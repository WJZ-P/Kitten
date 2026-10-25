package me.wjz.kitten.llm

import org.json.JSONObject

/**
 * 一家模型服务的接入方式。
 *
 * ## 为什么要这层抽象
 *
 * Agent 循环只认 [ChatMessage]/[ToolSpec] 这套中间表示，各家 API 的报文差异全部关在
 * provider 实现里。两家的分歧比看上去大：
 *
 * | | OpenAI | Anthropic |
 * |---|---|---|
 * | system 放哪 | messages 里的一条 | 顶层独立字段 |
 * | 工具定义 | `tools[].function.parameters` | `tools[].input_schema` |
 * | 工具调用 | `message.tool_calls[]` | `content[]` 里 type=tool_use 的块 |
 * | 工具结果 | 独立的 `role=tool` 消息 | 塞进 `role=user` 的 tool_result 块 |
 * | 认证头 | `Authorization: Bearer` | `x-api-key` + `anthropic-version` |
 *
 * 让上层直接拼某一家的 JSON，换模型就得重写 Agent。
 *
 * ## 国产模型
 *
 * 绝大多数（DeepSeek、通义、智谱、Moonshot、MiniMax…）都提供 OpenAI 兼容端点，
 * 直接用 [OpenAiProvider] 换 baseUrl 即可，不必各写一个。真正需要单独适配的是那些
 * 只有自研协议的，等真要支持时再加实现。
 *
 * 不过「兼容」的程度参差不齐，**tool use 尤其容易缺斤少两**——有的不支持并行调用，
 * 有的 `tool_choice` 不生效，有的干脆只认 function calling 的旧格式。这些只能实测。
 */
interface LlmProvider {

    /** 给用户看的名字。 */
    val displayName: String

    /**
     * 发起一轮对话。
     *
     * @param system 系统提示词。告诉模型它是谁、节点树怎么读、错误码什么意思。
     * @param messages 历史消息，按时间顺序。
     * @param tools 这一轮可用的工具；空列表表示纯聊天。
     * @param onTextDelta 传了就走流式：模型每生成一小段字就调一次，参数是新冒出来的那一段，
     *        把它们按顺序接起来就是最终回复的文字。在调用方的协程里调用。最后照样返回完整的
     *        结果，失败的处理和不传时一样——只是失败前可能已经交出过一些字了。
     * @return 失败不抛异常——网络错误、限流、key 失效都是预期内的情况，
     *         调用方需要据此分流（重试 / 提示用户改配置 / 放弃）。
     */
    suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
    ): LlmResult

    /**
     * 这家服务能用的模型名，给设置页的下拉条用。
     *
     * 两家协议都有 `GET /models`，兼容 OpenAI 的服务（DeepSeek、Ollama…）大多也有。没有的
     * 会返回 404 之类的失败，用户可以手填模型名。失败不抛异常，和 [chat] 一样归类成 [LlmError]。
     */
    suspend fun listModels(): ModelListResult
}

sealed interface ModelListResult {
    /** 按名字排好序。 */
    data class Ok(val models: List<String>) : ModelListResult
    data class Failed(val error: LlmError) : ModelListResult
}

/**
 * 取一个字符串字段，没有或者是 JSON 的 null 时返回 null。
 *
 * 别用 optString 代替：org.json 碰到 null 值会把它转成字符串 "null"——纯工具调用那一轮
 * content 就是 null，用 optString 会变成一句"null"显示出来。
 */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)

/** 从 `{"data": [{"id": "..."}, ...]}` 里取出模型名。两家的列表都是这个形状。 */
internal fun JSONObject.modelIds(): ModelListResult {
    val data = optJSONArray("data")
        ?: return ModelListResult.Failed(LlmError.MalformedResponse("模型列表里没有 data 字段：${toString().take(200)}"))
    val ids = (0 until data.length())
        .mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
        .distinct()
        .sorted()
    return ModelListResult.Ok(ids)
}
