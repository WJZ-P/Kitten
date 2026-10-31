package me.wjz.kitten.ui.chat

import androidx.annotation.DrawableRes
import me.wjz.kitten.R
import me.wjz.kitten.llm.LlmPreset
import java.net.URI

/**
 * 模型是哪一家的：输入栏上切换模型的圆按钮里画谁家的图标（见 [ModelSwitcher]）。怎么认见 [modelBrandOf]。
 *
 * 图标在 res/drawable/ic_model_*.xml，转自 LobeHub Icons（MIT 协议，出处写在每个文件开头）；各家 logo
 * 是品牌方的商标，这里只用来标出"现在用的是谁家的模型"。彩色的原样画，不染色。
 */
enum class ModelBrand(@DrawableRes val icon: Int) {
    Claude(R.drawable.ic_model_claude),
    OpenAI(R.drawable.ic_model_openai),
    Gemini(R.drawable.ic_model_gemini),
    DeepSeek(R.drawable.ic_model_deepseek),
    Qwen(R.drawable.ic_model_qwen),
    Zhipu(R.drawable.ic_model_zhipu),
    Kimi(R.drawable.ic_model_kimi),
    Doubao(R.drawable.ic_model_doubao),
    MiniMax(R.drawable.ic_model_minimax),
    Hunyuan(R.drawable.ic_model_hunyuan),
    Wenxin(R.drawable.ic_model_wenxin),
    StepFun(R.drawable.ic_model_stepfun),
    Mistral(R.drawable.ic_model_mistral),
    /** Llama。图标包里没有单独的 Llama，用的是 Meta 的。 */
    Meta(R.drawable.ic_model_meta),
    Grok(R.drawable.ic_model_grok),
    Ollama(R.drawable.ic_model_ollama),
}

/**
 * 认出现在用的模型是哪一家的，认不出返回 null（按钮里画安卓小机器人）。
 *
 * 1. 这一家用的是预设（[preset] 不是 null）：就是那家服务商的图标，不管选的哪个模型。本地 Ollama 里
 *    跑的 qwen 也画 Ollama 的羊驼。
 * 2. 自定义的服务（各种中转、自建）：看模型名里有没有认得的字（claude、gpt、qwen……），见 [ModelNameRules]。
 *
 * @param preset 这一家对上的预设（[me.wjz.kitten.llm.LlmProfile.matchingPreset]），自定义的是 null。
 * @param model 选中的模型名。
 */
fun modelBrandOf(preset: LlmPreset?, model: String?): ModelBrand? {
    if (preset != null) {
        val host = runCatching { URI(preset.baseUrl).host }.getOrNull()
        PresetBrands[host]?.let { return it }
    }
    val name = model?.lowercase() ?: return null
    return ModelNameRules.firstOrNull { (_, pattern) -> pattern.containsMatchIn(name) }?.first
}

/**
 * 预设服务商的地址（host）是哪一家。预设本来就是按地址认的（matchingPreset），这里也按地址认，不按
 * 显示名：名字以后可能改。新加一个预设记得在这里补一行，没补的话就退回去看模型名。
 */
private val PresetBrands: Map<String?, ModelBrand> = mapOf(
    "api.deepseek.com" to ModelBrand.DeepSeek,
    "dashscope.aliyuncs.com" to ModelBrand.Qwen,
    "open.bigmodel.cn" to ModelBrand.Zhipu,
    "api.moonshot.cn" to ModelBrand.Kimi,
    "api.openai.com" to ModelBrand.OpenAI,
    "api.anthropic.com" to ModelBrand.Claude,
    "127.0.0.1" to ModelBrand.Ollama,
)

/**
 * 自定义服务按模型名认（名字先转成小写），从上往下第一个对上的算数，所以顺序要紧：
 *
 * - DeepSeek 排在 Qwen、Llama 前面：蒸馏版叫 deepseek-r1-distill-qwen / -llama，是 DeepSeek 出的。
 * - OpenAI 放最后："gpt" 也出现在别家量化版的名字里（qwen2.5-7b-instruct-gptq），先让别家认。o1、o3
 *   这种要单独成词，不然随便一个名字里带 o1 都会被认成它。
 * - Llama 要排除 ollama：中转常把模型叫成 ollama/xxx。
 */
private val ModelNameRules: List<Pair<ModelBrand, Regex>> = listOf(
    ModelBrand.Claude to Regex("claude"),
    ModelBrand.Gemini to Regex("gemini"),
    ModelBrand.DeepSeek to Regex("deepseek"),
    ModelBrand.Qwen to Regex("qwen|qwq|qvq"),
    ModelBrand.Zhipu to Regex("glm|zhipu"),
    ModelBrand.Kimi to Regex("kimi|moonshot"),
    ModelBrand.Doubao to Regex("doubao"),
    ModelBrand.MiniMax to Regex("minimax|abab"),
    ModelBrand.Hunyuan to Regex("hunyuan"),
    ModelBrand.Wenxin to Regex("ernie|wenxin"),
    ModelBrand.StepFun to Regex("""stepfun|(^|[^a-z])step-?\d"""),
    // Mistral 家的名字都以 stral / xtral 结尾：mistral、codestral、ministral、mixtral、pixtral……
    ModelBrand.Mistral to Regex("stral|xtral"),
    ModelBrand.Meta to Regex("(?<!o)llama"),
    ModelBrand.Grok to Regex("grok"),
    ModelBrand.OpenAI to Regex("""gpt|davinci|(^|[^a-z0-9])o\d([^a-z0-9]|$)"""),
)
