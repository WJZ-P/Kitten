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
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        // 带上 displayId：副屏方案成不成立，全看无障碍能不能感知到主屏之外的窗口。
        // 实测（HyperOS/Android 16）是可以的——应用只存在于副屏时，这里照样收得到事件。
        Log.d(TAG, "Window changed: ${event.packageName} @display=${event.displayId}")

        // getWindows() 只覆盖默认屏，getWindowsOnAllDisplays() 才看得到副屏。
        // 这行同时验证了"能不能真的读到副屏的节点"，而不只是"收得到事件"。
        runCatching {
            windowsOnAllDisplays.let { byDisplay ->
                if (byDisplay.size() <= 1) return@let
                val summary = (0 until byDisplay.size()).joinToString(" ") { i ->
                    "display${byDisplay.keyAt(i)}=${byDisplay.valueAt(i).size}窗口"
                }
                Log.i(TAG, "多屏窗口：$summary")
            }
        }.onFailure { Log.w(TAG, "读取多屏窗口失败", it) }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "KittenA11y"
    }
}
