package me.wjz.kitten.system.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import me.wjz.kitten.MainActivity

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
 */
object Reminders {

    private const val TAG = "KittenReminder"

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
