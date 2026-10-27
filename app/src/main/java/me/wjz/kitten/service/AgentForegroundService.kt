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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import me.wjz.kitten.AppVisibility
import me.wjz.kitten.MainActivity
import me.wjz.kitten.R
import me.wjz.kitten.agent.AgentQuestion
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
 * ## 提问单独一条
 *
 * 常驻通知的渠道是低优先级的（每一步都在更新它，高优先级会弹来弹去），所以 Agent 要问用户问题时
 * 它不会主动提醒——用户在别的应用里根本不知道 Kitten 在等他。提问因此另发一条高优先级的通知
 * （[updateQuestionAlert]）：弹出横幅，选项做成按钮，在通知上直接点就能回答，不用先切回来。
 *
 * 用户正看着 Kitten 时不弹：对话里已经有提问卡片了，再弹一条横幅是重复。切到别的应用了才弹，
 * 回到 Kitten 就收掉（看 [AppVisibility]）。
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

    /** 提问那条通知现在挂着的是哪个问题，没挂着是 null。见 [updateQuestionAlert]。 */
    private var alertedQuestion: AgentQuestion? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.agent_channel_name),
                    // 不用 HIGH：任务跑着的时候每一步都在更新这条通知，弹来弹去很吵。
                    // 要用户回答时另发一条高优先级的（见 updateQuestionAlert）。
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.agent_channel_description) },
                NotificationChannel(
                    QUESTION_CHANNEL_ID,
                    getString(R.string.agent_question_channel_name),
                    // HIGH 才会弹横幅：用户多半在别的应用里，不弹出来就不知道 Kitten 在等他。
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = getString(R.string.agent_question_channel_description) },
            ),
        )
        scope.launch {
            // 任务和"用户看不看得见 Kitten"一起盯：任何一边变了，都要重新决定提问那条弹不弹。
            combine(AgentTaskHandle.task, AppVisibility.inForeground) { task, visible -> task to visible }
                .collectLatest { (task, visible) ->
                    if (task == null) {
                        // 任务结束了，通知跟着收掉。
                        updateQuestionAlert(question = null, appVisible = visible)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        notifications.notify(NOTIFICATION_ID, build(task))
                        updateQuestionAlert(task.question, visible)
                    }
                }
        }
    }

    /**
     * 提问那条单独的高优先级通知：要问用户问题、用户又看不见 Kitten 时弹出来；答完了、任务结束了、
     * 或者用户回到了 Kitten（对话里有提问卡片）就收掉。
     *
     * 同一个问题只弹一次：任务每走一步常驻通知都会更新，这里跟着重发的话会一遍遍地提醒。用户回到
     * Kitten 看了一眼没答又走了，会再弹一次——那时候确实该再叫一下。
     */
    private fun updateQuestionAlert(question: AgentQuestion?, appVisible: Boolean) {
        if (question == null || appVisible) {
            if (alertedQuestion != null) {
                notifications.cancel(QUESTION_NOTIFICATION_ID)
                alertedQuestion = null
            }
            return
        }
        if (question == alertedQuestion) return
        notifications.notify(QUESTION_NOTIFICATION_ID, buildQuestion(question))
        alertedQuestion = question
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
        // 服务没了提问也没人接了，别留一条点了没反应的通知。
        notifications.cancel(QUESTION_NOTIFICATION_ID)
        super.onDestroy()
    }

    private fun build(task: AgentTask?): Notification {
        val question = task?.question
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setOngoing(true)
            // 点通知本体回到 Kitten。
            .setContentIntent(openAppIntent())

        // 标题是正在干什么（"正在读取屏幕内容""正在思考…"），正文是模型最近说的那段话：一眼看得到
        // 进度，下拉展开还能看到它在想什么。还没开口时正文退回一句泛泛的说明。
        //
        // 在等用户回答时标题换成"正在等你回答…"，但不在这里写问题本身：问题在单独那条提问通知上
        // （buildQuestion），这里再写一遍，通知栏里就是两条一模一样的。那条被划掉了也不要紧，点
        // 这条回到 Kitten 里，对话里有提问卡片。
        val said = task?.lastSaid
        builder
            .setContentTitle(
                when {
                    question != null -> getString(R.string.agent_waiting_answer)
                    task?.detail != null -> getString(R.string.agent_running_step, task.detail)
                    else -> getString(R.string.agent_running_thinking)
                },
            )
            .setContentText(said ?: getString(R.string.agent_running_title))
        // 一段话常常不止一行，展开能看全。
        if (said != null) builder.setStyle(Notification.BigTextStyle().bigText(said))
        return builder.addAction(stopAction()).build()
    }

    /**
     * 提问那条通知：高优先级，弹横幅。选项做成按钮，点了直接作答；选项太多或者要自己打字时，
     * 给一个"回 Kitten 里回答"。
     */
    private fun buildQuestion(question: AgentQuestion): Notification {
        val title = getString(R.string.agent_question_title)
        val builder = Notification.Builder(this, QUESTION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setContentTitle(title)
            .setContentText(question.question)
            .setStyle(Notification.BigTextStyle().bigText(question.question))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openAppIntent())
            // 点通知本体回到 Kitten 时顺手收掉；点选项答完由 updateQuestionAlert 收。
            .setAutoCancel(true)
            // 锁屏上只说"Kitten 想问一下"，不露问题本身：问题里常带着查到的订单、聊天内容。
            // 用户在系统里设了锁屏显示全部内容的话，系统照样显示完整的那份。
            .setPublicVersion(
                Notification.Builder(this, QUESTION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_agent_notification)
                    .setContentTitle(title)
                    .build(),
            )
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

    // 回到对话页、而且不能把开着的 Kitten 重建掉（会把正在跑的任务一起清掉），见 chatIntent。
    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_OPEN,
            MainActivity.chatIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {

        private const val TAG = "KittenAgent"
        private const val CHANNEL_ID = "agent_task"
        private const val NOTIFICATION_ID = 2001

        /** 提问那条单独的通知：自己一个高优先级的渠道，自己一个 id，和常驻那条互不影响。 */
        private const val QUESTION_CHANNEL_ID = "agent_question"
        private const val QUESTION_NOTIFICATION_ID = 2002

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
