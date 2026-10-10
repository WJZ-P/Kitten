package me.wjz.kitten.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 读取前台 App 的界面，并在上面执行操作。
 *
 * 目前只是骨架：仅记录窗口切换。读取并压缩节点树是第一个里程碑。
 */
class KittenAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Window changed: ${event.packageName}")
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "KittenA11y"
    }
}
