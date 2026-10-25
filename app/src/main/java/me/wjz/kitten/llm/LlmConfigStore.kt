package me.wjz.kitten.llm

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配好的所有模型服务，以及当前用的是哪一家。
 *
 * @param activeId 当前用的那一家的 [LlmProfile.id]。对不上任何一家（比如一家都没配）时为 null。
 */
data class LlmProfiles(
    val profiles: List<LlmProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: LlmProfile?
        get() = profiles.firstOrNull { it.id == activeId }
}

/**
 * 模型配置的持久化。
 *
 * 可以配好几家服务（[LlmProfile]），记着当前用的是哪一家。整份列表序列化成 JSON 存成一个
 * 字段：家数很少，每次整份读写最简单，也不会出现"列表改了一半"的中间态。
 *
 * 发请求的地方只关心"现在用哪家、哪个模型"，读 [load] 拿到一份 [LlmConfig] 就行，不用知道
 * 有好几家。
 *
 * ## 从单个配置迁移
 *
 * 以前只能配一家，字段是平铺着存的（format、base_url、api_key、model）。第一次读到新格式
 * 不存在、旧字段有内容时，把它迁成第一家并设为当前，再删掉旧字段，免得下次重复迁移。
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
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_MAX_TOKENS = "max_tokens"
    private const val KEY_TEMPERATURE = "temperature"

    // 以前只能配一家时的平铺字段，只在迁移时读。
    private const val LEGACY_FORMAT = "format"
    private const val LEGACY_BASE_URL = "base_url"
    private const val LEGACY_API_KEY = "api_key"
    private const val LEGACY_MODEL = "model"

    /** 发请求用的那一份：当前那一家 + 它选中的模型。一家都没配时是空配置（isConfigured 为 false）。 */
    fun load(context: Context): LlmConfig {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val maxTokens = prefs.getInt(KEY_MAX_TOKENS, 2048)
        val temperature = prefs.getFloat(KEY_TEMPERATURE, 0.2f).toDouble()
        val active = loadProfiles(context).active
            ?: return LlmConfig(maxTokens = maxTokens, temperature = temperature)
        return active.toConfig(maxTokens, temperature)
    }

    fun loadProfiles(context: Context): LlmProfiles {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null)
            ?: return migrateLegacy(context)
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: JSONArray()
        val profiles = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toProfile() }
        return LlmProfiles(profiles, prefs.getString(KEY_ACTIVE, null))
    }

    fun saveProfiles(context: Context, profiles: LlmProfiles) {
        val array = JSONArray().apply { profiles.profiles.forEach { put(it.toJson()) } }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_PROFILES, array.toString())
            putString(KEY_ACTIVE, profiles.activeId)
        }
        _revision.update { it + 1 }
    }

    /**
     * 改当前那一家的地址。给 [EndpointRepair] 补上 /v1 之后用：对话页发现地址少了 /v1、补上
     * 能通，就直接改掉用户的设置，下次不用再猜。
     */
    fun updateActiveBaseUrl(context: Context, baseUrl: String) {
        val current = loadProfiles(context)
        val activeId = current.activeId ?: return
        saveProfiles(
            context,
            current.copy(
                profiles = current.profiles.map { if (it.id == activeId) it.copy(baseUrl = baseUrl) else it },
            ),
        )
    }

    private val _revision = MutableStateFlow(0)

    /**
     * 配置每存一次就加一。设置页一直活着（三页常驻），别处改了配置（比如对话页自动补了 /v1）
     * 它不知道，手里那份就过时了；看着这个数，变了就重读。
     */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 清掉所有配置，给"删除 Key"用。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { clear() }
        _revision.update { it + 1 }
    }

    private fun migrateLegacy(context: Context): LlmProfiles {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(LEGACY_API_KEY, "").orEmpty()
        val model = prefs.getString(LEGACY_MODEL, "").orEmpty()
        val baseUrl = prefs.getString(LEGACY_BASE_URL, "").orEmpty()
        if (apiKey.isBlank() && model.isBlank() && baseUrl.isBlank()) return LlmProfiles()

        val format = prefs.getString(LEGACY_FORMAT, null)
            ?.let { name -> runCatching { LlmFormat.valueOf(name) }.getOrNull() }
            ?: LlmFormat.OPENAI_COMPATIBLE
        val profile = LlmProfile.create().copy(
            format = format,
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
        )
        val migrated = LlmProfiles(listOf(profile), profile.id)
        saveProfiles(context, migrated)
        prefs.edit {
            remove(LEGACY_FORMAT)
            remove(LEGACY_BASE_URL)
            remove(LEGACY_API_KEY)
            remove(LEGACY_MODEL)
        }
        return migrated
    }

    private fun LlmProfile.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("format", format.name)
        put("baseUrl", baseUrl)
        put("apiKey", apiKey)
        put("model", model)
        put("models", JSONArray(models))
    }

    /** 解析不了的那一家直接跳过，不让一条坏数据把整个列表拖垮。 */
    private fun JSONObject.toProfile(): LlmProfile? = runCatching {
        val modelsArray = optJSONArray("models") ?: JSONArray()
        LlmProfile(
            id = getString("id"),
            format = runCatching { LlmFormat.valueOf(getString("format")) }
                .getOrDefault(LlmFormat.OPENAI_COMPATIBLE),
            baseUrl = optString("baseUrl"),
            apiKey = optString("apiKey"),
            models = (0 until modelsArray.length()).map { modelsArray.getString(it) },
            model = optString("model"),
        )
    }.getOrNull()
}
