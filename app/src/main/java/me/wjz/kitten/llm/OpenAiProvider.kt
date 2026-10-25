package me.wjz.kitten.llm

import android.util.Log
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
    ): LlmResult {
        if (!config.isConfigured) return LlmResult.Failed(LlmError.NotConfigured)

        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("messages", buildMessages(system, messages))
            if (tools.isNotEmpty()) put("tools", buildTools(tools))
        }
        val headers = Headers.Builder()
            .add("Authorization", "Bearer ${config.apiKey}")
            .add("Content-Type", "application/json")
            .build()

        val result = HttpTransport.postJson(endpoint(), headers, body)
        val json = result.getOrElse { return result.toLlmFailure() }
        return parse(json)
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
                        put(JSONObject().put("role", "user").put("content", message.text))

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
                    is ChatMessage.ToolResult -> put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", message.callId)
                            .put("name", message.name)
                            .put("content", message.content),
                    )
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

        val text = message.optString("content").takeIf { it.isNotBlank() }
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
