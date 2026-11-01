package me.wjz.kitten.system.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 提醒到点了（[Reminders] 用 setAlarmClock 排的那个广播）：把响铃交给 [ReminderRingService]。
 *
 * 广播接收器只有十来秒的命，响铃要一直响到用户点掉，得交给前台服务。setAlarmClock 到点的广播里系统
 * 允许从后台起前台服务，所以这里直接起得来。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        ReminderRingService.ring(context, id)
    }

    companion object {
        const val ACTION_FIRE = "me.wjz.kitten.reminder.action.FIRE"
        const val EXTRA_ID = "me.wjz.kitten.reminder.extra.ID"
    }
}
