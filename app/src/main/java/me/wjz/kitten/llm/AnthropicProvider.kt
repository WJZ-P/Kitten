package me.wjz.kitten.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 *
 * ## Prompt caching
 *
 * Agent 一轮要发十几次请求，每次都把系统提示词、工具清单、前面所有步骤重发一遍。服务端能把
 * "开头一模一样的那一段"算过的结果存起来，下次直接复用，命中的部分按原价的零头计费。这家
 * 要求**显式打标记**（`cache_control`），不打就一点缓存都没有。打两个（[CacheMarkers]）：
 *
 * - **系统提示词那一块**：工具清单排在它前面，一起缓存。这一段整轮不变，每一步都命中。
 * - **最后一条消息的最后一块**：每次请求往后挪一格。前面每一步留下的标记位置照样能读，所以每一步
 *   都复用之前全部的内容，只为新追加的那一截付原价。
 *
 * 缓存是按前缀逐字节匹配的，任何一处变了，从那里往后都不命中。能命中的前提我们本来就满足：
 * 一轮里系统提示词和工具清单不变、历史只往后加、JSON 字段顺序固定（这几条本来是 thinking 块
 * 要求的，见 [ReasoningBlock]）。改组请求的代码时要守住它们。
 *
 * 没用顶层的自动缓存字段（`cache_control` 放在请求最外层）：那是后加的，中转站不一定认，认不出
 * 来就是 400；显式标记是老字段，转发一般原样带过去。缓存时长用默认的 5 分钟：一轮里每步隔几秒，
 * 每次读取还会刷新计时，一直是热的。
 *
 * 命中了多少看 usage 里的 `cache_read_input_tokens` / `cache_creation_input_tokens`（见 [usageOf]），
 * 每步打在日志里。前缀太短（不同模型几百到几千 token 不等）时打了标记也不缓存，不报错。
 */
class AnthropicProvider(private val config: LlmConfig) : LlmProvider {

