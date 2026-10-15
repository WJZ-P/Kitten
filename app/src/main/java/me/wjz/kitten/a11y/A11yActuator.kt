package me.wjz.kitten.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import me.wjz.kitten.service.KittenAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 动作层的无障碍通道。
 *
 * 两类能力，层次不同：
 *
 * - **按节点操作**（[click]、[setText]、[scroll] 等）走 `performAction`。它不产生触摸
 *   事件，而是直接让目标 View 执行对应的回调。最精确——不用算坐标，也不怕被别的窗口
 *   遮住。缺点是很多自绘控件压根没实现 `ACTION_CLICK`，这时 `performAction` 返回
 *   false，得退回坐标。
 * - **按坐标操作**（[tapAt]、[swipeAt]）走 `dispatchGesture`，会生成真实的 MotionEvent。
 *
 * ## 和注入通道的分工
 *
 * `dispatchGesture` 派发的事件带 `MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT`(0x800)，
 * 应用用公开的 `getFlags()` 就能认出"这是无障碍生成的"。要绕开这个标记只能走特权进程
 * 的注入通道（`InputInjector`），那条路直接送进 InputDispatcher，但需要
 * `INJECT_EVENTS`（纯 signature 权限，App 进程拿不到）。
 *
 * 两条通道都保留，让上层按目标应用的脾气挑，这里不替它决定。
 *
 * ## 都要在工作线程调用
 *
 * `performAction` 是阻塞的跨进程调用；[tapAt] / [swipeAt] 还会等手势派发完成。
 */
object A11yActuator {

    private const val TAG = "KittenA11yAct"

    /** 手势派发的等待上限。超时按失败处理，免得 Agent 卡在一次点击上。 */
    private const val GESTURE_TIMEOUT_MS = 5_000L

    /** 一次点击的按压时长。太短有些应用会当成误触丢掉。 */
    private const val TAP_DURATION_MS = 60L

    /** 长按时长。系统的判定阈值是 500ms，留点余量。 */
    const val LONG_PRESS_DURATION_MS = 600L

    private val service: AccessibilityService?
        get() = KittenAccessibilityService.peek()

    // ---- 按节点操作 ----

    /** 点击节点。 */
    fun click(snapshot: UiSnapshot, nodeId: Int): ActionResult =
        perform(snapshot, nodeId, AccessibilityNodeInfo.ACTION_CLICK, "click")

    /** 长按节点。 */
    fun longClick(snapshot: UiSnapshot, nodeId: Int): ActionResult =
        perform(snapshot, nodeId, AccessibilityNodeInfo.ACTION_LONG_CLICK, "longClick")

    /**
     * 往输入框里写文字。
     *
     * 这是**整体替换**，不是追加——想追加得自己把原文拼进去。相比模拟逐字输入，它不依赖
     * 输入法、不会被联想词干扰，中文和 emoji 也不会出问题。
     */
    fun setText(snapshot: UiSnapshot, nodeId: Int, text: String): ActionResult {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return perform(snapshot, nodeId, AccessibilityNodeInfo.ACTION_SET_TEXT, "setText", args)
    }

    /**
     * 滚动节点。
     *
     * 目标得是可滚动的容器（`scrollable`），不是它里面的某个列表项。紧凑输出里带
     * `scroll` 标志的那个就是。
     */
    fun scroll(snapshot: UiSnapshot, nodeId: Int, forward: Boolean): ActionResult {
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        return perform(snapshot, nodeId, action, if (forward) "scrollForward" else "scrollBackward")
    }

    /**
     * 执行一个节点动作，顺带把失败归因清楚。
     *
     * 三种失败对模型意味着不同的下一步，所以不能都返回"失败了"：
     * 节点已经不在（页面变了，该重抓）、节点禁用（该先满足启用条件）、
     * 控件拒绝（不支持这个动作，该退回坐标点击）。
     */
    private fun perform(
        snapshot: UiSnapshot,
        nodeId: Int,
        action: Int,
        name: String,
        args: Bundle? = null,
    ): ActionResult {
        if (service == null) return ActionResult.Failed(ScreenError.ServiceUnavailable)
        val handle = snapshot.handleOf(nodeId)
            ?: return ActionResult.Failed(ScreenError.NodeGone(nodeId))

        // 节点可能在快照之后失效，refresh 会同步最新状态，顺便告诉我们它还在不在。
        val alive = runCatching { handle.refresh() }.getOrDefault(false)
        if (!alive) {
            Log.w(TAG, "$name 失败：节点 [$nodeId] 已失效")
            return ActionResult.Failed(ScreenError.NodeGone(nodeId))
        }
        if (!handle.isEnabled) {
            val label = snapshot.findById(nodeId)?.displayLabel
            Log.w(TAG, "$name 失败：节点 [$nodeId] 不可用")
            return ActionResult.Failed(ScreenError.NodeDisabled(nodeId, label))
        }

        val ok = runCatching { handle.performAction(action, args) }
            .onFailure { Log.e(TAG, "$name 执行出错：节点 [$nodeId]", it) }
            .getOrDefault(false)
        return if (ok) {
            Log.i(TAG, "$name 成功：节点 [$nodeId]")
            ActionResult.Ok
        } else {
            Log.w(TAG, "$name 被拒：节点 [$nodeId] 可能不支持这个动作")
            ActionResult.Failed(ScreenError.ActionRejected(nodeId, name))
        }
    }

