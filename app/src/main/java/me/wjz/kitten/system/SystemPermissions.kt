package me.wjz.kitten.system

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import me.wjz.kitten.privilege.SelfGrants

/**
 * 「系统能力」各项要的权限现在有没有，没有的话怎么跟用户说。
 *
 * 这些权限平时由增强模式连上 ADB 时自己补上（privilege 模块的 SelfGrants，它和这里查的是同一套），
 * 用户在系统设置里关掉了、或者还没开增强模式，就会缺。缺了不硬闯：工具把 [hint] 那句话交给模型，让它
 * 照实告诉用户去哪开。
 */
object SystemPermissions {

    /** 能不能排精确到点的提醒。 */
    fun canScheduleExactAlarms(context: Context): Boolean = SelfGrants.canScheduleExactAlarms(context)

    /** 有没有"使用情况访问权限"。 */
    fun hasUsageAccess(context: Context): Boolean = SelfGrants.hasUsageAccess(context)

    /** 能不能读写日历。 */
    fun hasCalendar(context: Context): Boolean =
        granted(context, Manifest.permission.READ_CALENDAR) && granted(context, Manifest.permission.WRITE_CALENDAR)

    /**
     * 能不能发通知。提醒响的时候要靠通知上的"知道了"让它停下来：没有通知的话铃声照样响，用户却找不到
     * 地方关（只能等它自己停），所以排提醒前要先看这一项。
     */
    fun canNotify(context: Context): Boolean =
        granted(context, Manifest.permission.POST_NOTIFICATIONS) &&
            context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /**
     * 缺了 [what]（"闹钟和提醒""日历""使用情况访问""通知"这种系统设置里的叫法）时给模型的话：开增强模式
     * Kitten 会自己补上，或者用户自己去系统设置里开。
     */
    fun hint(what: String): String =
        "ERROR no_permission: Kitten 还没有「$what」权限。告诉用户：打开 设置 → 增强模式，Kitten 会自己把它补上；" +
            "或者去系统设置 → 应用 → Kitten 里手动打开「$what」。"

    private fun granted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
