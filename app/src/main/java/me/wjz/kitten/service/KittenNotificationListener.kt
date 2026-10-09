package me.wjz.kitten.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Entry point for incoming messages: the official way to learn that a new
 * message arrived without watching the screen.
 */
class KittenNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Log only the source package. Notification content is private and must never go to logcat.
        Log.d(TAG, "Notification posted by ${sbn.packageName}")
    }

    private companion object {
        const val TAG = "KittenNotify"
    }
}
