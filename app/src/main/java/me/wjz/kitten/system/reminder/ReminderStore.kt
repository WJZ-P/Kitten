package me.wjz.kitten.system.reminder

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Kitten 自己的一条提醒：到点了一直响，直到用户点掉（见 [ReminderRingService]）。
 *
 * @param id 内部编号，排闹钟、取消都按它。
 * @param title 一个简短的标题（"交周报""讲个笑话"）：任务页卡片上、响的时候通知的标题写的都是它。
 * @param note 到点要说的完整内容，可以是空的。任务页点开详情、响的时候通知的正文里写的是它。
 * @param triggerAtMillis 什么时候响（UTC 毫秒）。
 * @param sessionId 在哪段对话里排的。到点了往那段对话里写一条（见 [Reminders.announce]）；那段对话删了、或者
 *   不知道是哪段，就另开一段。
 * @param announced 已经写进对话了：稍后再响的那一次不再写一遍。
 */
data class Reminder(
    val id: String,
    val title: String,
    val note: String,
    val triggerAtMillis: Long,
    val sessionId: String? = null,
    val announced: Boolean = false,
) {
    companion object {
        fun create(title: String, note: String, triggerAtMillis: Long, sessionId: String?) =
            Reminder(UUID.randomUUID().toString(), title, note, triggerAtMillis, sessionId)
    }
}

/**
 * 还没响的提醒。排在系统里的闹钟会因为重启、强行停止而丢，所以自己再存一份：开机后照着重排（见
 * [Reminders.rescheduleAll]），任务页"定时任务"里列的也是这一份。
 *
 * 存在 SharedPreferences 里，整份 JSON 读写：提醒顶多几十条。响过、取消了就删掉。
 */
object ReminderStore {

    private const val TAG = "KittenReminder"
    private const val PREFS = "kitten_reminders"
    private const val KEY = "reminders"

    private val state = MutableStateFlow<List<Reminder>?>(null)

    /** 全部还没响的提醒，按时间先后排。第一次用到时读出来，之后有变化推一份新的。 */
    fun observe(context: Context): StateFlow<List<Reminder>?> {
        if (state.value == null) state.value = read(context)
        return state.asStateFlow()
    }

    fun all(context: Context): List<Reminder> = state.value ?: read(context).also { state.value = it }

    fun find(context: Context, id: String): Reminder? = all(context).firstOrNull { it.id == id }

    /** 加一条，或者换掉同 id 的那条（再响一次时改时间）。 */
    fun put(context: Context, reminder: Reminder) = synchronized(this) {
        write(context, all(context).filterNot { it.id == reminder.id } + reminder)
    }

    fun remove(context: Context, id: String) = synchronized(this) {
        write(context, all(context).filterNot { it.id == id })
    }

    private fun write(context: Context, reminders: List<Reminder>) {
        val sorted = reminders.sortedBy { it.triggerAtMillis }
        val array = JSONArray()
        sorted.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("note", it.note)
                    .put("at", it.triggerAtMillis)
                    .put("session", it.sessionId ?: JSONObject.NULL)
                    .put("announced", it.announced),
            )
        }
        prefs(context).edit { putString(KEY, array.toString()) }
        state.value = sorted
    }

    private fun read(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Reminder(
                    id = id,
                    title = json.optString("title"),
                    note = json.optString("note"),
                    triggerAtMillis = json.optLong("at"),
                    sessionId = json.optString("session").takeIf { it.isNotBlank() && !json.isNull("session") },
                    announced = json.optBoolean("announced"),
                )
            }.sortedBy { it.triggerAtMillis }
        }.getOrElse {
            Log.w(TAG, "提醒读不出来，当作没有", it)
            emptyList()
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
