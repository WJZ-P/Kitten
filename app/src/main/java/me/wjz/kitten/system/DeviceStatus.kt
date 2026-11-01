package me.wjz.kitten.system

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 手机此刻的状态，一份快照：现在几点、电量、网络、存储、内存、响铃模式。全都不要权限。
 *
 * 现在几点也放在这里：系统提示词里不放时间（一变缓存就失效，见 SystemPrompt），模型要定闹钟、排提醒、
 * 加日程之前，先从这里拿准"今天几号、星期几"。
 *
 * 不读连的是哪个 Wi-Fi：那要定位权限，对办事没什么用。
 */
object DeviceStatus {

    /** 整理成给模型看的几行字。 */
    fun describe(context: Context): String = buildString {
        val now = ZonedDateTime.now()
        appendLine("现在：${now.format(NowFormat)}（${now.zone.id}）")
        battery(context)?.let { appendLine("电量：$it") }
        appendLine("网络：${network(context)}")
        appendLine("存储：${storage()}")
        memory(context)?.let { appendLine("内存：$it") }
        appendLine("响铃：${ringer(context)}")
        appendLine("机型：${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}")
    }.trimEnd()

    private fun battery(context: Context): String? {
        // 粘性广播：注册时直接拿到最近的那一份，不用真的等广播来。
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return null
        val percent = level * 100 / scale
        val charging = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "，正在充电（插头）"
            BatteryManager.BATTERY_PLUGGED_USB -> "，正在充电（USB）"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "，正在无线充电"
            else -> ""
        }
        val saver = if (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true) "，省电模式开着" else ""
        return "$percent%$charging$saver"
    }

    private fun network(context: Context): String {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "不知道"
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "没有联网"
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动数据"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有线网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "联网了"
        }
        val online = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return type + (if (online) "" else "（上不了网）") + (if (metered && online) "，按流量计费" else "")
    }

    private fun storage(): String {
        val stat = StatFs(Environment.getDataDirectory().path)
        return "还剩 ${gigabytes(stat.availableBytes)}，一共 ${gigabytes(stat.totalBytes)}"
    }

    private fun memory(context: Context): String? {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        return "可用 ${gigabytes(info.availMem)}，一共 ${gigabytes(info.totalMem)}" + if (info.lowMemory) "，内存很紧张" else ""
    }

    private fun ringer(context: Context): String {
        val ringerMode = when (context.getSystemService(AudioManager::class.java)?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "静音"
            AudioManager.RINGER_MODE_VIBRATE -> "震动"
            else -> "正常响铃"
        }
        val dnd = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        val dndText = if (dnd != null && dnd != NotificationManager.INTERRUPTION_FILTER_ALL &&
            dnd != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        ) {
            "，勿扰模式开着"
        } else {
            ""
        }
        return ringerMode + dndText
    }

    private fun gigabytes(bytes: Long): String = String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)

    /** "2026年11月1日 星期六 14:03"：日期、星期、时间都给，模型算"明天""下周五"时不用自己推星期几。 */
    private val NowFormat = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
}
