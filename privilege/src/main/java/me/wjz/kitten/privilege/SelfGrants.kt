package me.wjz.kitten.privilege

import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

/**
 * 趁 ADB 连着的时候，给 Kitten 自己补上的授权：一条写死的命令，只授给 Kitten 自己的包名。
 *
 * ## 为什么这不违反"不给通用 exec"
 *
 * 特权侧不提供执行任意命令的通道（见 IPrivilegeService），这里也没有：命令是这个文件里拼死的，
 * 授什么、授给谁都是常量，App 里的模型碰不到它，更传不进参数。它和以前那条
 * `pm grant WRITE_SECURE_SETTINGS` 是同一种东西，只是多授了几项。
 *
 * ## 授些什么
 *
 * | 授权 | 怎么授 | 谁要用 |
 * |---|---|---|
 * | WRITE_SECURE_SETTINGS | pm grant | 开机自动恢复（见 [Starter.buildSelfGrantCommand]） |
 * | POST_NOTIFICATIONS | pm grant | 常驻通知、到点的提醒 |
 * | READ_CALENDAR / WRITE_CALENDAR | pm grant | 「系统能力」里的日程 |
 * | SCHEDULE_EXACT_ALARM | appops set | 「系统能力」里精确到点的提醒。Android 14 起新装的应用默认没有 |
 * | GET_USAGE_STATS | appops set | 「系统能力」里的应用使用记录。平时要用户自己去"使用情况访问权限"里开 |
 *
 * pm grant 只能授清单里声明过的权限，这几个都要在 App 的清单里写上。
 *
 * ## 什么时候补
 *
 * 每次连上 ADB（首次配对、开机自动恢复）都看一眼 [allGranted]，缺一项就把整条命令跑一遍。用户在系统
 * 设置里关掉了哪一项，下次连上时会被补回来。
 */
object SelfGrants {

    /** 用 pm grant 授的权限。 */
    private val permissions = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
    )

    /** 用 appops set 打开的特殊权限（appops 命令里的名字）。 */
    private val appOps = listOf(
        "SCHEDULE_EXACT_ALARM",
        "GET_USAGE_STATS",
    )

    /** 在 shell 里跑的那条命令。每一段用 `;` 串起来：哪一项失败了不影响别的。 */
    fun command(packageName: String): String = buildList {
        permissions.forEach { add("pm grant $packageName $it") }
        appOps.forEach { add("appops set $packageName $it allow") }
        // 电池白名单：尽力而为，失败也不影响别的。
        add("cmd deviceidle whitelist +$packageName")
    }.joinToString("; ")

    /** 这些授权是不是都已经有了。有一项没有就该再跑一次 [command]。 */
    fun allGranted(context: Context): Boolean =
        permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
            canScheduleExactAlarms(context) &&
            hasUsageAccess(context)

    /** 能不能排精确到点的闹钟（AlarmManager 的 setAlarmClock、setExact*）。 */
    fun canScheduleExactAlarms(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    /** 有没有"使用情况访问权限"（应用使用记录要它）。 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