    override val displayName: String get() = "Anthropic"

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
                // max_tokens 在这个协议里是必填的，不像 OpenAI 可以省。
                put("max_tokens", config.maxTokens)
                // 不传 temperature（top_p、top_k 同理）：API 版本 2026-10-21 起，Messages 请求带这三个
                // 参数在所有模型上都是 400，哪怕传的是默认值。Claude 4.7 及以后的模型本来也只认默认值。
                // 写成块数组而不是字符串：缓存标记只能打在块上。
                if (system.isNotBlank()) put("system", CacheMarkers.system(system))
                put("messages", buildMessages(messages).also(CacheMarkers::markTail))
                if (tools.isNotEmpty()) put("tools", buildTools(tools))
            }
        }
        val headers = Headers.Builder()
            .add("x-api-key", config.apiKey)
            .add("anthropic-version", API_VERSION)
            .add("Content-Type", "application/json")
            .build()

        if (onTextDelta != null) return chatStreaming(body, headers, onTextDelta)

        val result = HttpTransport.postJson(endpoint(), headers, body)
        val json = result.getOrElse { return result.toLlmFailure() }
        return parse(json)
    }

    /**
     * 流式。回来的是一串带名字的事件，按 data 里的 type 分流：
     *
     * - `message_start`：里面有输入 token 数；
     * - `content_block_start`：开一个新块，text、tool_use 或 thinking；
     * - `content_block_delta`：往那个块里接东西，text_delta 是字，input_json_delta 是工具参数的
     *   一截 JSON，thinking_delta / signature_delta 是推理块的正文和签名（见 [PartialThinking]）；
     * - `message_delta`：stop_reason 和输出 token 数；`message_stop`：收尾；
     * - `error`：服务端在流里报错（比如过载）；`ping`：心跳，不用管。
     *
     * 有好几个文字块时用换行接起来，和非流式的 [parse] 一样：一路交出去的字接起来，就是最后
     * 返回的文字。
     */
    private suspend fun chatStreaming(
        body: JSONObject,
        headers: Headers,
        onTextDelta: (String) -> Unit,
    ): LlmResult {
        body.put("stream", true)
        val text = StringBuilder()
        // 按块的 index 记工具调用，参数一截截接上。
        val calls = sortedMapOf<Int, PartialToolCall>()
        // 同理记 thinking 块。按 index 排序，送回去时要保持原来的先后。
        val thinking = sortedMapOf<Int, PartialThinking>()
        var stopReason: String? = null
        // 输入那几个数在 message_start 里，输出在 message_delta 里。
        var usage = TokenUsage(inputTokens = 0, outputTokens = 0)
        var stopped = false
        var whole: LlmResult? = null

        try {
            HttpTransport.postSse(endpoint(), headers, body).collect { chunk ->
                when (chunk) {
                    is StreamChunk.Whole -> {
                        // 中转站不支持流式，回了一整个：按非流式解析，字一次全交出去。
                        val result = parse(chunk.json)
                        (result as? LlmResult.Ok)?.response?.text?.let(onTextDelta)
                        whole = result
                    }

                    is StreamChunk.Event -> {
                        val json = runCatching { JSONObject(chunk.data) }.getOrNull() ?: return@collect
                        when (json.optString("type")) {
                            "message_start" -> json.optJSONObject("message")?.optJSONObject("usage")?.let {
                                usage = usageOf(it)
                            }

                            "content_block_start" -> {
                                val block = json.optJSONObject("content_block") ?: return@collect
                                when (block.optString("type")) {
                                    "text" -> if (text.isNotBlank()) {
                                        text.append('\n')
                                        onTextDelta("\n")
                                    }

                                    "tool_use" -> calls[json.optInt("index")] =
                                        PartialToolCall(id = block.optString("id"), name = block.optString("name"))

                                    // thinking 块的壳先收着，内容和签名跟在后面的 delta 里。
                                    "thinking", "redacted_thinking" ->
                                        thinking[json.optInt("index")] = PartialThinking(block)
                                }
                            }

                            "content_block_delta" -> {
                                val delta = json.optJSONObject("delta") ?: return@collect
                                when (delta.optString("type")) {
                                    "text_delta" -> delta.optStringOrNull("text")?.takeIf { it.isNotEmpty() }?.let {
                                        text.append(it)
                                        onTextDelta(it)
                                    }

                                    "input_json_delta" ->
                                        calls[json.optInt("index")]?.arguments?.append(delta.optString("partial_json"))

                                    // 推理的正文和签名分两种 delta 来，都往同一个块上攒。
                                    "thinking_delta" ->
                                        thinking[json.optInt("index")]?.thinking?.append(delta.optString("thinking"))

                                    "signature_delta" ->
                                        thinking[json.optInt("index")]?.signature?.append(delta.optString("signature"))
                                }
                            }

                            "message_delta" -> {
                                json.optJSONObject("delta")?.optStringOrNull("stop_reason")?.let { stopReason = it }
                                json.optJSONObject("usage")?.let { latest ->
                                    // 这里的数是累计值：一定有输出数，输入那几个有的话也以这里为准。
                                    usage = usageOf(latest, fallback = usage)
                                }
                            }

                            "message_stop" -> stopped = true

                            "error" -> throw LlmException(streamError(json.optJSONObject("error")))
                        }
                    }
                }
            }
        } catch (e: LlmException) {
            return LlmResult.Failed(e.error)
        }

        whole?.let { return it }
        // 没等到 message_stop：连接没收尾就断了，回复多半不完整。
        if (!stopped) return LlmResult.Failed(LlmError.Interrupted("连接没收尾就断了"))

        val toolCalls = calls.values.map { call ->
            ToolCall(id = call.id, name = call.name, arguments = call.arguments.toString().ifBlank { "{}" })
        }
        val finalText = text.toString().takeIf { it.isNotBlank() }
        if (finalText == null && toolCalls.isEmpty()) {
            Log.w(TAG, "模型既没说话也没调工具（流式），stop_reason=$stopReason")
        }
        return LlmResult.Ok(
            ChatResponse(
                text = finalText,
                toolCalls = toolCalls,
                stopReason = stopReason,
                usage = usage,
                reasoning = thinking.values.map { it.build() },
            ),
        )
    }

    /** 流里的 error 事件：限流、认证照常归类，过载之类的归成"回复到一半中断"。 */
    private fun streamError(error: JSONObject?): LlmError {
        val message = error?.optStringOrNull("message")
        return when (error?.optString("type")) {
            "rate_limit_error" -> LlmError.RateLimited(retryAfterSeconds = null)
            "authentication_error", "permission_error" -> LlmError.Unauthorized(message)
            else -> LlmError.Interrupted(message)
        }
    }

    /** 流式时一次工具调用的半成品：id、名字在块开头就给了，参数一截截地接。 */
    private class PartialToolCall(val id: String, val name: String) {
        val arguments = StringBuilder()
    }

    /**
     * 流式时一个 thinking 块的半成品。
     *
     * [shell] 是 content_block_start 给的那个壳（带 type，redacted 的还带 data），正文和签名
     * 跟在后面的 delta 里，攒齐了再填回壳里。签名必须和服务端算的一模一样，所以只做拼接，
     * 不做任何加工。
     */
    private class PartialThinking(private val shell: JSONObject) {
        val thinking = StringBuilder()
        val signature = StringBuilder()

        fun build(): ReasoningBlock {
            // 拷一份再改，别动原来那个壳。
            val block = JSONObject(shell.toString())
            if (thinking.isNotEmpty()) block.put("thinking", thinking.toString())
            if (signature.isNotEmpty()) block.put("signature", signature.toString())
            return ReasoningBlock(block.toString())
        }
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
                is ChatMessage.ToolResult -> {
                    // 带图时 content 要写成块数组（图在前、说明在后）；纯文字时还是写字符串，
                    // 各家兼容端点对字符串形式的接受度更高，没必要为了统一而都换成数组。
                    val images = message.images.mapNotNull { image ->
                        image.readBase64()?.let { image to it }
                    }
                    val content: Any = if (images.isEmpty()) {
                        message.content
                    } else {
                        JSONArray().apply {
                            images.forEach { (image, data) -> put(imageBlock(image, data)) }
                            put(JSONObject().put("type", "text").put("text", message.content))
                        }
                    }
                    append(
                        "user",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "tool_result")
                                .put("tool_use_id", message.callId)
                                .put("content", content),
                        ),
                    )
                }

                is ChatMessage.User -> append("user", userBlocks(message))

                is ChatMessage.Assistant -> {
                    val content = JSONArray()
                    // thinking 块要排在最前面、而且一个字节都不能改（签名是按原文算的）。
                    // 带回去模型才接得上上一步的思路，见 ReasoningBlock。
                    message.reasoning.forEach { block ->
                        runCatching { JSONObject(block.raw) }.getOrNull()?.let(content::put)
                    }
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

    /**
     * 用户消息的内容块：每张图一块 base64 的 image，再是字。图放在字前面，官方建议先给图、
     * 再问问题。字是空的就不放文字块（这个协议不收空的文字块）；图片文件读不到就跳过那一张。
     */
    private fun userBlocks(message: ChatMessage.User): JSONArray = JSONArray().apply {
        message.images.forEach { image ->
            val data = image.readBase64() ?: return@forEach
            put(imageBlock(image, data))
        }
        if (message.text.isNotBlank()) put(JSONObject().put("type", "text").put("text", message.text))
    }

    /** 一张内嵌 base64 的图片块。用户消息和工具结果都用它。 */
    private fun imageBlock(image: ImageAttachment, base64: String): JSONObject =
        JSONObject()
            .put("type", "image")
            .put(
                "source",
                JSONObject()
                    .put("type", "base64")
                    .put("media_type", image.mediaType)
                    .put("data", base64),
            )

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
        val reasoning = mutableListOf<ReasoningBlock>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> block.optString("text").takeIf { it.isNotBlank() }?.let(texts::add)
                // 原样收着，下一轮原样送回去。
                "thinking", "redacted_thinking" -> reasoning += ReasoningBlock(block.toString())
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
                usage = json.optJSONObject("usage")?.let(::usageOf),
                reasoning = reasoning,
            ),
        )
    }

    /**
     * 把 usage 换成统一的 [TokenUsage]。这家的 `input_tokens` 本来就只算没命中缓存的部分，
     * 命中和新写入的另有两个字段，三个加起来才是整个输入。
     *
     * [fallback]：流式时 message_delta 里的 usage 不一定带输入那几个数，没带的沿用 message_start
     * 时拿到的。
     */
    private fun usageOf(json: JSONObject, fallback: TokenUsage? = null): TokenUsage {
        fun count(key: String, old: Int?): Int =
            if (json.has(key)) json.optInt(key) else old ?: 0
        return TokenUsage(
            inputTokens = count("input_tokens", fallback?.inputTokens),
            outputTokens = count("output_tokens", fallback?.outputTokens),
            cacheReadTokens = count("cache_read_input_tokens", fallback?.cacheReadTokens),
            cacheWriteTokens = count("cache_creation_input_tokens", fallback?.cacheWriteTokens),
        )
    }

    /** 缓存标记打在哪，原因见类注释的 Prompt caching 一节。 */
    private object CacheMarkers {

        /** 系统提示词写成一个文本块，块上打标记：它和排在前面的工具清单一起缓存。 */
        fun system(text: String): JSONArray = JSONArray().put(
            JSONObject()
                .put("type", "text")
                .put("text", text)
                .put("cache_control", ephemeral()), //  目前缓存策略都是五分钟过期。
        )

        /**
         * 在最后一条消息的最后一块上打标记。
         *
         * 从后往前跳过 thinking 块：这家不让在它们上面打标记。最后一条正常是用户的话或者工具结果，
         * 实际碰不到，防一手。
         */
        fun markTail(messages: JSONArray) {
            val content = messages.optJSONObject(messages.length() - 1)?.optJSONArray("content") ?: return
            for (i in content.length() - 1 downTo 0) {
                val block = content.optJSONObject(i) ?: continue
                if (block.optString("type") in UnmarkableTypes) continue
                block.put("cache_control", ephemeral())
                return
            }
        }

        /** 默认的 5 分钟缓存。每次新建：同一个对象挂在两处，改一处另一处跟着变。 */
        private fun ephemeral() = JSONObject().put("type", "ephemeral")

        private val UnmarkableTypes = setOf("thinking", "redacted_thinking")
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
