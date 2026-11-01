package me.wjz.kitten.system

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import java.time.LocalDate
import java.time.ZoneId

/** 一个应用在一段时间里用了多久。 */
data class AppUsageEntry(
    val packageName: String,
    val label: String,
    val foregroundMillis: Long,
    val lastUsedMillis: Long,
)

/**
 * 应用使用记录（[UsageStatsManager]）：一段时间里每个应用在前台待了多久、最近一次是什么时候用的。
 *
 * 要"使用情况访问权限"（PACKAGE_USAGE_STATS），平时得用户自己去系统设置里开；增强模式连上时 Kitten
 * 自己补上（见 privilege 模块的 SelfGrants）。没权限时系统不报错，只是查回来是空的，所以先查权限再查。
 */
object AppUsage {

    /**
     * 从 [days] 天前的零点到现在，按用的时长从长到短，最多 [limit] 个。没用过的、Kitten 自己不算。
     * 返回 null 表示没有权限。
     */
    fun top(context: Context, days: Int, limit: Int): List<AppUsageEntry>? {
        if (!SystemPermissions.hasUsageAccess(context)) return null
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val zone = ZoneId.systemDefault()
        val begin = LocalDate.now(zone).minusDays((days - 1).coerceAtLeast(0).toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = System.currentTimeMillis()
        val packages = context.packageManager
        return usage.queryAndAggregateUsageStats(begin, end).values
            .filter { it.totalTimeInForeground > 0 && it.packageName != context.packageName }
            .sortedByDescending { it.totalTimeInForeground }
            .take(limit)
            .map { stats ->
                AppUsageEntry(
                    packageName = stats.packageName,
                    label = labelOf(packages, stats.packageName),
                    foregroundMillis = stats.totalTimeInForeground,
                    lastUsedMillis = stats.lastTimeUsed,
                )
            }
    }

    private fun labelOf(packages: PackageManager, packageName: String): String =
        runCatching { packages.getApplicationLabel(packages.getApplicationInfo(packageName, 0)).toString() }
            .getOrDefault(packageName)
}
