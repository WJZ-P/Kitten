package me.wjz.kitten.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock

/**
 * 在系统时钟应用里定闹钟、开倒计时（[AlarmClock]）。
 *
 * 和 Kitten 自己的提醒（[me.wjz.kitten.system.reminder.Reminders]）不是一回事：这里是交给系统时钟
 * 去响，用户在时钟应用里看得到、关得掉，和自己手动定的一模一样；提醒是 Kitten 自己到点响，带着一句
 * 要提醒的话，列在任务页里。用户说"定个闹钟"走这里，说"提醒我……"走提醒。
 *
 * 要 `SET_ALARM`，普通权限，清单里声明了就有。带了 [AlarmClock.EXTRA_SKIP_UI]，大多数时钟应用直接
 * 定好、不弹界面；有的厂商的时钟不认这个参数，会把设置界面打开让用户确认一下。
 */
object Alarms {

    /**
     * 定一个闹钟。
     *
     * @param days 每周哪几天响（[java.util.Calendar.MONDAY] 这种），空的就是只响一次。
     * @return 失败的原因，成功是 null。
     */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?, days: List<Int>): String? {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply {
                label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
            }
        return start(context, intent)
    }

    /** 开一个倒计时。 */
    fun setTimer(context: Context, seconds: Int, label: String?): String? {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .apply { label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
        return start(context, intent)
    }

    /** 打开时钟应用的闹钟列表，给用户看看都定了哪些。 */
    fun showAlarms(context: Context): String? = start(context, Intent(AlarmClock.ACTION_SHOW_ALARMS))

    private fun start(context: Context, intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "手机上没有能接这个请求的时钟应用"
    } catch (e: SecurityException) {
        "系统不让定闹钟（${e.message}）"
    }
}
