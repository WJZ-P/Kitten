package me.wjz.kitten.privilege.client

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.R
import me.wjz.kitten.privilege.Starter
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 无线调试的全流程编排（全程手机闭环，不接电脑）：
 *
 * 首次：配对 → 连接 → 拉起特权进程
 * 重启后：连接 → 拉起特权进程（密钥已存，不用再配对）
 *
 * 配对码的收集不在这里，而在 [AdbPairingService] 的通知里——配对码只在系统配对弹窗
 * 打开期间有效，用户没法切回 App 输入。所以配对端口和配对码都是从那边传进来的。
 *
 * 这里只负责 ADB 这一侧的进度；最终「是否就绪」由 [PrivilegeManager.state] 给出
 * （特权进程交回 binder 后才算就绪）。
 */
class WirelessDebugController private constructor(private val appContext: Context) {

    sealed interface Step {
        data object Idle : Step
        data object Pairing : Step
        data object Connecting : Step
        data object Launching : Step
        /** ADB 侧已完成，正在等特权进程交回 binder。 */
        data object Launched : Step
        data class Failed(val message: String) : Step
    }

    private val _step = MutableStateFlow<Step>(Step.Idle)
    val step: StateFlow<Step> = _step.asStateFlow()

    /** 控制器是进程单例，这个 scope 随进程存活，不会被调用方的生命周期带走。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val inProgress = AtomicBoolean(false)

    /**
     * 首次：用通知里收到的配对码完成配对，接着连接并启动。
     *
     * @param pairingPort 由 [AdbPairingService] 通过 mDNS 发现的配对端口，每次开弹窗都会变。
     */
    suspend fun pairAndLaunch(pairingPort: Int, pairingCode: String) {
        runFlow {
            _step.value = Step.Pairing
            // 只记端口，绝不记配对码——它是一次性密钥，不该进 Logcat。
            Log.i(TAG, "开始配对，端口=$pairingPort")
            val manager = adbManager()
            val host = AndroidUtils.getHostIpAddress(appContext)
                ?: throw IOException(appContext.getString(R.string.privilege_error_no_host_ip))
            val paired = withContext(Dispatchers.IO) { manager.pair(host, pairingPort, pairingCode) }
            if (!paired) {
                throw IOException(appContext.getString(R.string.privilege_error_pair_failed))
            }
            Log.i(TAG, "配对成功，公钥已写入系统的已授权列表，之后连接不再需要配对码")

            connectAndLaunchInternal()
        }
    }

    /**
     * 静默尝试恢复连接。App 回到前台时调用，用户不需要知道它的存在。
     *
     * 配对是一次性的：密钥对存在本地、公钥存在系统的已授权列表里，两边都扛重启。
     * 所以只要无线调试是开着的，连接随时可以自己重建，没理由让用户去点一个按钮。
     *
     * 非挂起：调用方不用关心它什么时候结束，也不会因为调用方（比如某个 Composable）
     * 退出而被取消——之前就踩过协程被取消导致流程中断的坑。
     */
    fun tryAutoConnect() {
        scope.launch { autoConnect() }
    }

    /**
     * [tryAutoConnect] 的挂起版本。
     *
     * 开机广播需要知道什么时候做完，好把 `goAsync()` 的 pending result 结束掉——
     * 否则系统可能在连接完成前就把进程回收了。
     */
    suspend fun autoConnect() {
        if (PrivilegeManager.state.value is PrivilegeState.Ready) {
            Log.d(TAG, "特权进程已就绪，无需自动连接")
            return
        }
        if (!KittenAdbManager.hasKeyPair(appContext)) {
            Log.d(TAG, "本地没有 ADB 密钥，说明从没配对过，跳过自动连接")
            return
        }
        Log.i(TAG, "开始自动连接")
        runFlow { connectAndLaunchInternal() }
        // 失败是常态：无线调试可能压根没开。这种情况不该在界面上报错吓人，
        // 退回"未连接"就行，用户想用自然会去点「开始配对」。
        (step.value as? Step.Failed)?.let {
            Log.i(TAG, "自动连接未成功，保持未连接状态：${it.message}")
            _step.value = Step.Idle
        }
    }

