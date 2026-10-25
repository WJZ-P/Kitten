package me.wjz.kitten.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.wjz.kitten.MainActivity
import me.wjz.kitten.R
import me.wjz.kitten.agent.AgentTask
import me.wjz.kitten.agent.AgentTaskHandle

/**
 * Agent 跑任务期间的常驻通知。
 *
 * ## 它解决什么
 *
 * Agent 在副屏里操作应用时，用户多半正拿着主屏干别的——Kitten 的界面在后台。没有前台服务的
 * 话，系统随时可能因为内存紧张把整个进程收走，任务就断在半路。前台服务把进程顶到前台优先级，
 * 顺带有了一条常驻通知，正好用来显示"正在做什么"和一个"停止"。
 *
 * Agent 要问用户问题、而用户又不在 Kitten 界面里时，问题也显示在这条通知上，选项做成按钮，
 * 在通知上直接点就能回答，不用先切回来。
 *
 * ## 任务本身不在这里跑
 *
 * 循环还是跑在 ViewModel 的协程里，这个服务只负责"让进程活着 + 显示进度"。真把循环搬进来
 * 要在 Service 和界面之间再搭一套状态同步，眼下不值得。
 *
 * **已知边界**：用户从最近任务里把 Kitten 划掉时，Activity 和 ViewModel 一起没了，任务会
 * 中断——前台服务保得住进程，保不住 ViewModel。切到别的应用（最常见的情况）不受影响。
 *
 * ## 为什么是 specialUse
 *
 * Android 14 起前台服务必须声明类型，不同类型的运行时长限制不一样：
 *
 * - shortService：最多约 3 分钟，到点系统调 onTimeout，还不停就 ANR。看名字最像"跑一会儿就完"
 *   的任务，但 Agent 任务没有步数上限，"在淘宝里找到历史订单再下单"跑几分钟很正常，用不了它。
 * - dataSync / mediaProcessing：Android 15 起每 24 小时最多 6 小时。而且名不副实，审核也说不过去。
 * - specialUse：**没有时长限制**，这就是我们用的。代价是要在清单里写明用途（PROPERTY_SPECIAL_USE_FGS_SUBTYPE），
 *   上架 Google Play 时审核会看这段说明。配对服务（AdbPairingService）也是同样的考虑。
 *
 * 没有时长限制不等于不会被杀：用户从最近任务里划掉 Kitten、或者系统省电策略下手，任务一样会断，
 * 见上面的"已知边界"。
 */
class AgentForegroundService : Service() {

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.agent_channel_name),
                // 不用 HIGH：任务跑着的时候每一步都在更新这条通知，弹来弹去很吵。
                // 要用户回答时再单独提醒（见 notify）。
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.agent_channel_description) },
        )
        scope.launch {
            AgentTaskHandle.task.collectLatest { task ->
                if (task == null) {
                    // 任务结束了，通知跟着收掉。
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifications.notify(NOTIFICATION_ID, build(task))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 经由 startForegroundService 进来的实例必须先 startForeground 再做别的，否则会 ANR。
        startForeground(NOTIFICATION_ID, build(AgentTaskHandle.task.value))

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "用户在通知上点了停止")
                AgentTaskHandle.requestStop()
            }
            ACTION_ANSWER -> intent.getStringExtra(EXTRA_ANSWER)?.let { answer ->
                Log.i(TAG, "用户在通知上回答了问题")
                AgentTaskHandle.answer(answer)
            }
        }

        // 任务可能在服务起来之前就结束了（很短的一轮），那就别赖着不走。
        if (AgentTaskHandle.task.value == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(task: AgentTask?): Notification {
        val question = task?.question
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setOngoing(true)
            // 点通知本体回到 Kitten。
            .setContentIntent(openAppIntent())

        if (question == null) {
            // 标题是正在干什么（"正在读取屏幕内容""正在思考…"），正文是模型最近说的那段话：
            // 一眼看得到进度，下拉展开还能看到它在想什么。还没开口时正文退回一句泛泛的说明。
            val said = task?.lastSaid
            builder
                .setContentTitle(
                    task?.detail?.let { getString(R.string.agent_running_step, it) }
                        ?: getString(R.string.agent_running_thinking),
                )
                .setContentText(said ?: getString(R.string.agent_running_title))
            // 一段话常常不止一行，展开能看全。
            if (said != null) builder.setStyle(Notification.BigTextStyle().bigText(said))
            return builder.addAction(stopAction()).build()
        }

        // 要用户回答：标题换成问题本身，选项做成按钮，在通知上直接点就能答。
        builder
            .setContentTitle(getString(R.string.agent_question_title))
            .setContentText(question.question)
            // 问题可能比一行长，展开能看全。
            .setStyle(Notification.BigTextStyle().bigText(question.question))
        question.options.take(MAX_OPTION_ACTIONS).forEachIndexed { index, option ->
            builder.addAction(answerAction(index, option))
        }
        // 选项多到放不下，或者本来就要自己打字：引导用户回到 Kitten 里答。
        if (question.options.size > MAX_OPTION_ACTIONS || question.options.isEmpty()) {
            builder.addAction(
                Notification.Action.Builder(
                    null as Icon?,
                    getString(R.string.agent_answer_in_app),
                    openAppIntent(),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun stopAction(): Notification.Action =
        Notification.Action.Builder(
            // 显式标成 Icon?：另一个重载收 int 图标资源，不标类型会撞上重载解析。
            null as Icon?,
            getString(R.string.agent_action_stop),
            servicePendingIntent(Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP), REQUEST_STOP),
        ).build()

    private fun answerAction(index: Int, option: String): Notification.Action =
        Notification.Action.Builder(
            null as Icon?,
            option,
            servicePendingIntent(
                Intent(this, AgentForegroundService::class.java)
                    .setAction(ACTION_ANSWER)
                    .putExtra(EXTRA_ANSWER, option),
                // 每个选项一个请求码：PendingIntent 判等不看 extras，共用一个的话
                // 点哪个都会变成第一个选项。
                REQUEST_ANSWER_BASE + index,
            ),
        ).build()

    private fun servicePendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {

        private const val TAG = "KittenAgent"
        private const val CHANNEL_ID = "agent_task"
        private const val NOTIFICATION_ID = 2001

        private const val ACTION_STOP = "me.wjz.kitten.agent.action.STOP"
        private const val ACTION_ANSWER = "me.wjz.kitten.agent.action.ANSWER"
        private const val EXTRA_ANSWER = "me.wjz.kitten.agent.extra.ANSWER"

        private const val REQUEST_STOP = 1
        private const val REQUEST_OPEN = 2
        private const val REQUEST_ANSWER_BASE = 100

        /**
         * 通知上最多放几个选项按钮。
         *
         * 系统在折叠状态下通常只显示 3 个 action，再多用户也看不见。超出的用"回 Kitten 里选"
         * 兜底。
         */
        private const val MAX_OPTION_ACTIONS = 3

        /** 任务开始时拉起服务。幂等，重复调只是刷新通知。 */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
