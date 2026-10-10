package me.wjz.kitten.privilege.client

import android.content.Context
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.Starter
import java.io.IOException

/**
 * 无线调试的全流程编排（全程手机闭环，不接电脑）：
 *
 * 首次：发现配对端口 → 配对 → 连接 → 拉起特权进程
 * 重启后：连接 → 拉起特权进程（密钥已存，不用再配对）
 *
 * 这里只负责 ADB 这一侧的进度；最终"是否就绪"由 [PrivilegeManager.state] 给出
 * （特权进程交回 binder 后才算就绪）。
 */
class WirelessDebugController(context: Context) {

    private val appContext = context.applicationContext

    sealed interface Step {
        data object Idle : Step
        data object DiscoveringPairingPort : Step
        data object Pairing : Step
        data object Connecting : Step
        data object Launching : Step
        /** ADB 侧已完成，正在等特权进程交回 binder。 */
        data object Launched : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** 首次：输入配对码，完成配对并启动特权进程。启动前请保持系统"配对对话框"打开。 */
    suspend fun pairAndLaunch(pairingCode: String) {
        runFlow {
            _step.value = Step.DiscoveringPairingPort
            val port = AdbMdnsHelper.discoverPort(
                appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING, DISCOVER_TIMEOUT_MS,
            ) ?: throw IOException("未发现配对服务，请确认系统的配对对话框处于打开状态")

            _step.value = Step.Pairing
            val manager = KittenAdbManager.getInstance(appContext)
            val host = AndroidUtils.getHostIpAddress(appContext)
                ?: throw IOException("拿不到本机 IP")
            val paired = withContext(Dispatchers.IO) { manager.pair(host, port, pairingCode) }
            if (!paired) throw IOException("配对失败，请检查配对码")

            connectAndLaunchInternal()
        }
    }

    /** 重启后：已配对过，直接连接并启动。 */
    suspend fun connectAndLaunch() {
        runFlow { connectAndLaunchInternal() }
    }

    private suspend fun connectAndLaunchInternal() {
        _step.value = Step.Connecting
        val manager = KittenAdbManager.getInstance(appContext)
        val connected = withContext(Dispatchers.IO) {
            manager.autoConnect(appContext, CONNECT_TIMEOUT_MS)
        }
        if (!connected) throw IOException("连接失败，请确认无线调试已开启")

        _step.value = Step.Launching
        val apkPath = appContext.applicationInfo.sourceDir
        val token = PrivilegeManager.newLaunchToken()
        val command = Starter.buildCommand(apkPath, token)
        AdbBackend(manager).launch(command)

        _step.value = Step.Launched
    }

    private suspend inline fun runFlow(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "无线调试流程失败", t)
            _step.value = Step.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    private companion object {
        const val TAG = "KittenWirelessAdb"
        const val DISCOVER_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
