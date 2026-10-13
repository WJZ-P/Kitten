package me.wjz.kitten.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 读取前台 App 的界面，并在上面执行操作。
 *
 * 服务本身只做两件事：把实例挂到 [peek] 上供感知层取用，以及记录窗口变化。
 * 真正抓节点树的逻辑在 `me.wjz.kitten.a11y.ScreenReader` 里——那是个无状态的
 * util，不塞进服务，免得这个类随功能增长膨胀成什么都往里放的垃圾桶。
 */
class KittenAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 服务被关掉或系统重启它时必须撤掉引用，否则 ScreenReader 会拿着一个
        // 已经失效的 Service 去查节点，报的错还和"没开无障碍"长得不一样。
        instance = null
        Log.i(TAG, "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
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

    companion object {
        private const val TAG = "KittenA11y"

        /**
         * 当前活着的服务实例。
         *
         * 感知层（ScreenReader）要用它去查节点树，而服务的生命周期由系统掌握，
         * 我们没法自己 new 一个，只能在连上时把引用存下来。
         *
         * 静态持有 Service（它是个 Context）通常要警惕泄漏，这里是安全的：
         * 引用在 onUnbind/onDestroy 里必定被清空，活得不会比服务本身更久。
         */
        @Volatile
        private var instance: KittenAccessibilityService? = null

        /** 取当前服务实例；没开启无障碍、或服务正在重启时为 null。 */
        fun peek(): KittenAccessibilityService? = instance
    }
}
