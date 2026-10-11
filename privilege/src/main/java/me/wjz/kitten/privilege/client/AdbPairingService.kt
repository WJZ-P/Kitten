package me.wjz.kitten.privilege.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.R

/**
 * 在通知里完成无线调试配对。
 *
 * 为什么要绕这么一圈：系统「使用配对码配对设备」的配对码，只在那个弹窗打开期间有效。
 * 用户切回 Kitten 输入的瞬间弹窗就关了，配对码同时作废，所以 App 里放输入框是行不通的。
 * 通知栏是少数能盖在弹窗上、又不会把弹窗挤掉的交互入口，Shizuku 也是这么做的。
 *
 * 流程：
 *   ACTION_START → 前台服务 + mDNS 持续监听配对端口
 *   发现端口     → 把通知换成带内联回复（RemoteInput）的版本
 *   ACTION_REPLY → 取出配对码 → 配对 → 连接 → 拉起特权进程
 */
class AdbPairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val controller by lazy { WirelessDebugController.getInstance(this) }

    /** 停止 mDNS 监听的函数，null 表示当前没在监听。 */
    private var stopMdns: (() -> Unit)? = null

    /** 已经就这个端口弹过通知了。mDNS 可能重复上报同一个端口，别反复弹横幅骚扰用户。 */
    @Volatile
    private var notifiedPort = -1

    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.privilege_channel_name),
                // 用 HIGH：配对弹窗一出现就弹横幅，用户不用自己想起来下拉通知栏。
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.privilege_channel_description)
                // 要横幅但不要响：配对时用户正盯着设置页，提示音没必要。
                setSound(null, null)
                setShowBadge(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground(searchingNotification())
                startSearch()
            }

            ACTION_REPLY -> {
                goForeground(workingNotification(getString(R.string.privilege_notif_pairing)))
                onReply(intent)
            }

            else -> {
                // ACTION_STOP，或者被系统异常唤起。
                // 经由 startForegroundService 进来的实例必须先 startForeground 再退出，否则会 ANR。
                goForeground(workingNotification(getString(R.string.privilege_notif_stopping)))
                stopSearch()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSearch()
        scope.cancel()
        super.onDestroy()
    }

    // ---- 配对码回调 ----

    private fun onReply(intent: Intent) {
        // 用户可能顺手打了空格或连字符，只留数字。
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(REMOTE_INPUT_KEY)
            ?.filter { it.isDigit() }
            ?.toString()
        // 端口来自 PendingIntent 的 extra：每次重开配对弹窗系统都会换端口，
        // 所以不能用成员变量，必须用这条通知当时带的那个。
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        if (code.isNullOrEmpty() || port <= 0) {
            notify(retryNotification(getString(R.string.privilege_notif_no_code)))
            return
        }
        // 配对码是一次性的，停掉 mDNS，免得又把通知刷回「等待中」。
        stopSearch()
        scope.launch { pairAndLaunch(port, code) }
    }

    private suspend fun pairAndLaunch(port: Int, code: String) {
        controller.pairAndLaunch(port, code)
        val step = controller.step.value
        if (step is WirelessDebugController.Step.Failed) {
            notify(retryNotification(step.message))
            startSearch()
            return
        }
        // ADB 侧已经把命令发出去了，特权进程还要花一点时间把 binder 交回来。
        notify(workingNotification(getString(R.string.privilege_notif_waiting_binder)))
        val state = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
            PrivilegeManager.state.first {
                it is PrivilegeState.Ready || it is PrivilegeState.Error
            }
        }
        when (state) {
            is PrivilegeState.Ready -> {
                notifyDone(getString(R.string.privilege_notif_ready, state.uid))
                stopSelf()
            }

            is PrivilegeState.Error -> notify(retryNotification(state.message))

            else -> notifyDone(getString(R.string.privilege_notif_no_binder))
        }
    }

    // ---- mDNS ----

    private fun startSearch() {
        if (stopMdns != null) return
        notifiedPort = -1
        stopMdns = AdbMdnsHelper.observePort(this, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { port ->
            // 端口变了说明用户重开了配对弹窗，这时要换一条新通知（里面的端口也得跟着换）。
            if (port == notifiedPort) return@observePort
            notifiedPort = port
            Log.i(TAG, "发现配对端口 $port")
            notify(inputNotification(port))
        }
    }

    private fun stopSearch() {
        runCatching { stopMdns?.invoke() }
        stopMdns = null
    }

    // ---- 通知 ----

    private fun goForeground(notification: Notification) {
        if (isForeground) {
            notify(notification)
            return
        }
        try {
            startForeground(NOTIFICATION_ID, notification)
            isForeground = true
        } catch (e: IllegalStateException) {
            // API 31+ 从后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException
            // 继承自 IllegalStateException）。退化成普通通知，至少配对码还能填。
            Log.w(TAG, "转入前台失败，退化为普通通知", e)
            notify(notification)
        }
    }

    private fun notify(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** 收尾：把通知从前台服务上摘下来，这样服务退出后它还留着给用户看。 */
    private fun notifyDone(text: String) {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_DETACH)
            isForeground = false
        }
        notify(doneNotification(text))
    }

    // 通知刻意保持极简。操作按钮只存在于通知的「展开布局」里，而带长文本（尤其是
    // BigTextStyle）的通知在 MIUI / HyperOS 下默认以折叠形态显示，按钮就整个看不见了。
    // Shizuku 在同款 ROM 上能显示按钮，它那条通知正是「只有标题 + 一个按钮」。
    private fun baseNotification(title: String): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_privilege_notification)
            // Android 12+ 会用这个颜色给小图标的圆形底衬上色，让人一眼认出是 Kitten。
            .setColor(getColor(R.color.privilege_notification_color))
            .setContentTitle(title)

    /**
     * 服务还在跑时用的通知。
     *
     * Android 14 起前台服务通知可以被用户划掉，但**划掉不会停掉服务**。不挂 deleteIntent
     * 的话，服务会继续在后台空转，而用户已经没有任何入口能填配对码了。
     * 所以这里把"划掉"当成"取消"处理。
     */
    private fun ongoingNotification(title: String): Notification.Builder =
        baseNotification(title).setDeleteIntent(servicePendingIntent(ACTION_STOP, REQUEST_STOP))

    private fun searchingNotification(): Notification =
        ongoingNotification(getString(R.string.privilege_notif_searching_title))
            .setContentText(getString(R.string.privilege_notif_searching_text))
            .addAction(action(ACTION_STOP, REQUEST_STOP, R.string.privilege_action_cancel))
            .build()

    /** 只放一个操作：按钮越少、文本越短，越不容易被 ROM 的折叠布局吃掉。 */
    private fun inputNotification(port: Int): Notification =
        ongoingNotification(getString(R.string.privilege_notif_input_title))
            .addAction(replyAction(port))
            .build()

    private fun workingNotification(text: String): Notification =
        ongoingNotification(text).build()

    private fun retryNotification(message: String): Notification =
        ongoingNotification(getString(R.string.privilege_notif_failed_title))
            .setContentText(message)
            .addAction(action(ACTION_START, REQUEST_RETRY, R.string.privilege_action_retry))
            .build()

    /**
     * 收尾通知：流程已经走完、服务即将退出。
     *
     * 只有这一条可以点：点一下回到 Kitten 看结果，并随 autoCancel 自动消失。
     * 中途那些通知刻意不挂 contentIntent——配对期间用户必须留在系统设置的配对弹窗里，
     * 把他拽回 App 等于让配对码当场作废。
     *
     * 也不能挂 deleteIntent：此时服务已经停了，划掉它反而会把服务又拉起来。
     */
    private fun doneNotification(text: String): Notification =
        baseNotification(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

    // ---- PendingIntent ----

    private fun replyAction(port: Int): Notification.Action {
        val intent = serviceIntent(ACTION_REPLY).putExtra(EXTRA_PORT, port)
        // FLAG_MUTABLE 是必须的：系统要把用户输入的内容填进这个 Intent，不可变就收不到。
        // FLAG_UPDATE_CURRENT 保证重开配对弹窗换了端口后，Intent 里的端口也跟着刷新
        //（PendingIntent 判等不看 extras，不加这个标志就会一直用第一次那个旧端口）。
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getForegroundService(this, REQUEST_REPLY, intent, flags)
        // 图标传 null：Android 7 起手机通知栏就不画 action 图标了，Shizuku 也是传 null。
        return Notification.Action.Builder(
            null as Icon?,
            getString(R.string.privilege_action_input_code),
            pending,
        )
            .addRemoteInput(
                RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel(getString(R.string.privilege_action_code_hint))
                    .build(),
            )
            .build()
    }

    /** @param titleRes 按钮文案的资源 id。 */
    private fun action(action: String, requestCode: Int, titleRes: Int): Notification.Action =
        Notification.Action.Builder(
            null as Icon?,
            getString(titleRes),
            servicePendingIntent(action, requestCode),
        ).build()

    /** 点通知回到 Kitten。拿不到启动 Intent 时返回 null，通知就退化成不可点击。 */
    private fun openAppIntent(): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            serviceIntent(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun serviceIntent(action: String): Intent =
        Intent(this, AdbPairingService::class.java).setAction(action)

    companion object {
        private const val TAG = "KittenAdbPairing"
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 1001
        private const val REMOTE_INPUT_KEY = "pairing_code"
        private const val EXTRA_PORT = "port"

        private const val ACTION_START = "me.wjz.kitten.privilege.action.PAIRING_START"
        private const val ACTION_REPLY = "me.wjz.kitten.privilege.action.PAIRING_REPLY"
        private const val ACTION_STOP = "me.wjz.kitten.privilege.action.PAIRING_STOP"

        private const val REQUEST_REPLY = 1
        private const val REQUEST_RETRY = 2
        private const val REQUEST_STOP = 3
        private const val REQUEST_OPEN_APP = 4

        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        /**
         * 弹出配对通知。调用方必须已经拿到通知权限（API 33+ 要运行时申请），
         * 并且自己处于前台，否则 API 31+ 不允许启动前台服务。
         */
        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, AdbPairingService::class.java).setAction(ACTION_START),
            )
        }
    }
}
