package me.wjz.kitten.llm

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
     * @return 失败不抛异常——网络错误、限流、key 失效都是预期内的情况，
     *         调用方需要据此分流（重试 / 提示用户改配置 / 放弃）。
     */
    suspend fun chat(
        system: String,
        messages: List<ChatMessage>,
        tools: List<ToolSpec> = emptyList(),
    ): LlmResult
}
