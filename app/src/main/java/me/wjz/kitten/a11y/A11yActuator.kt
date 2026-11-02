package me.wjz.kitten.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.dianming.phoneapp.MyAccessibilityService

/**
 * 动作层的无障碍通道：**按节点操作**（[click]、[setText]、[scroll] 等），走 `performAction`。
 *
 * 它不产生触摸事件，而是直接让目标 View 执行对应的回调。最精确——不用算坐标，也不怕被别的
 * 窗口遮住。缺点是很多自绘控件压根没实现 `ACTION_CLICK`，这时 `performAction` 返回 false，
 * 得退回坐标。
 *
 * ## 按坐标操作不在这里
 *
 * 坐标点击、滑动、按键都走特权进程的注入通道（[InjectActuator]）。无障碍自己的 `dispatchGesture`
 * 和全局动作以前留作"没有特权进程时"的退路，增强模式必开之后删掉了：全局动作没有 displayId，
 * 只作用于用户眼前那块屏，副屏上的应用收不到；手势还带着 `FLAG_IS_ACCESSIBILITY_EVENT`(0x800)，
 * 有的应用认得出来。
 *
 * ## 都要在工作线程调用
 *
 * `performAction` 是阻塞的跨进程调用。
 */
object A11yActuator {

    private const val TAG = "KittenA11yAct"

    private val service: AccessibilityService?
        get() = MyAccessibilityService.peek()

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
}
