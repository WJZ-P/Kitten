package me.wjz.kitten.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.wjz.kitten.permission.SelfConfigurator
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.client.WirelessDebugController

/**
 * 开机后自动把特权链路重建起来，全程零交互。
 *
 * 能这么做的前提是之前已经拿到 WRITE_SECURE_SETTINGS（见 SelfConfigurator）。
 * 这个授权扛重启，所以：
 *
 *   1. 自己把无线调试打开——重启会把它关掉，不开 mDNS 就发现不了连接端口
 *   2. 顺便把无障碍和通知使用权也打开，重装或被系统关掉时能自愈
 *   3. 用本地存的密钥连上 adbd（配对状态是持久的，不需要再输配对码）
 *   4. 重新拉起特权进程
 *
 * 没有授权就什么都不做：这时连无线调试都打不开，做什么都是白费。
 *
 * 关于触发时机：只在解锁后的 BOOT_COMPLETED 跑，原因见 AndroidManifest 的注释。
 * 另外在 MIUI / HyperOS 上，未放行自启动的应用收不到开机广播——实测日志为
 * `BroadcastQueueInjector: ... process is not permitted to auto start`。
 * 我们能收到是因为无障碍服务已经把进程拉起来了（系统拉起的服务不受自启动管控），
 * 广播这才搭上车，代价是比开机晚了几十秒。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!SelfConfigurator.hasPermission(context)) {
            Log.i(TAG, "没有 WRITE_SECURE_SETTINGS，开机不做自动恢复")
            return
        }

        val appContext = context.applicationContext
        // onReceive 返回后进程随时可能被回收，而下面是网络操作。
        // goAsync() 把广播的生命周期延长到 finish() 为止。
        val pending = goAsync()
        val startedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "开机自动恢复开始，action=${intent.action}")

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                SelfConfigurator.applyAll(appContext)
                if (!SelfConfigurator.enableWirelessDebugging(appContext)) {
                    // 无线调试没打开，后面 mDNS 必然发现不了端口，没必要再往下走。
                    Log.w(TAG, "无线调试未能打开，放弃本次自动恢复")
                    return@launch
                }
                WirelessDebugController.getInstance(appContext).autoConnect()
            } catch (t: Throwable) {
                Log.w(TAG, "开机自动恢复出错", t)
            } finally {
                val cost = SystemClock.elapsedRealtime() - startedAt
                Log.i(
                    TAG,
                    "开机自动恢复结束，耗时 ${cost}ms，特权状态=${PrivilegeManager.state.value}",
                )
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "KittenBoot"
    }
}
