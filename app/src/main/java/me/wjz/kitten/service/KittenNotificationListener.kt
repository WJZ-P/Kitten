package me.wjz.kitten.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 新消息的入口：通过官方 API 得知有新消息到来，不需要一直盯着屏幕。
 */
class KittenNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // 只记录来源包名。通知内容属于隐私，绝不能写进 logcat。
        Log.d(TAG, "Notification posted by ${sbn.packageName}")
    }

    private companion object {
        const val TAG = "KittenNotify"
    }
}
