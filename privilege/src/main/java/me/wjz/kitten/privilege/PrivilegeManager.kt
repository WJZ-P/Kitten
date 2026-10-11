package me.wjz.kitten.privilege

import android.content.Context
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.wjz.kitten.privilege.ipc.IPrivilegeService
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
            val proxy = IPrivilegeService.Stub.asInterface(binder)
            val uid = proxy.uid
            val version = proxy.version
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

    /** 主动停止特权进程。 */
    fun stop() {
        runCatching { service?.destroy() }
        currentBinder = null
        service = null
        _state.value = PrivilegeState.Idle
    }
}