    // ---- 按坐标操作 ----

    /** 在指定屏幕的坐标上点一下。 */
    fun tapAt(displayId: Int, x: Float, y: Float): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(displayId, path, TAP_DURATION_MS, "tapAt($x,$y)")
    }

    /** 长按某个坐标。 */
    fun longPressAt(
        displayId: Int,
        x: Float,
        y: Float,
        durationMs: Long = LONG_PRESS_DURATION_MS,
    ): ActionResult {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(displayId, path, durationMs, "longPressAt($x,$y)")
    }

    /**
     * 从一点滑到另一点。
     *
     * [durationMs] 决定快慢：太短（<100ms）会被识别成甩动（fling），列表会一直滚到
     * 停不下来；想精确滚一屏就给 300ms 以上。
     */
    fun swipeAt(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 300L,
    ): ActionResult {
        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }
        return dispatch(displayId, path, durationMs, "swipeAt($fromX,$fromY→$toX,$toY)")
    }

    /**
     * 派发一次手势并等它完成。
     *
     * `dispatchGesture` 是异步的，回调可能在别的线程。这里用 CountDownLatch 等结果——
     * 对 Agent 来说"点完了没"必须是确定的，否则下一步抓取会读到动作执行途中的中间状态。
     */
    private fun dispatch(displayId: Int, path: Path, durationMs: Long, what: String): ActionResult {
        val service = service ?: return ActionResult.Failed(ScreenError.ServiceUnavailable)
        return runCatching {
            val gesture = GestureDescription.Builder()
                // 不设 displayId 的话手势只会落在主屏——副屏是 Kitten 的主场，必须指定。
                .setDisplayId(displayId)
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val latch = CountDownLatch(1)
            var completed = false
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        completed = true
                        latch.countDown()
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        completed = false
                        latch.countDown()
                    }
                },
                null,
            )
            if (!dispatched) {
                Log.w(TAG, "$what 派发失败，display=$displayId 可能不可用")
                return ActionResult.Failed(ScreenError.DisplayGone(displayId))
            }
            if (!latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "$what 超时未返回")
                return ActionResult.Failed(ScreenError.GestureFailed(what, "超时"))
            }
            if (completed) {
                Log.i(TAG, "$what 成功 @display=$displayId")
                ActionResult.Ok
            } else {
                Log.w(TAG, "$what 被取消")
                ActionResult.Failed(ScreenError.GestureFailed(what, "被系统取消"))
            }
        }.getOrElse {
            Log.e(TAG, "$what 执行出错", it)
            ActionResult.Failed(ScreenError.GestureFailed(what, it.message ?: it.javaClass.simpleName))
        }
    }

    // ---- 全局动作 ----

    /**
     * 返回键。
     *
     * **注意它没有 displayId 参数**——`performGlobalAction` 作用于当前有焦点的那块屏，
     * 副屏上的应用未必吃得到。要在副屏上回退，用注入通道发 KEYCODE_BACK 并指定 display。
     */
    fun back(): ActionResult = globalAction(AccessibilityService.GLOBAL_ACTION_BACK, "back")

    /** 回桌面。同样只作用于当前焦点屏，见 [back] 的说明。 */
    fun home(): ActionResult = globalAction(AccessibilityService.GLOBAL_ACTION_HOME, "home")

    private fun globalAction(action: Int, name: String): ActionResult {
        val service = service ?: return ActionResult.Failed(ScreenError.ServiceUnavailable)
        val ok = runCatching { service.performGlobalAction(action) }
            .onFailure { Log.e(TAG, "$name 执行出错", it) }
            .getOrDefault(false)
        return if (ok) {
            Log.i(TAG, "$name 成功")
            ActionResult.Ok
        } else {
            Log.w(TAG, "$name 失败")
            ActionResult.Failed(ScreenError.GestureFailed(name, "系统拒绝了全局动作"))
        }
    }
}
