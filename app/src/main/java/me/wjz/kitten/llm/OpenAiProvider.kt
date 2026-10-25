package me.wjz.kitten.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 的 Chat Completions 协议，以及所有兼容它的服务。
 *
 * 国产模型基本都走这里——DeepSeek、通义、智谱、Moonshot、MiniMax，还有本地的
 * Ollama / vLLM，换个 [LlmConfig.baseUrl] 就行，不必各写一个 provider。
 *
 * ## 但"兼容"的程度参差不齐
 *
 * 基础的对话几乎都没问题，**tool use 才是分水岭**，实际遇到过的坑：
 * - 不支持并行调用，一轮只肯返回一个 tool_call；
 * - `tool_choice` 传了不生效，模型该不调还是不调；
 * - 只认已废弃的 `functions`/`function_call` 旧字段；
 * - `arguments` 返回的不是合法 JSON（少引号、带 markdown 代码块包裹）。
 *
 * 前三个只能实测，最后一个在 [ToolCall.argumentsJson] 里兜了一道。
 */
class OpenAiProvider(private val config: LlmConfig) : LlmProvider {

    override val displayName: String
        get() = if (config.format == LlmFormat.OPENAI) "OpenAI" else "OpenAI 兼容"

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onTextDelta: ((String) -> Unit)?,
    ): LlmResult {
        if (!config.isConfigured) return LlmResult.Failed(LlmError.NotConfigured)

        // 组请求要把消息里的图片读出来编码，是磁盘 IO，不能在主线程做。
        val body = withContext(Dispatchers.IO) {
            JSONObject().apply {
                put("model", config.model)
                put("max_tokens", config.maxTokens)
                put("temperature", config.temperature)
                put("messages", buildMessages(system, messages))
                if (tools.isNotEmpty()) put("tools", buildTools(tools))
            }
        }
        val headers = Headers.Builder()
            .add("Authorization", "Bearer ${config.apiKey}")
            .add("Content-Type", "application/json")
            .build()

        if (onTextDelta != null) return chatStreaming(body, headers, onTextDelta)

        val result = HttpTransport.postJson(endpoint(), headers, body)
        val json = result.getOrElse { return result.toLlmFailure() }
        return parse(json)
    }

    /**
     * 流式。每一块是 `data: {"choices":[{"delta":{...}}]}`，最后一条是 `data: [DONE]`：
     *
     * - 文字在 delta.content 里，一段段接起来；
     * - 工具调用也是拆开给的：delta.tool_calls[] 带着 index，第一块给 id 和名字，后面的块只给
     *   arguments 的一截，按 index 拼回去；
     * - finish_reason 在最后一块里；服务端在流里报错时，那一块是 `{"error": {...}}`。
     */
    private suspend fun chatStreaming(
        body: JSONObject,
        headers: Headers,
        onTextDelta: (String) -> Unit,
    ): LlmResult {
        body.put("stream", true)
        val text = StringBuilder()
        val calls = sortedMapOf<Int, PartialToolCall>()
        var finishReason: String? = null
        var usage: TokenUsage? = null
        var done = false
        var whole: LlmResult? = null

        try {
            HttpTransport.postSse(endpoint(), headers, body).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Whole -> {
                        // 服务端不支持流式，回了一整个：按非流式解析，字一次全交出去。
                        val result = parse(chunk.json)
                        (result as? LlmResult.Ok)?.response?.text?.let(onTextDelta)
                        whole = result
                    }

                    is StreamChunk.Event -> {
                        if (chunk.data == "[DONE]") {
                            done = true
                            return@collect
                        }
                        val json = runCatching { JSONObject(chunk.data) }.getOrNull() ?: return@collect
                        json.optJSONObject("error")?.let { error ->
                            throw LlmException(LlmError.Interrupted(error.optStringOrNull("message")))
                        }
                        json.optJSONObject("usage")?.let {
                            usage = TokenUsage(
                                inputTokens = it.optInt("prompt_tokens"),
                                outputTokens = it.optInt("completion_tokens"),
                            )
                        }
                        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return@collect
                        choice.optStringOrNull("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
                        val delta = choice.optJSONObject("delta") ?: return@collect
                        delta.optStringOrNull("content")?.takeIf { it.isNotEmpty() }?.let {
                            text.append(it)
                            onTextDelta(it)
                        }
                        delta.optJSONArray("tool_calls")?.let { array ->
                            for (i in 0 until array.length()) {
                                val item = array.optJSONObject(i) ?: continue
                                val call = calls.getOrPut(item.optInt("index", i)) { PartialToolCall() }
                                item.optStringOrNull("id")?.takeIf { it.isNotBlank() }?.let { call.id = it }
                                item.optJSONObject("function")?.let { function ->
                                    function.optStringOrNull("name")?.takeIf { it.isNotBlank() }?.let { call.name = it }
                                    function.optStringOrNull("arguments")?.let { call.arguments.append(it) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: LlmException) {
            return LlmResult.Failed(e.error)
        }

        whole?.let { return it }
        // 既没等到 [DONE]、也没有 finish_reason：连接没收尾就断了，回复多半不完整。
        if (!done && finishReason == null) return LlmResult.Failed(LlmError.Interrupted("连接没收尾就断了"))

        val toolCalls = calls.map { (index, call) ->
            ToolCall(
                // 同非流式：少数兼容端点不给 id，用下标顶上。
                id = call.id ?: "call_$index",
                name = call.name.orEmpty(),
                arguments = call.arguments.toString().ifBlank { "{}" },
            )
        }
        val finalText = text.toString().takeIf { it.isNotBlank() }
        if (finalText == null && toolCalls.isEmpty()) {
            Log.w(TAG, "模型既没说话也没调工具（流式），finish_reason=$finishReason")
        }
        return LlmResult.Ok(
            ChatResponse(text = finalText, toolCalls = toolCalls, stopReason = finishReason, usage = usage),
        )
    }

    /** 流式时一次工具调用的半成品：id、名字先到，参数一截截地接。 */
    private class PartialToolCall {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    override suspend fun listModels(): ModelListResult {
        // 本地 Ollama 这类服务不需要 Key，有就带上，没有就不带。
        val headers = Headers.Builder()
            .apply { if (config.apiKey.isNotBlank()) add("Authorization", "Bearer ${config.apiKey}") }
            .build()
        val result = HttpTransport.getJson("${base()}/models", headers)
        val json = result.getOrElse { return ModelListResult.Failed(result.toLlmFailure().error) }
        return json.modelIds()
    }

    private fun endpoint(): String = "${base()}/chat/completions"

    /** 根地址。有些用户填的是完整的 /chat/completions 地址，去掉它，两种都收下免得反复试。 */
    private fun base(): String =
        config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/').removeSuffix("/chat/completions")

    /**
     * system 在这个协议里是 messages 的第一条，而不是独立字段（Anthropic 那边才是）。
     */
    private fun buildMessages(system: String, messages: List<ChatMessage>): JSONArray =
        JSONArray().apply {
            if (system.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", system))
            }
            messages.forEach { message ->
                when (message) {
                    is ChatMessage.User ->
                        put(JSONObject().put("role", "user").put("content", userContent(message)))

                    is ChatMessage.Assistant -> put(
                        JSONObject().apply {
                            put("role", "assistant")
                            // content 为 null 是合法的（纯工具调用那一轮），但有些兼容端点
                            // 不接受缺字段，统一给空串更稳。
                            put("content", message.text ?: "")
                            if (message.toolCalls.isNotEmpty()) {
                                put("tool_calls", JSONArray().apply {
                                    message.toolCalls.forEach { call ->
                                        put(
                                            JSONObject()
                                                .put("id", call.id)
                                                .put("type", "function")
                                                .put(
                                                    "function",
                                                    JSONObject()
                                                        .put("name", call.name)
                                                        .put("arguments", call.arguments),
                                                ),
                                        )
                                    }
                                })
                            }
                        },
                    )

                    // 工具结果在这个协议里是独立的一条消息，靠 tool_call_id 和调用配对。
                    is ChatMessage.ToolResult -> {
                        put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", message.callId)
                                .put("name", message.name)
                                .put("content", message.content),
                        )
                        // tool 消息的 content 只能是字符串，图片塞不进去（Anthropic 那边可以）。
                        // 截图只好另发一条 user 消息补上，并说清楚它是哪一步的产物，不然模型
                        // 会以为是用户突然发了张图。
                        val images = message.images.mapNotNull { image ->
                            image.readBase64()?.let { "data:${image.mediaType};base64,$it" }
                        }
                        if (images.isNotEmpty()) {
                            put(
                                JSONObject().put("role", "user").put(
                                    "content",
                                    JSONArray().apply {
                                        put(
                                            JSONObject()
                                                .put("type", "text")
                                                .put("text", "（上一步 ${message.name} 拿到的画面）"),
                                        )
                                        images.forEach { url ->
                                            put(
                                                JSONObject()
                                                    .put("type", "image_url")
                                                    .put("image_url", JSONObject().put("url", url)),
                                            )
                                        }
                                    },
                                ),
                            )
                        }
                    }
                }
            }
        }

    /**
     * 用户消息的 content。只有字时是一个字符串，所有兼容服务都认；带图片时是一个数组：字一块，
     * 每张图一块，图用 data URL 把 base64 直接内嵌进去。不支持看图的服务会报错，错误原样告诉
     * 用户。图片文件读不到（被清理了）就跳过那一张。
     */
    private fun userContent(message: ChatMessage.User): Any {
        val images = message.images.mapNotNull { image ->
            image.readBase64()?.let { "data:${image.mediaType};base64,$it" }
        }
        if (images.isEmpty()) return message.text
        return JSONArray().apply {
            if (message.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.text))
            images.forEach { url ->
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", url)))
            }
        }
    }

    private fun buildTools(tools: List<ToolSpec>): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parameters),
                    ),
            )
        }
    }

    private fun parse(json: JSONObject): LlmResult {
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: return LlmResult.Failed(
                LlmError.MalformedResponse("响应里没有 choices：${json.toString().take(200)}"),
            )
        val message = choice.optJSONObject("message")
            ?: return LlmResult.Failed(LlmError.MalformedResponse("choices[0] 里没有 message"))

        val text = message.optStringOrNull("content")?.takeIf { it.isNotBlank() }
        val toolCalls = message.optJSONArray("tool_calls")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val item = array.optJSONObject(i) ?: return@mapNotNull null
                val function = item.optJSONObject("function") ?: return@mapNotNull null
                ToolCall(
                    // id 必须原样带回去，模型靠它把结果和调用配对。少数兼容端点不给 id，
                    // 那就用下标顶上，总比没有强。
                    id = item.optString("id").ifBlank { "call_$i" },
                    name = function.optString("name"),
                    arguments = function.optString("arguments").ifBlank { "{}" },
                )
            }
        }.orEmpty()

        if (text == null && toolCalls.isEmpty()) {
            Log.w(TAG, "模型既没说话也没调工具：${json.toString().take(300)}")
        }
        return LlmResult.Ok(
            ChatResponse(
                text = text,
                toolCalls = toolCalls,
                stopReason = choice.optString("finish_reason").takeIf { it.isNotBlank() },
                usage = json.optJSONObject("usage")?.let {
                    TokenUsage(
                        inputTokens = it.optInt("prompt_tokens"),
                        outputTokens = it.optInt("completion_tokens"),
                    )
                },
            ),
        )
    }

    private companion object {
        const val TAG = "KittenLlm"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
    }
}
