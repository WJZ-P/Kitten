package me.wjz.kitten.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Reads the UI of the foreground app and performs actions on it.
 *
 * Skeleton only: it currently logs window changes. Reading and compressing the
 * node tree is the first milestone.
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
