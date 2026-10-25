package me.wjz.kitten.llm

import android.util.Log
import okhttp3.Headers
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic 的 Messages 协议。
 *
 * 和 OpenAI 的差异集中在消息怎么组织，逐条都踩过：
 *
 * - **system 是顶层字段**，不是 messages 里的一条；
 * - **工具定义叫 `input_schema`**，不是 `parameters`（schema 本身的写法一样）；
 * - **工具调用混在 `content` 数组里**，type=`tool_use` 的那些块，而不是独立的
 *   `tool_calls` 字段；同一个数组里还可能夹着 type=`text` 的说话内容；
 * - **工具结果要包成 `role=user` 的消息**，里面放 type=`tool_result` 的块——
 *   没有 `role=tool` 这种角色。连续几个工具结果**必须合并进同一条 user 消息**，
 *   拆成多条会被拒；
 * - 认证头是 `x-api-key`，而且必须带 `anthropic-version`。
 */
class AnthropicProvider(private val config: LlmConfig) : LlmProvider {

    override val displayName: String get() = "Anthropic"

    override suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
    ): LlmResult {
        if (!config.isConfigured) return LlmResult.Failed(LlmError.NotConfigured)

        val body = JSONObject().apply {
            put("model", config.model)
            // max_tokens 在这个协议里是必填的，不像 OpenAI 可以省。
            put("max_tokens", config.maxTokens)
            // 不传 temperature（top_p、top_k 同理）：API 版本 2026-10-21 起，Messages 请求带这三个
            // 参数在所有模型上都是 400，哪怕传的是默认值。Claude 4.7 及以后的模型本来也只认默认值。
            if (system.isNotBlank()) put("system", system)
            put("messages", buildMessages(messages))
            if (tools.isNotEmpty()) put("tools", buildTools(tools))
        }
        val headers = Headers.Builder()
            .add("x-api-key", config.apiKey)
            .add("anthropic-version", API_VERSION)
            .add("Content-Type", "application/json")
            .build()

        val result = HttpTransport.postJson(endpoint(), headers, body)
        val json = result.getOrElse { return result.toLlmFailure() }
        return parse(json)
    }

    override suspend fun listModels(): ModelListResult {
        val headers = Headers.Builder()
            .add("x-api-key", config.apiKey)
            .add("anthropic-version", API_VERSION)
            .build()
        // 默认一页只给 20 个，要一次拿全。
        val result = HttpTransport.getJson("${base()}/models?limit=$MODELS_PAGE_LIMIT", headers)
        val json = result.getOrElse { return ModelListResult.Failed(result.toLlmFailure().error) }
        return json.modelIds()
    }

    private fun endpoint(): String = "${base()}/messages"

    /** 根地址。有些用户填的是完整的 /messages 地址，去掉它。 */
    private fun base(): String =
        config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/').removeSuffix("/messages")

    /**
     * 组装 messages。
     *
     * 这个协议对消息的排法有两条硬要求，不满足就是 400：
     *
     * - **第一条必须是 user。** 用户把开头的提问删掉以后，对话会以模型的回复开头——这种开头
     *   的回复（连同它的工具结果）没有提问可依，直接丢掉。
     * - **同一方的消息不能挨着分成好几条。** 模型一轮里并发调用多个工具时，它们的结果要装进
     *   同一条 user 消息；用户删掉中间某条消息后，也会出现两条 user 或两条 assistant 挨在
     *   一起。统一处理：和上一条是同一方，就并进上一条的 content 里。
     */
    private fun buildMessages(messages: List<ChatMessage>): JSONArray {
        val array = JSONArray()

        fun append(role: String, blocks: JSONArray) {
            if (blocks.length() == 0) return
            val last = array.optJSONObject(array.length() - 1)
            if (last != null && last.getString("role") == role) {
                val content = last.getJSONArray("content")
                for (i in 0 until blocks.length()) content.put(blocks.get(i))
            } else {
                array.put(JSONObject().put("role", role).put("content", blocks))
            }
        }

        messages.dropWhile { it !is ChatMessage.User }.forEach { message ->
            when (message) {
                is ChatMessage.ToolResult -> append(
                    "user",
                    JSONArray().put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", message.callId)
                            .put("content", message.content),
                    ),
                )

                is ChatMessage.User -> append(
                    "user",
                    JSONArray().put(JSONObject().put("type", "text").put("text", message.text)),
                )

                is ChatMessage.Assistant -> {
                    val content = JSONArray()
                    message.text?.takeIf { it.isNotBlank() }?.let {
                        content.put(JSONObject().put("type", "text").put("text", it))
                    }
                    message.toolCalls.forEach { call ->
                        content.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", call.id)
                                .put("name", call.name)
                                // 这边要的是解析好的对象，不是 OpenAI 那样的字符串。
                                .put("input", call.argumentsJson()),
                        )
                    }
                    append("assistant", content)
                }
            }
        }
        return array
    }

    private fun buildTools(tools: List<ToolSpec>): JSONArray = JSONArray().apply {
        tools.forEach { tool ->
            put(
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    // 叫 input_schema，不是 parameters——schema 内容本身是一样的。
                    .put("input_schema", tool.parameters),
            )
        }
    }

    /**
     * 解析响应。
     *
     * content 是个混合数组：说话的内容和工具调用混在一起，要按 type 分流。
     */
    private fun parse(json: JSONObject): LlmResult {
        val content = json.optJSONArray("content")
            ?: return LlmResult.Failed(
                LlmError.MalformedResponse("响应里没有 content：${json.toString().take(200)}"),
            )

        val texts = mutableListOf<String>()
        val toolCalls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                "tool_use" -> toolCalls += ToolCall(
                    id = block.optString("id"),
                    name = block.optString("name"),
                    // 统一成字符串，和 OpenAI 的 arguments 对齐，上层不用区分两家。
                    arguments = block.optJSONObject("input")?.toString() ?: "{}",
                )
            }
        }

        val text = texts.joinToString("\n").takeIf { it.isNotBlank() }
        if (text == null && toolCalls.isEmpty()) {
            Log.w(TAG, "模型既没说话也没调工具：${json.toString().take(300)}")
        }
        return LlmResult.Ok(
            ChatResponse(
                text = text,
                toolCalls = toolCalls,
                stopReason = json.optString("stop_reason").takeIf { it.isNotBlank() },
                usage = json.optJSONObject("usage")?.let {
                    TokenUsage(
                        inputTokens = it.optInt("input_tokens"),
                        outputTokens = it.optInt("output_tokens"),
                    )
                },
            ),
        )
    }

    private companion object {
        const val TAG = "KittenLlm"
        const val DEFAULT_BASE_URL = "https://api.anthropic.com/v1"

        /**
         * 必填的协议版本头，不带会被拒。2026-10-21 起 Messages 请求不能再带 temperature / top_p /
         * top_k，所以上面组请求时不传。迁移说明：https://platform.claude.com/docs/en/api/migration-2026-10-21
         */
        const val API_VERSION = "2026-10-21"

        /** 模型列表一页最多要多少个（接口允许的上限是 1000）。 */
        const val MODELS_PAGE_LIMIT = 1000
    }
}
