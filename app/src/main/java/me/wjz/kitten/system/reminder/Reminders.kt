package me.wjz.kitten.system.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.wjz.kitten.MainActivity
import me.wjz.kitten.data.session.Sessions
import me.wjz.kitten.llm.ChatMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 排、取消 Kitten 自己的提醒。到点由 [ReminderReceiver] 接住，交给 [ReminderRingService] 一直响。
 *
 * ## 为什么用 setAlarmClock
 *
 * 精确到点有好几种排法，setAlarmClock 是最硬的那个：
 * - 省电模式（Doze）、厂商的后台管控都管不住它，到点必响——这是闹钟该有的样子；
 * - 状态栏会出现闹钟图标，下拉锁屏也看得到"下一个闹钟"，用户知道有东西排着；
 * - 到点后系统放行它从后台起前台服务，响铃的服务才起得来。
 *
 * 代价是要"精确闹钟"权限（SCHEDULE_EXACT_ALARM），Android 14 起新装的应用默认没有。增强模式连上时
 * Kitten 自己补上（见 privilege 模块的 SelfGrants），没有的话 [schedule] 返回原因，让模型告诉用户去哪开。
 *
 * ## 重启以后
 *
 * 系统里排好的闹钟重启就没了，所以每条提醒自己也存一份（[ReminderStore]），开机时照着重排
 * （[rescheduleAll]，在 BootCompletedReceiver 里调）。关机期间错过的，开机后马上补响：晚响总比不响好。
 *
 * ## 响了也写进对话
 *
 * 只弹一个通知的话，通知一划掉就什么都没留下。所以响的时候还往排它的那段对话里写一条（[announce]）：回头翻
 * 对话看得到它提醒过什么，接着聊"刚才那个笑话再讲一个"模型也知道上下文。
 */
object Reminders {

    private const val TAG = "KittenReminder"

    /** 写对话用：响铃的服务随时会停（用户一点"知道了"），写到一半不能跟着它被取消。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 把响了的这条写进排它的那段对话（[Reminder.sessionId]），作为 Kitten 说的一句话。那段对话已经删了、或者
     * 不知道是哪段，就另开一段，标题是提醒的标题。
     *
     * 写的是固定的一句，不现找模型来说：到点时可能没网、模型可能没配好，提醒本身不能因为这个落空。
     */
    fun announce(context: Context, reminder: Reminder) {
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val sessions = Sessions.get(app)
                val sessionId = reminder.sessionId?.takeIf { sessions.load(it) != null }
                    ?: sessions.create("提醒：${reminder.title}").id
                sessions.appendMessage(sessionId, ChatMessage.Assistant(text = announcement(reminder)))
            }.onFailure {
                // 只记出了什么错，不记提醒的内容：那是用户的事。
                Log.w(TAG, "提醒写不进对话", it)
            }
        }
    }

    /** "⏰ 22:06 到点啦：**程序员笑话**"，有内容的话下面接着完整内容。 */
    private fun announcement(reminder: Reminder): String = buildString {
        val time = Instant.ofEpochMilli(reminder.triggerAtMillis).atZone(ZoneId.systemDefault())
        append("⏰ ").append(time.format(ClockFormat)).append(" 到点啦：**").append(reminder.title).append("**")
        if (reminder.note.isNotBlank()) append("\n\n").append(reminder.note)
    }

    private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 排一条提醒，存下来。返回失败的原因，成功是 null。 */
    fun schedule(context: Context, reminder: Reminder): String? {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return "系统没有闹钟服务"
        if (!alarms.canScheduleExactAlarms()) {
            return "没有「闹钟和提醒」权限，定不了准点的提醒"
        }
        return try {
            alarms.setAlarmClock(
                AlarmManager.AlarmClockInfo(reminder.triggerAtMillis, showIntent(context)),
                fireIntent(context, reminder.id),
            )
            ReminderStore.put(context, reminder)
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "排提醒被系统拒了", e)
            "系统不让排准点的提醒（${e.message}）"
        }
    }

    /** 取消一条：系统里排着的撤掉，存着的删掉。 */
    fun cancel(context: Context, id: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(fireIntent(context, id))
        ReminderStore.remove(context, id)
    }

    /**
     * 开机后把存着的提醒重新排一遍。已经过了点的（关机期间错过的）排在几秒后，马上补响。
     */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        ReminderStore.all(context).forEach { reminder ->
            val due = if (reminder.triggerAtMillis <= now) reminder.copy(triggerAtMillis = now + MISSED_DELAY_MILLIS) else reminder
            schedule(context, due)?.let { Log.w(TAG, "开机重排提醒失败：$it") }
        }
    }

    /** 到点时发给 [ReminderReceiver] 的那个广播。按提醒 id 区分，取消时用同一个才撤得掉。 */
    private fun fireIntent(context: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_FIRE)
                // data 里带上 id：PendingIntent 判等不看 extras，只靠请求码的话两条提醒的 hashCode 撞了会互相覆盖。
                .setData(android.net.Uri.fromParts("kitten-reminder", id, null))
                .putExtra(ReminderReceiver.EXTRA_ID, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /**
     * 用户在锁屏、状态栏上点"下一个闹钟"时打开 Kitten。用 chatIntent：直接 new 一个 Intent 的话会把开着的
     * Kitten 重建掉（见 MainActivity.chatIntent）。
     */
    private fun showIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            MainActivity.chatIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_SHOW = 3001

    /** 关机期间错过的提醒，开机后隔多久补响：等系统忙完开机那一阵。 */
    private const val MISSED_DELAY_MILLIS = 5_000L
}