    private suspend fun connectAndLaunchInternal() {
        _step.value = Step.Connecting
        Log.i(TAG, "正在连接本机 adbd…")
        val manager = adbManager()
        val connected = withContext(Dispatchers.IO) {
            manager.autoConnect(appContext, CONNECT_TIMEOUT_MS)
        }
        if (!connected) {
            throw IOException(appContext.getString(R.string.privilege_error_connect_failed))
        }
        Log.i(TAG, "已连上 adbd")

        _step.value = Step.Launching
        val backend = AdbBackend(manager)

        // 趁 ADB 还连着，把永久授权办了。只在还没拿到时跑——授权是一次性的，
        // 之后每次连接都重跑纯属浪费一个来回。失败不阻断启动：拿不到授权只是
        // 少了"开机自动恢复"的能力，特权进程本身照常可用。
        if (!hasSecureSettings()) {
            Log.i(TAG, "尚未取得 WRITE_SECURE_SETTINGS，执行一次性自助授权")
            runCatching { backend.launch(Starter.buildSelfGrantCommand(appContext.packageName)) }
                .onFailure { Log.w(TAG, "自助授权命令执行失败", it) }
            // 立刻回读确认。这是整条"开机全自动"链路成不成立的分水岭，
            // 所以无论成败都要在日志里说死，别让人去猜。
            if (hasSecureSettings()) {
                Log.i(TAG, "自助授权成功：已取得 WRITE_SECURE_SETTINGS，之后可开机自动恢复")
            } else {
                Log.w(
                    TAG,
                    "自助授权未生效。特权进程本身不受影响，但开机自动恢复用不了。" +
                        "请在 KittenAdbShell 的日志里看 pm grant 的输出",
                )
            }
        } else {
            Log.d(TAG, "已有 WRITE_SECURE_SETTINGS，跳过自助授权")
        }

        val apkPath = appContext.applicationInfo.sourceDir
        val token = PrivilegeManager.newLaunchToken()
        Log.i(TAG, "正在拉起特权进程…")
        backend.launch(Starter.buildCommand(apkPath, token))

        _step.value = Step.Launched
        // 到这里只代表命令发出去了。真正是否就绪看 PrivilegeManager 的
        // "特权已就绪，uid=2000"——binder 走的是另一条通路。
        Log.i(TAG, "启动命令已送达，等待特权进程交回 binder")
    }

    private fun hasSecureSettings(): Boolean =
        appContext.checkSelfPermission(PERMISSION_WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** 首次调用会生成 RSA 密钥对和自签证书，耗时可达几百毫秒，别放主线程。 */
    private suspend fun adbManager(): AbsAdbConnectionManager = withContext(Dispatchers.IO) {
        KittenAdbManager.getInstance(appContext)
    }

    /**
     * 同一时刻只允许一条流程在跑。
     *
     * 没有这个闸门的话，连点两次「开始配对」会并发启动两个特权进程——日志里实际出现过。
     */
    private suspend inline fun runFlow(block: () -> Unit) {
        if (!inProgress.compareAndSet(false, true)) {
            Log.w(TAG, "已有流程在进行中，忽略这次请求")
            return
        }
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "无线调试流程失败", t)
            _step.value = Step.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            inProgress.set(false)
        }
    }

    companion object {
        private const val TAG = "KittenWirelessAdb"
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val PERMISSION_WRITE_SECURE_SETTINGS =
            "android.permission.WRITE_SECURE_SETTINGS"

        @Volatile
        private var instance: WirelessDebugController? = null

        /** 全进程一个实例：App 界面和配对通知服务共用同一份进度状态。 */
        fun getInstance(context: Context): WirelessDebugController =
            instance ?: synchronized(this) {
                instance ?: WirelessDebugController(context.applicationContext).also { instance = it }
            }
    }
}
