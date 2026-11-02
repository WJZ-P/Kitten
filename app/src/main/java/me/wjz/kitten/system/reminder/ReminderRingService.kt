package me.wjz.kitten.system.reminder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import me.wjz.kitten.MainActivity
import me.wjz.kitten.R

/**
 * 提醒到点后一直响：放闹钟铃声、一阵一阵地震，挂一条通知，用户点"知道了"（或者点通知本身）才停，
 * 点"10 分钟后再提醒"就停下、十分钟后再来一遍。
 *
 * ## 为什么是前台服务
 *
 * 要一直响到用户理它为止，广播接收器那十来秒不够；进程在后台也不能被收走。媒体播放类型
 * （mediaPlayback）的前台服务正好：它在放声音，系统也这么认。
 *
 * ## 不会真的响一辈子
 *
 * 响满 [MaxRingMillis] 还没人理（手机落在家里了），就停下，换成一条安静的"刚才的提醒没人理"，免得一直
 * 响到没电。系统自带的闹钟也是这么做的。
 *
 * ## 声音和通知分开
 *
 * 铃声是这里自己放的（闹钟音量、闹钟用途，勿扰模式默认放行闹钟）；通知渠道本身不带声音、不震，不然
 * 通知自己再响一下，和铃声叠在一起。
 */
