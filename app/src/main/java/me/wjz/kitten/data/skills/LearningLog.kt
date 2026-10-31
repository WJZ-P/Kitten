package me.wjz.kitten.data.skills

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * 哪件事复盘后学到了哪个技能：对话里那张任务卡片下面"已学习到新知识！"那一行靠它，翻历史会话、
 * 重开 App 也还在。
 *
 * 按 anchor 记（会话 id + 那一轮最后一个工具调用的 id，见 SkillReflector）：消息在领域模型里没有 id，
 * 按位置记的话，用户删一条消息后面的位置就全错开了；工具调用的 id 是服务端给的，跟着消息一起落库，不会变。
 *
 * 只记学到了的：没学到、复盘失败的那一行本来就不显示，不用记。复盘中的状态也不记：进程没了复盘就断了，
 * 记着只会让那一行永远停在"在复盘"。
 *
 * 存在 SharedPreferences 里，一条几十个字，最多留 [MAX_ENTRIES] 条。
 */
object LearningLog {

    private const val TAG = "KittenSkills"
    private const val PREFS = "kitten_learning_log"
    private const val KEY_ENTRIES = "entries"

    /**
     * 最多留几条，再多就把最早的扔掉。几百个字节一条，留多了也不占地方；可太老的那些对应的会话多半
     * 已经删了，留着也没人看。
     */
    private const val MAX_ENTRIES = 200

    /** 学到了什么：技能的 id（点那一行打开它）和学到时的名字（那一行上写的）。 */
    data class Entry(val skillId: String, val skillName: String)

    /** 全部记录，anchor → 学到了什么。 */
    fun load(context: Context): Map<String, Entry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyMap()
        return runCatching {
            val array = JSONArray(raw)
            buildMap {
                for (i in 0 until array.length()) {
                    val json = array.optJSONObject(i) ?: continue
                    val anchor = json.optString("anchor").takeIf { it.isNotBlank() } ?: continue
                    val id = json.optString("id").takeIf { it.isNotBlank() } ?: continue
                    put(anchor, Entry(skillId = id, skillName = json.optString("name")))
                }
            }
        }.getOrElse {
            // 整份坏了（不该发生）：当作没有，那几行不显示而已。
            Log.w(TAG, "学习记录读不出来，当作没有", it)
            emptyMap()
        }
    }

    /** 记一条。同一个 anchor 记过的覆盖掉，放到最后。 */
    fun record(context: Context, anchor: String, entry: Entry) = synchronized(this) {
        val next = (load(context) - anchor).toList() + (anchor to entry)
        val array = JSONArray()
        next.takeLast(MAX_ENTRIES).forEach { (key, value) ->
            array.put(
                JSONObject()
                    .put("anchor", key)
                    .put("id", value.skillId)
                    .put("name", value.skillName),
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
