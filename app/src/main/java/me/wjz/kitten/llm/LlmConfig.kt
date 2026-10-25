package me.wjz.kitten.llm

import java.util.UUID

/**
 * 模型接入的配置。
 *
 * @param format 报文格式，决定用哪个 [LlmProvider] 实现。国产模型基本都填
 *        [LlmFormat.OPENAI_COMPATIBLE]。
 * @param baseUrl API 根地址，**不带** `/chat/completions` 这类路径后缀，由 provider 补。
 *        留空则用各自的官方默认值。
 * @param apiKey 用户自己的密钥。
 * @param model 模型名，如 `deepseek-chat`、`claude-sonnet-5-5`、`gpt-4o`。
 * @param maxTokens 单次回复的上限。Agent 的回复通常很短（一句话 + 一次工具调用），
 *        给太大没意义；但也不能太小，否则工具参数会被截断成不合法的 JSON。
 * @param temperature 调低一些，Agent 要的是稳定执行而不是创意。只有 OpenAI 格式会发：Anthropic
 *        格式的新版 API 不让传温度，见 [AnthropicProvider]。
 */
data class LlmConfig(
    val format: LlmFormat = LlmFormat.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val maxTokens: Int = 2048,
    val temperature: Double = 0.2,
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * 一家配置好的模型服务。可以配好几家，随时切换用哪一家（见 [LlmConfigStore]）；设置页上一家
 * 一张卡片。
 *
 * 不单独存名字：从预设建的显示预设名，自定义的显示地址里的主机名，见设置页。
 *
 * @param id 内部标识，增删改按它找。
 * @param models 这家可选的模型，从服务商的 /models 接口拉下来的（见 [LlmProvider.listModels]）。
 * @param model 当前选中的模型。不一定在 [models] 里：拉不到列表的服务，用户可以手填。
 */
data class LlmProfile(
    val id: String,
    val format: LlmFormat = LlmFormat.OPENAI_COMPATIBLE,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<String> = emptyList(),
    val model: String = "",
) {
    val isConfigured: Boolean
        get() = apiKey.isNotBlank() && model.isNotBlank()

    /** 下拉条里的选项：拉到的列表，加上当前选中、但不在列表里的那个（手填的）。 */
    val modelChoices: List<String>
        get() = if (model.isNotBlank() && model !in models) listOf(model) + models else models

    fun toConfig(maxTokens: Int, temperature: Double): LlmConfig =
        LlmConfig(format, baseUrl, apiKey, model, maxTokens, temperature)

    companion object {
        fun create(): LlmProfile = LlmProfile(id = UUID.randomUUID().toString())
    }
}

/** 报文格式。 */
enum class LlmFormat {
    /** OpenAI 官方。 */
    OPENAI,

    /** 兼容 OpenAI 协议的第三方：DeepSeek、通义、智谱、Moonshot、本地 Ollama… */
    OPENAI_COMPATIBLE,

    /** Anthropic Claude。 */
    ANTHROPIC,
}

/** 按配置里的格式造出对应的实现。 */
fun LlmConfig.createProvider(): LlmProvider = when (format) {
    LlmFormat.OPENAI, LlmFormat.OPENAI_COMPATIBLE -> OpenAiProvider(this)
    LlmFormat.ANTHROPIC -> AnthropicProvider(this)
}

/**
 * 常见服务商的填写模板。
 *
 * 只是给用户省掉查文档的功夫，**不是白名单**——任何兼容 OpenAI 协议的服务（包括
 * 自建的 Ollama、vLLM）填上 baseUrl 就能用。
 *
 * 这里故意不内置任何 API Key，Kitten 不代持用户的凭据。
 */
data class LlmPreset(
    val label: String,
    val format: LlmFormat,
    val baseUrl: String,
    /** 举一个常用模型名做默认值，用户可以改成同一家的任意型号。 */
    val sampleModel: String,
) {
    companion object {
        val ALL = listOf(
            LlmPreset("DeepSeek", LlmFormat.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1", "deepseek-chat"),
            LlmPreset("通义千问", LlmFormat.OPENAI_COMPATIBLE, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
            LlmPreset("智谱 GLM", LlmFormat.OPENAI_COMPATIBLE, "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus"),
            LlmPreset("Moonshot", LlmFormat.OPENAI_COMPATIBLE, "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            LlmPreset("OpenAI", LlmFormat.OPENAI, "https://api.openai.com/v1", "gpt-4o"),
            LlmPreset("Anthropic", LlmFormat.ANTHROPIC, "https://api.anthropic.com/v1", "claude-sonnet-5-5"),
            LlmPreset("本地 Ollama", LlmFormat.OPENAI_COMPATIBLE, "http://127.0.0.1:11434/v1", "qwen2.5"),
        )
    }
}
