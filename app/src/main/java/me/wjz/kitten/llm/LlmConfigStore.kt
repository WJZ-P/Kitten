package me.wjz.kitten.llm

import android.content.Context
import androidx.core.content.edit

/**
 * 模型配置的持久化。
 *
 * ## 关于 API Key 的存放
 *
 * 存在应用私有目录的 SharedPreferences 里。同设备上的其它应用读不到（沙箱隔离），
 * 但 **root 过的设备、或者通过备份导出，是能拿到明文的**。
 *
 * 没有上 EncryptedSharedPreferences：androidx.security 那套已经停止维护，而自己绕
 * Keystore 做一层加密，密钥同样躺在这台设备上——真到了攻击者能读应用私有目录的地步，
 * 加密只是提高了一点门槛，不改变结论。与其给人"已加密所以很安全"的错觉，不如把
 * 实情写清楚，让用户自己判断要不要在这台设备上放高权限的 Key。
 *
 * 这件事在 README 和设置页里都该讲明白，而不是只留在代码注释里。
 */
object LlmConfigStore {

    private const val PREFS = "kitten_llm"
    private const val KEY_FORMAT = "format"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_MODEL = "model"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_TEMPERATURE = "temperature"

    fun load(context: Context): LlmConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val format = prefs.getString(KEY_FORMAT, null)
            ?.let { name -> runCatching { LlmFormat.valueOf(name) }.getOrNull() }
            ?: LlmFormat.OPENAI_COMPATIBLE
        return LlmConfig(
            format = format,
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
            apiKey = prefs.getString(KEY_API_KEY, "").orEmpty(),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048),
            temperature = prefs.getFloat(KEY_TEMPERATURE, 0.2f).toDouble(),
        )
    }

    fun save(context: Context, config: LlmConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_FORMAT, config.format.name)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_MODEL, config.model)
            putInt(KEY_MAX_TOKENS, config.maxTokens)
            putFloat(KEY_TEMPERATURE, config.temperature.toFloat())
        }
    }

    /** 清掉配置，给"删除 Key"用。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
    }
}
