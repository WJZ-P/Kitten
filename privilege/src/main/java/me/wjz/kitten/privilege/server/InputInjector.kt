package me.wjz.kitten.privilege.server

import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.TimeUnit

/**
 * 在指定屏幕上注入输入事件。
 *
 * ## 为什么必须在特权进程里做
 *
 * `INJECT_EVENTS` 的 protectionLevel 是**纯 signature**，不像 `WRITE_SECURE_SETTINGS`
 * 那样带 `development` 标志，所以 `pm grant` 也授不了，App 进程永远拿不到。
 * 而 `com.android.shell` 本身就持有它（实测 `granted=true`），特权进程跑在它名下。
 *
 * ## 两条实现路径：反射优先，命令兜底
 *
 * **主路径是反射 `InputManager.injectInputEvent`。** 两个关键 API 都够稳：
 * - `injectInputEvent(InputEvent, int)`：Android 14 把实现挪进了 `InputManagerGlobal`，
 *   但 `InputManager` 上的这个入口被保留为转发方法，签名没动；
 * - `InputEvent.setDisplayId(int)`：`@TestApi`，在 test API 列表里。不设它事件只会
 *   落到主屏，副屏就白干了。
 *
 * 而且特权进程**不受 non-SDK 接口限制**——它是 `app_process` 直接起的，不走 zygote
 * 那套应用初始化流程（和 [SystemContext] 反射 `ActivityThread` 是同一个道理）。
 *
 * **兜底是 `input` 命令。** 万一哪个 ROM 真改了上面两个签名，还能降级运行。
 * 实测 `input` 一次 37–52ms（它在现代 Android 上走 `cmd input` 直连 system_server，
 * 不再是老式的 `app_process` 起 JVM），而反射只是一次 binder 调用。差着一个数量级，
 * 所以只在反射不可用时才用它。
 *
 * 反射那条路还顺带带来一个命令给不了的能力：**任意路径和时间曲线**。`input swipe`
 * 只能直线匀速，而注入可以自己安排每个 MOVE 点的位置和时间戳——以后要模拟更像真人的
 * 手势轨迹，得靠这个。
 *
 * 命令兜底仍然用参数数组而不是拼命令行：不经过 shell，不存在命令注入。
 */
internal object InputInjector {

    private const val TAG = "KittenPrivInput"

    /** 命令兜底的超时。注入应当是瞬时的，卡住说明环境有问题。 */
    private const val TIMEOUT_MS = 5_000L

    /**
     * `InputEventInjectionSync.WAIT_FOR_FINISHED`，@hide 常量只能写字面量。
     *
     * 选"等派发完成"而不是 NONE：Agent 必须知道这一下到底点下去没有，否则紧接着的
     * 抓取会读到动作执行途中的中间状态。
     */
    private const val INJECT_SYNC_WAIT_FOR_FINISHED = 2

    /** 一次点击里 DOWN 到 UP 的间隔。太短有些应用会当成误触丢掉。 */
    private const val TAP_HOLD_MS = 60L

    /** 滑动过程中每隔多久补一个 MOVE 点。16ms 约等于 60fps，再密没有意义。 */
    private const val MOVE_INTERVAL_MS = 16L

    /** 反射拿到的注入通道；为 null 表示这条路不通，得走命令兜底。 */
    private val reflection: Reflection? by lazy { Reflection.create() }

    fun tap(displayId: Int, x: Int, y: Int): Boolean {
        reflection?.let { r ->
            val downTime = SystemClock.uptimeMillis()
            val ok = r.motion(displayId, MotionEvent.ACTION_DOWN, x, y, downTime, downTime) &&
                r.motion(displayId, MotionEvent.ACTION_UP, x, y, downTime, downTime + TAP_HOLD_MS)
            if (ok) return true
            Log.w(TAG, "反射注入 tap 失败，降级到 input 命令")
        }
        return exec(listOf("input", "-d", "$displayId", "tap", "$x", "$y"))
    }

    /**
     * 滑动。
     *
     * [durationMs] 决定快慢，这个值很关键：太短（<100ms）会被识别成甩动（fling），
     * 列表会一直滚到停不下来；想精确滚一屏就给 300ms 以上。
     */
    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        reflection?.let { r ->
            if (injectSwipe(r, displayId, x1, y1, x2, y2, durationMs.toLong())) return true
            Log.w(TAG, "反射注入 swipe 失败，降级到 input 命令")
        }
        return exec(
            listOf("input", "-d", "$displayId", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs"),
        )
    }

    /**
     * 长按。
     *
     * 本质就是"按下、原地停留、抬起"，所以用起止点相同的滑动实现——`input` 命令也没有
     * 单独的 longpress 子命令，同样是这么做的。
     */
    fun longPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean =
        swipe(displayId, x, y, x, y, durationMs)

