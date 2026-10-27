package me.wjz.kitten.privilege

import android.content.Context
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.wjz.kitten.privilege.ipc.IPrivilegeService
import me.wjz.kitten.privilege.ipc.PrivilegeContract
import java.util.UUID

/**
 * 特权功能的统一入口（App 全局单例）。
 *
 * UI 和以后的 Agent 工具只跟它打交道，不需要关心 ADB 连接的细节。
 * 2a 阶段负责：生成启动 token、接收 binder、维护状态、暴露 IPrivilegeService。
 */
object PrivilegeManager {

    private const val TAG = "KittenPrivilege"

    private val _state = MutableStateFlow<PrivilegeState>(PrivilegeState.Idle)
    val state: StateFlow<PrivilegeState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var service: IPrivilegeService? = null

    @Volatile
    private var expectedToken: String? = null

    /** 当前这条连接的 binder，用来识别死讯来自新进程还是被替换掉的旧进程。 */
    @Volatile
    private var currentBinder: IBinder? = null

    /** 由 PrivilegeReceiverProvider 在创建时调用。 */
    fun attach(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * 生成一次启动用的 token 并进入 Connecting 状态。
     * 调用方把它交给 [Starter.buildCommand]，再通过 ADB shell 执行生成的命令。
     */
    fun newLaunchToken(): String {
        val token = UUID.randomUUID().toString()
        expectedToken = token
        _state.value = PrivilegeState.Connecting
        return token
    }

    /**
     * 由 PrivilegeReceiverProvider 调用：收到特权进程交回的 binder。
     *
     * @return 是否接受。返回 false 时特权进程会自行退出，不留孤儿进程。
     */
    fun onBinderReceived(binder: IBinder, token: String?): Boolean {
        val expected = expectedToken
        if (expected != null && token != expected) {
            Log.w(TAG, "token 不匹配，拒绝此 binder")
            return false
        }
        try {
            val proxy = IPrivilegeService.Stub.asInterface(binder)
            val uid = proxy.uid
            val version = proxy.version

            // 版本核对放在 linkToDeath 之前：拒绝的话就当这条连接从没存在过，
            // 不用再去 unlink。拒绝后特权进程会收到否定回执并自行退出（见 BinderSender），
            // 紧接着的 autoConnect 会拉起一个版本正确的。
            val expectedVersion = PrivilegeContract.PROTOCOL_VERSION
            if (version != expectedVersion) {
                Log.w(TAG, "特权进程版本不符：它是 $version，App 要的是 $expectedVersion，拒绝这个 binder")
                // 刚拉起的特权进程还是旧版本，说明磁盘上的 base.apk 本身就是旧的（特权进程按 base.apk
                // 启动，见 Starter）。开发时多半是 Android Studio 的增量部署：它只把改动的 dex 推进
                // App 自己的 code_cache/.overlay，App 进程读得到，特权进程读不到，重拉多少次都是旧的。
                // 正式安装包不会这样。
                if (version < expectedVersion) {
                    Log.w(
                        TAG,
                        "base.apk 里的特权侧代码比 App 旧：如果是 Android Studio 部署的，大概是增量部署" +
                            "（overlay）只更新了 App 进程的代码。完整重装一次（卸载重装，或在运行配置里勾选" +
                            " Always install with package manager）即可",
                    )
                }
                _state.value = PrivilegeState.Error("特权进程版本不符（$version ≠ $expectedVersion）")
                return false
            }

            // 每条连接一个 recipient：Starter 每次启动前会 pkill 掉上一个特权进程，
            // 旧进程的死讯可能晚于新进程的 Ready 才到，不能让它把新状态冲掉。
            val recipient = IBinder.DeathRecipient {
                if (currentBinder !== binder) {
                    Log.d(TAG, "被替换掉的特权进程退出，忽略")
                } else {
                    Log.w(TAG, "特权进程已断开")
                    currentBinder = null
                    service = null
                    _state.value = PrivilegeState.Idle
                }
            }
            binder.linkToDeath(recipient, 0)
            currentBinder = binder
            service = proxy
            _state.value = PrivilegeState.Ready(uid)
            Log.i(TAG, "特权已就绪，uid=$uid, version=$version")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "握手失败", t)
            // 异常自带的 message 通常已经够具体了；没有就退回类名，
            // 总比一句笼统的"握手失败"更有助于定位。
            _state.value = PrivilegeState.Error(t.message ?: t.javaClass.simpleName)
            return false
        }
    }

    /** 已连接时返回特权服务代理，否则 null。 */
    fun get(): IPrivilegeService? = service

    /**
     * 当前连着的特权进程是不是在跑旧代码。
     *
     * 握手时的那道校验挡不住这种情况：特权进程**不随 App 更新而重启**，
     * App 装了新版、而旧进程还活着时，它根本不会再交回一次 binder，
     * 于是 [state] 一直停在 Ready，没有任何地方会去复核。所以需要主动问一次。
     *
     * 这是个同步 binder 调用，别放主线程。
     *
     * @return true 表示该把特权进程重新拉起了。没有连接时返回 false——
     *         没连上就谈不上新旧，该不该拉起由 [state] 说。
     */
    fun isStale(): Boolean {
        val proxy = service ?: return false
        val expected = PrivilegeContract.PROTOCOL_VERSION
        val actual = try {
            proxy.version
        } catch (t: Throwable) {
            // 进程已经死了但死讯还没到，binder 调用会抛 DeadObjectException。
            // 这种连接本来就该重建，和版本不符一样处理。
            Log.w(TAG, "问不出特权进程的版本，当作需要重新拉起", t)
            return true
        }
        if (actual == expected) return false
        Log.i(TAG, "特权进程跑的是旧代码：它 version=$actual，App 要 version=$expected，需要重新拉起")
        return true
    }

    /** 主动停止特权进程。 */
    fun stop() {
        runCatching { service?.destroy() }
        currentBinder = null
        service = null
        _state.value = PrivilegeState.Idle
    }
}
