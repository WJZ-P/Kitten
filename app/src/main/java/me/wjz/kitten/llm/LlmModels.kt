package me.wjz.kitten.llm

import org.json.JSONObject

/**
 * 一轮对话里的消息。
 *
 * 这是**与厂商无关**的中间表示：Agent 循环只跟它打交道，各家 API 的报文差异由
 * [LlmProvider] 的实现去翻译。OpenAI 和 Anthropic 在这件事上分歧不小——比如工具结果，
 * 前者是一条 `role=tool` 的独立消息，后者是塞进 `role=user` 的一个内容块——如果让
 * 上层直接拼各家的 JSON，换个模型就得重写一遍 Agent。
 */
sealed interface ChatMessage {

    /** 用户说的话。 */
    data class User(val text: String) : ChatMessage

    /**
     * 模型的回复。
     *
     * [text] 和 [toolCalls] 可以同时存在——模型经常先说一句"我来看看屏幕上有什么"，
     * 再发起工具调用。两者都为空是异常响应。
     */
    data class Assistant(
        val text: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
    ) : ChatMessage

    /**
     * 工具执行完的结果，回灌给模型。
     *
     * [callId] 必须原样回传模型给的那个 id，它靠这个把结果和自己发起的调用对上号——
     * 一轮里可能并发调好几个工具。
     */
    data class ToolResult(
        val callId: String,
        val name: String,
        val content: String,
    ) : ChatMessage
}

/**
 * 模型发起的一次工具调用。
 *
 * [arguments] 是 JSON 字符串而不是解析好的对象：各家返回的都是字符串，而且参数
 * schema 由工具自己定义，这里不做任何假设，交给工具的执行方自行解析。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
) {
    /** 解析参数；模型偶尔会吐出不合法的 JSON，所以失败返回空对象而不是抛异常。 */
    fun argumentsJson(): JSONObject =
        runCatching { JSONObject(arguments) }.getOrElse { JSONObject() }
}

/**
 * 一个工具的定义，交给模型让它知道自己能干什么。
 *
 * [parameters] 是 JSON Schema。两家的字段名不同（OpenAI 叫 `parameters`，
 * Anthropic 叫 `input_schema`），但 schema 本身的写法是一致的，所以这里存原始
 * JSONObject，由各 provider 放进各自的位置。
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/** 模型这一轮的回复。 */
data class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    /** 原样保留厂商给的停止原因，便于排查"为什么没继续"。 */
    val stopReason: String?,
    val usage: TokenUsage?,
) {
    /** 模型要求执行工具——Agent 循环要继续转。 */
    val needsToolExecution: Boolean
        get() = toolCalls.isNotEmpty()
}

/**
 * token 用量。
 *
 * Agent 每步都要把整屏节点树喂进去，上下文涨得很快，这个数字值得一直盯着——
 * 它直接决定了压缩策略要不要再激进些。
 */
data class TokenUsage(val inputTokens: Int, val outputTokens: Int) {
    val total: Int get() = inputTokens + outputTokens
}

/**
 * 调用失败的原因。
 *
 * 分这么细是因为处理方式完全不同：认证错了要让用户去改配置，限流该退避重试，
 * 网络抖动可以直接重试，而模型返回的内容不合预期多半是提示词或 schema 的问题。
 */
sealed interface LlmError {

    fun describe(): String

    /** 没配 API key 或 endpoint。 */
    data object NotConfigured : LlmError {
        override fun describe() = "未配置模型：请先填写 API Key 和模型名"
    }

    /** 401/403。key 不对、过期、或没有这个模型的权限。 */
    data class Unauthorized(val detail: String?) : LlmError {
        override fun describe() = "认证失败，请检查 API Key${detail?.let { "：$it" } ?: ""}"
    }

    /** 429 或厂商的限流错误。 */
    data class RateLimited(val retryAfterSeconds: Int?) : LlmError {
        override fun describe() =
            "请求过于频繁" + (retryAfterSeconds?.let { "，请 $it 秒后重试" } ?: "，请稍后重试")
    }

    /** 服务端 5xx，或者厂商自己的故障。 */
    data class ServerError(val code: Int, val detail: String?) : LlmError {
        override fun describe() = "模型服务异常（HTTP $code）${detail?.let { "：$it" } ?: ""}"
    }

    /** 连不上、超时、DNS 失败。 */
    data class Network(val detail: String?) : LlmError {
        override fun describe() = "网络错误${detail?.let { "：$it" } ?: ""}"
    }

    /** 响应能拿到，但结构不是预期的样子。 */
    data class MalformedResponse(val detail: String) : LlmError {
        override fun describe() = "模型返回了无法解析的内容：$detail"
    }

    /**
     * 地址打到了网站页面上，而不是接口：回来的是一张网页。中转站的控制台对不认识的路径
     * 一律回首页，地址少拼了 /v1 时就是这样。会先试着自己补上 /v1，见 [EndpointRepair]。
     */
    data class WrongEndpoint(val url: String) : LlmError {
        override fun describe() =
            "地址返回的是网页，不是接口数据。多半是地址少了 /v1 之类的路径，实际请求的是：$url"
    }
}

/** 调用结果。失败带原因，不用异常——这些失败都是预期内的，不是程序错误。 */
sealed interface LlmResult {
    data class Ok(val response: ChatResponse) : LlmResult
    data class Failed(val error: LlmError) : LlmResult
}
