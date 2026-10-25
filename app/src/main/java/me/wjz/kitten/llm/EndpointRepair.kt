package me.wjz.kitten.llm

/**
 * API 地址少拼了 /v1 时，自己补上。
 *
 * 自建中转、第三方服务填地址时最常漏的就是 /v1：漏了的话请求会打到网站页面上（中转站的
 * 控制台对不认识的路径一律回首页），拿回一张网页（[LlmError.WrongEndpoint]），或者一个 404。
 *
 * ## 什么时候猜
 *
 * - 请求失败、而且看起来是"打错了地方"（[isWrongPath]）；
 * - 地址里本来没有版本段。`/v1`、`/v4`、`compatible-mode/v1` 这种已经带了版本的不乱猜：
 *   那多半是别的问题，补一个 /v1 只会更错。
 *
 * ## 怎么验证
 *
 * 补上 /v1 去拉一次模型列表（GET /models）。回来的是接口数据——拉到了列表，或者是 401
 * "Key 不对"——都说明这个地址上确实是接口，补对了；还是网页、404、连不上，就当没猜中，
 * 不动用户的设置。
 *
 * 用 /models 而不是直接重发原来的请求：它是 GET、不带对话内容，不会白白再发一轮消息、
 * 也不会产生费用。
 */
object EndpointRepair {

    /** 地址里的版本段：/v1、/v4 这种，后面是斜杠或者结尾。 */
    private val VersionSegment = Regex("""/v\d+(/|$)""")

    /** 这个错误像不像"地址打错了地方"：回的是网页，或者 404。 */
    fun isWrongPath(error: LlmError): Boolean =
        error is LlmError.WrongEndpoint || (error is LlmError.ServerError && error.code == 404)

    /**
     * 试着在 [config] 的地址后面补上 /v1。补对了返回新地址，没猜中返回 null。
     *
     * 用户填的可能是完整的 `/chat/completions` 或 `/messages` 地址，先去掉再补。
     */
    suspend fun tryAppendV1(config: LlmConfig): String? {
        val base = config.baseUrl.trim().trimEnd('/')
            .removeSuffix("/chat/completions")
            .removeSuffix("/messages")
            .trimEnd('/')
        // 空地址用的是官方默认，本来就带 /v1。
        if (base.isBlank() || VersionSegment.containsMatchIn(base)) return null

        val candidate = "$base/v1"
        return when (val probe = config.copy(baseUrl = candidate).createProvider().listModels()) {
            is ModelListResult.Ok -> candidate
            // 接口在，只是 Key 有问题：路径是对的。
            is ModelListResult.Failed -> candidate.takeIf { probe.error is LlmError.Unauthorized }
        }
    }
}
