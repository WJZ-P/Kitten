package me.wjz.kitten.permission

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import com.dianming.phoneapp.MyAccessibilityService

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
                        service.name == MyAccessibilityService::class.java.name
                }
            val notificationListenerEnabled = NotificationManagerCompat
                .getEnabledListenerPackages(context)
                .contains(context.packageName)
            return PermissionStatus(accessibilityEnabled, notificationListenerEnabled)
        }
    }
}
