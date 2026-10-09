package me.wjz.kitten

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import me.wjz.kitten.service.KittenAccessibilityService

data class PermissionStatus(
    val accessibilityEnabled: Boolean,
    val notificationListenerEnabled: Boolean,
) {
    companion object {
        fun read(context: Context): PermissionStatus {
            val accessibilityManager = context.getSystemService(AccessibilityManager::class.java)
            val accessibilityEnabled = accessibilityManager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo.serviceInfo
                    service.packageName == context.packageName &&
                        service.name == KittenAccessibilityService::class.java.name
                }
            val notificationListenerEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
            return PermissionStatus(accessibilityEnabled, notificationListenerEnabled)
        }
    }
}
