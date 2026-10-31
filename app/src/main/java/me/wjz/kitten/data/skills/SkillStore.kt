package me.wjz.kitten.data.skills

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
 * 技能的开关和用户自己加的技能。
 *
 * 存在 SharedPreferences 里：自带技能一个开关一条（没存过就是开着），用户技能整份存成一段 JSON。
 * 技能顶多几十个，整份读写足够快，不值得为它建表。
 *
 * 改了之后对话页不用通知：它每轮开始时才读一次（[enabled]），下一轮自然用上。设置页盯着 [revision] 重读。
 */
object SkillStore {

    private const val TAG = "KittenSkills"
    private const val PREFS = "kitten_skills"
    private const val KEY_USER_SKILLS = "user_skills"

    private val _revision = MutableStateFlow(0)

    /** 每改一次就加一，设置页盯着它重读。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** 自带技能开没开。没存过就是开着：新装的 App 什么都能干，用户想关再关。 */
    fun isEnabled(context: Context, skill: BuiltinSkill): Boolean =
        prefs(context).getBoolean(builtinKey(skill), true)

    fun setEnabled(context: Context, skill: BuiltinSkill, enabled: Boolean) {
        prefs(context).edit { putBoolean(builtinKey(skill), enabled) }
        _revision.update { it + 1 }
    }

    /** 用户加的全部技能（开着的、关着的都有），按加的先后。 */
    fun userSkills(context: Context): List<UserSkill> {
        val raw = prefs(context).getString(KEY_USER_SKILLS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { decode(array.optJSONObject(it)) }
        }.getOrElse {
            // 整份坏了（不该发生）：当作没有，别让设置页和对话都跟着崩。
            Log.w(TAG, "用户技能读不出来，当作没有", it)
            emptyList()
        }
    }

    /** 按名字找一个用户技能，不分大小写、不管首尾空格（名字不能重复就是按这个规矩比的）。 */
    fun findByName(context: Context, name: String): UserSkill? =
        userSkills(context).firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }

    /** 存一个用户技能：[skill] 的 id 已经有了就替换（编辑），没有就加在最后（新加）。 */
    fun saveUserSkill(context: Context, skill: UserSkill) {
        val skills = userSkills(context)
        val next = if (skills.any { it.id == skill.id }) {
            skills.map { if (it.id == skill.id) skill else it }
        } else {
            skills + skill
        }
        writeUserSkills(context, next)
    }

    fun setUserSkillEnabled(context: Context, id: String, enabled: Boolean) {
        writeUserSkills(context, userSkills(context).map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun deleteUserSkill(context: Context, id: String) {
        writeUserSkills(context, userSkills(context).filterNot { it.id == id })
    }

    /** 这一轮开着的技能，给对话用：每轮开始时读一次。 */
    fun enabled(context: Context): SkillSet = SkillSet(
        builtins = BuiltinSkill.entries.filterTo(mutableSetOf()) { isEnabled(context, it) },
        userSkills = userSkills(context).filter { it.enabled },
    )

    private fun writeUserSkills(context: Context, skills: List<UserSkill>) {
        val array = JSONArray()
        skills.forEach { array.put(encode(it)) }
        prefs(context).edit { putString(KEY_USER_SKILLS, array.toString()) }
        _revision.update { it + 1 }
    }

    private fun encode(skill: UserSkill): JSONObject = JSONObject()
        .put("id", skill.id)
        .put("name", skill.name)
        .put("when", skill.whenToUse)
        .put("body", skill.instructions)
        .put("on", skill.enabled)

    /** 解析不了、或者没有名字的那一条跳过。 */
    private fun decode(json: JSONObject?): UserSkill? {
        json ?: return null
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
        return UserSkill(
            id = id,
            name = name,
            whenToUse = json.optString("when"),
            instructions = json.optString("body"),
            enabled = json.optBoolean("on", true),
        )
    }

    private fun builtinKey(skill: BuiltinSkill) = "builtin_${skill.id}"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
