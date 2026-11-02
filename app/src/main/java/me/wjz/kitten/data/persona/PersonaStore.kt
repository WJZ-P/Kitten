package me.wjz.kitten.data.persona

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * 人设：现在用哪个、自带的改成了什么样、用户自己捏了哪些。
 *
 * 存在 SharedPreferences 里，整份 JSON 读写：人设顶多二十来个。
 *
 * ## 自带的只存改过的样子
 *
 * 自带的人设（[PersonaPresets]）原样不存，用户改过才存一份改过的。以后调了自带人设的写法，没改过的用户自动
 * 用上新的，不会被一份旧的副本钉住；改回和原样一字不差，也当没改过，删掉那份。
 *
 * 改了之后对话页不用通知：它每轮开始时才读一次（见 SystemPrompt.load），下一句话自然用上。设置页盯着
 * [revision] 重读。
 */
object PersonaStore {

    private const val TAG = "KittenPersona"
    private const val PREFS = "kitten_persona"
    private const val KEY_ACTIVE = "active"
    private const val KEY_USER = "user_personas"
    private const val KEY_EDITED = "edited_presets"

    private val _revision = MutableStateFlow(0)

    /** 每改一次就加一，设置页盯着它重读。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 全部人设：自带的在前（改过就是改过的样子），自己捏的在后，按捏的先后。 */
    fun all(context: Context): List<Persona> {
        val edited = editedPresets(context)
        return PersonaPresets.all.map { edited[it.id] ?: it } + userPersonas(context)
    }

    /** 现在用的那个的 id。没选过就是默认的猫娘。 */
    fun activeId(context: Context): String =
        prefs(context).getString(KEY_ACTIVE, null) ?: PersonaPresets.KITTEN_ID

    /** 现在用的人设。选着的那个已经被删了，就回到第一个自带的。 */
    fun active(context: Context): Persona {
        val all = all(context)
        val id = activeId(context)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setActive(context: Context, id: String) {
        prefs(context).edit { putString(KEY_ACTIVE, id) }
        _revision.update { it + 1 }
    }

    /**
     * 存一个人设。自带的存成"改过的样子"，和原样一字不差就删掉那份；自己捏的有就替换、没有就加在最后。
     */
    fun save(context: Context, persona: Persona) = synchronized(this) {
        val original = PersonaPresets.find(persona.id)
        if (original != null) {
            val edited = editedPresets(context).toMutableMap()
            if (persona.name == original.name && persona.content == original.content) {
                edited.remove(persona.id)
            } else {
                edited[persona.id] = persona.copy(builtin = true)
            }
            writeEdited(context, edited)
        } else {
            val list = userPersonas(context)
            val next = if (list.any { it.id == persona.id }) {
                list.map { if (it.id == persona.id) persona else it }
            } else {
                list + persona
            }
            writeUser(context, next)
        }
        _revision.update { it + 1 }
    }

    /** 删掉一个自己捏的；正在用它的话换回默认的猫娘。自带的删不了，传进来什么都不做。 */
    fun delete(context: Context, id: String) = synchronized(this) {
        if (PersonaPresets.find(id) != null) return@synchronized
        writeUser(context, userPersonas(context).filterNot { it.id == id })
        if (activeId(context) == id) prefs(context).edit { remove(KEY_ACTIVE) }
        _revision.update { it + 1 }
    }

    private fun userPersonas(context: Context): List<Persona> {
        val raw = prefs(context).getString(KEY_USER, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { decode(it, builtin = false) } }
        }.getOrElse {
            // 整份坏了（不该发生）：当作没有，别让设置页和对话都跟着崩。
            Log.w(TAG, "自己捏的人设读不出来，当作没有", it)
            emptyList()
        }
    }

    private fun editedPresets(context: Context): Map<String, Persona> {
        val raw = prefs(context).getString(KEY_EDITED, null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence()
                .filter { PersonaPresets.find(it) != null }
                .mapNotNull { id -> json.optJSONObject(id)?.let { decode(it, builtin = true) } }
                .associateBy { it.id }
        }.getOrElse {
            Log.w(TAG, "改过的自带人设读不出来，当作没改过", it)
            emptyMap()
        }
    }

    private fun writeUser(context: Context, personas: List<Persona>) {
        val array = JSONArray()
        personas.forEach { array.put(encode(it)) }
        prefs(context).edit { putString(KEY_USER, array.toString()) }
    }

    private fun writeEdited(context: Context, edited: Map<String, Persona>) {
        val json = JSONObject()
        edited.forEach { (id, persona) -> json.put(id, encode(persona)) }
        prefs(context).edit {
            if (edited.isEmpty()) remove(KEY_EDITED) else putString(KEY_EDITED, json.toString())
        }
    }

    private fun encode(persona: Persona): JSONObject = JSONObject()
        .put("id", persona.id)
        .put("name", persona.name)
        .put("content", persona.content)

    private fun decode(json: JSONObject, builtin: Boolean): Persona? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val content = json.optString("content").takeIf { it.isNotBlank() } ?: return null
        return Persona(id = id, name = json.optString("name").ifBlank { "没起名字" }, content = content, builtin = builtin)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