    /** 发一个按键事件，keyCode 取 `KeyEvent.KEYCODE_*`。 */
    fun keyEvent(displayId: Int, keyCode: Int): Boolean {
        reflection?.let { r ->
            val downTime = SystemClock.uptimeMillis()
            val ok = r.key(displayId, KeyEvent.ACTION_DOWN, keyCode, downTime, downTime) &&
                r.key(displayId, KeyEvent.ACTION_UP, keyCode, downTime, downTime)
            if (ok) return true
            Log.w(TAG, "反射注入 keyEvent 失败，降级到 input 命令")
        }
        return exec(listOf("input", "-d", "$displayId", "keyevent", "$keyCode"))
    }

    /**
     * 把一次滑动拆成 DOWN → 若干 MOVE → UP。
     *
     * 中间的 MOVE 点不能省：只发 DOWN 和 UP 的话，系统看到的是"手指瞬移"，
     * 多数应用的滚动逻辑依赖连续的移动量，会完全没反应。
     */
    private fun injectSwipe(
        r: Reflection,
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Long,
    ): Boolean {
        val downTime = SystemClock.uptimeMillis()
        if (!r.motion(displayId, MotionEvent.ACTION_DOWN, x1, y1, downTime, downTime)) return false

        val steps = (durationMs / MOVE_INTERVAL_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            val x = x1 + ((x2 - x1) * fraction).toInt()
            val y = y1 + ((y2 - y1) * fraction).toInt()
            val at = downTime + (durationMs * fraction).toLong()
            if (!r.motion(displayId, MotionEvent.ACTION_MOVE, x, y, downTime, at)) return false
        }
        return r.motion(displayId, MotionEvent.ACTION_UP, x2, y2, downTime, downTime + durationMs)
    }

    /**
     * 反射出来的注入通道。
     *
     * 一次性解析好，解析失败就整个为 null——不要每次调用都去试，那样在不支持的设备上
     * 会反复抛异常。
     */
    private class Reflection(
        private val inputManager: Any,
        private val injectInputEvent: java.lang.reflect.Method,
        private val setDisplayId: java.lang.reflect.Method,
    ) {
        fun motion(
            displayId: Int,
            action: Int,
            x: Int,
            y: Int,
            downTime: Long,
            eventTime: Long,
        ): Boolean {
            val event = MotionEvent.obtain(
                downTime, eventTime, action, x.toFloat(), y.toFloat(), 0,
            )
            return try {
                // 不设 source 的话事件不会被当成触摸，落到应用那里是无效的。
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                setDisplayId.invoke(event, displayId)
                injectInputEvent.invoke(
                    inputManager, event, INJECT_SYNC_WAIT_FOR_FINISHED,
                ) as? Boolean ?: false
            } catch (t: Throwable) {
                Log.w(TAG, "注入 MotionEvent 失败", t)
                false
            } finally {
                // MotionEvent 的对象池还在用（不像 AccessibilityNodeInfo 已经废弃），
                // 滑动一次要造几十个事件，不回收会白白搅动 GC。
                event.recycle()
            }
        }

        fun key(
            displayId: Int,
            action: Int,
            keyCode: Int,
            downTime: Long,
            eventTime: Long,
        ): Boolean = try {
            val event = KeyEvent(
                downTime, eventTime, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            setDisplayId.invoke(event, displayId)
            injectInputEvent.invoke(
                inputManager, event, INJECT_SYNC_WAIT_FOR_FINISHED,
            ) as? Boolean ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "注入 KeyEvent 失败", t)
            false
        }

        companion object {
            fun create(): Reflection? = runCatching {
                val context = SystemContext.get() ?: error("没有系统 Context")
                val im = context.getSystemService(InputManager::class.java)
                    ?: error("取不到 InputManager")
                // setDisplayId 定义在 InputEvent 上，MotionEvent/KeyEvent 都是它的子类。
                val setDisplayId = InputEvent::class.java
                    .getMethod("setDisplayId", Int::class.javaPrimitiveType)
                val inject = InputManager::class.java.getMethod(
                    "injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType,
                )
                Log.i(TAG, "输入注入走反射通道")
                Reflection(im, inject, setDisplayId)
            }.getOrElse {
                Log.w(TAG, "反射注入通道不可用，将降级到 input 命令", it)
                null
            }
        }
    }

    private fun exec(args: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            Log.e(TAG, "注入超时：${args.joinToString(" ")}")
            return false
        }
        val code = process.exitValue()
        // input 命令失败时未必返回非零，输出里带 Error/Exception 同样算失败。
        val failed = code != 0 || output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true)
        if (failed) Log.e(TAG, "注入失败 exit=$code：${args.joinToString(" ")}，输出：$output")
        !failed
    }.getOrElse {
        Log.e(TAG, "注入出错：${args.joinToString(" ")}", it)
        false
    }
}