class ReminderRingService : Service() {

    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())

    /** 正在响的那条提醒。 */
    private var ringing: Reminder? = null
    private var player: MediaPlayer? = null

    /** 响满了还没人理：停下，留一条安静的通知。 */
    private val giveUp = Runnable {
        ringing?.let { notifications.notify(missedNotificationId(it.id), buildMissed(it)) }
        finish()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.reminder_channel_name),
                // HIGH 才会弹横幅：用户这会儿多半在干别的。
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.reminder_channel_description)
                // 铃声和震动是服务自己放的，见类注释。
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_ID)
        when (intent?.action) {
            ACTION_RING -> {
                val reminder = id?.let { ReminderStore.find(this, it) }
                if (reminder != null) {
                    startRinging(reminder)
                } else {
                    // 排着的时候被取消了（列表里点了取消、刚好赶上到点）：不响。但经 startForegroundService
                    // 进来的必须先 startForeground 一下，不然系统会当成没按规矩来、把进程弄崩。
                    Log.i(TAG, "要响的提醒已经不在了，不响")
                    if (ringing == null) {
                        startForeground(
                            RING_NOTIFICATION_ID,
                            buildRinging(Reminder(id.orEmpty(), "", "", 0)),
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                        )
                        finish()
                    }
                }
            }
            ACTION_DISMISS -> finish()
            ACTION_SNOOZE -> {
                ringing?.let { reminder ->
                    val again = reminder.copy(triggerAtMillis = System.currentTimeMillis() + SnoozeMillis)
                    Reminders.schedule(this, again)?.let { Log.w(TAG, "再提醒排不上：$it") }
                }
                finish()
            }
            else -> if (ringing == null) finish()
        }
        return START_NOT_STICKY
    }

    private fun startRinging(reminder: Reminder) {
        // 已经在响另一条：那条算响过了（留一条安静的通知），换成这条。
        ringing?.let { previous -> notifications.notify(missedNotificationId(previous.id), buildMissed(previous)) }
        // 第一次响时写进对话；"稍后再响"排的那一次带着 announced，不再写一遍。
        if (!reminder.announced) Reminders.announce(this, reminder)
        ringing = reminder.copy(announced = true)
        // 响了就从"还没响的"里拿掉：任务页上不再列它；要再提醒时重新排、重新存。
        ReminderStore.remove(this, reminder.id)

        startForeground(RING_NOTIFICATION_ID, buildRinging(reminder), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        // 换了一条接着响：铃声、震动从头来。
        silence()
        playSound()
        vibrate()
        handler.removeCallbacks(giveUp)
        handler.postDelayed(giveUp, MaxRingMillis)
        Log.i(TAG, "提醒响了")
    }

    private fun playSound() {
        if (player != null) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setDataSource(this@ReminderRingService, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { Log.w(TAG, "铃声放不出来，只震动", it) }.getOrNull()
    }

    private fun vibrate() {
        val vibrator = getSystemService(VibratorManager::class.java)?.defaultVibrator ?: return
        vibrator.vibrate(
            // 震 0.8 秒、停 0.6 秒，一直重复（repeat = 0 表示从头循环）。
            VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    /** 停下所有声音和震动，收掉响铃的通知，服务退出。 */
    private fun finish() {
        silence()
        ringing = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun silence() {
        handler.removeCallbacks(giveUp)
        player?.let { current ->
            // 没放起来的 MediaPlayer 调 stop 会抛异常，但 release 照样要调，分开包。
            runCatching { current.stop() }
            current.release()
        }
        player = null
        getSystemService(VibratorManager::class.java)?.defaultVibrator?.cancel()
    }

    override fun onDestroy() {
        silence()
        super.onDestroy()
    }

    /** 标题是提醒的标题，正文是完整内容（没有就只有标题），顶上那行小字写着是 Kitten 在提醒。 */
    private fun buildRinging(reminder: Reminder): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setSubText(getString(R.string.reminder_title))
            .setContentTitle(reminder.title)
            .apply {
                if (reminder.note.isNotBlank()) {
                    setContentText(reminder.note)
                    setStyle(Notification.BigTextStyle().bigText(reminder.note))
                }
            }
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            // 点通知本身就是"知道了"：一直响的东西，点哪儿都该能让它停。
            .setContentIntent(serviceIntent(ACTION_DISMISS, REQUEST_DISMISS_BODY))
            .addAction(action(R.string.reminder_action_dismiss, ACTION_DISMISS, REQUEST_DISMISS))
            .addAction(action(R.string.reminder_action_snooze, ACTION_SNOOZE, REQUEST_SNOOZE))
            .build()

    private fun buildMissed(reminder: Reminder): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setContentTitle(getString(R.string.reminder_missed_title))
            .setContentText(reminder.title)
            .setStyle(Notification.BigTextStyle().bigText(reminder.title))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    REQUEST_OPEN,
                    MainActivity.chatIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun action(label: Int, action: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(null as Icon?, getString(label), serviceIntent(action, requestCode)).build()

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ReminderRingService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 每条提醒一个"没人理"的通知，几条一起错过时不会互相顶掉。 */
    private fun missedNotificationId(id: String) = MISSED_NOTIFICATION_BASE + (id.hashCode() and 0xFFFF)

    companion object {
        private const val TAG = "KittenReminder"
        private const val CHANNEL_ID = "reminder"
        private const val RING_NOTIFICATION_ID = 3002
        private const val MISSED_NOTIFICATION_BASE = 40000

        private const val ACTION_RING = "me.wjz.kitten.reminder.action.RING"
        private const val ACTION_DISMISS = "me.wjz.kitten.reminder.action.DISMISS"
        private const val ACTION_SNOOZE = "me.wjz.kitten.reminder.action.SNOOZE"
        private const val EXTRA_ID = "me.wjz.kitten.reminder.extra.ID"

        private const val REQUEST_DISMISS = 1
        private const val REQUEST_SNOOZE = 2
        private const val REQUEST_DISMISS_BODY = 3
        private const val REQUEST_OPEN = 4

        /**
         * 一直响最多响多久（毫秒）。十分钟：人在旁边早就听见了；不在旁边，再响也是白耗电。系统闹钟
         * 一般也是十分钟左右自动停。
         */
        private const val MaxRingMillis = 10 * 60 * 1000L

        /** "10 分钟后再提醒"是多久以后。改这里的话通知按钮上的字也要跟着改（strings.xml 的 reminder_action_snooze）。 */
        private const val SnoozeMillis = 10 * 60 * 1000L

        /** 让一条提醒响起来（[ReminderReceiver] 到点时调）。 */
        fun ring(context: Context, id: String) {
            context.startForegroundService(
                Intent(context, ReminderRingService::class.java).setAction(ACTION_RING).putExtra(EXTRA_ID, id),
            )
        }
    }
}
