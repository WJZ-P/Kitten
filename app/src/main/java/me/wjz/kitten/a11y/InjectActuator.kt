package me.wjz.kitten.a11y

import android.util.Log
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.ipc.IPrivilegeService

/**
 * 动作层的注入通道：绕过无障碍，直接把输入事件送进系统。
 *
 * ## 和 [A11yActuator] 的分工
 *
 * 这两条通道不是「主用」和「备用」的关系，各有各的适用面：
 *
 * | | [A11yActuator] | 本通道 |
 * |---|---|---|
 * | 作用对象 | 节点（也支持坐标） | 只有坐标 |
 * | 需要 | 无障碍服务 | **特权进程**（shell 身份） |
 * | 事件标记 | 带 `FLAG_IS_ACCESSIBILITY_EVENT`(0x800) | 不带 |
 * | 精确度 | 按节点操作不怕遮挡、不用算坐标 | 纯坐标，可能点到遮挡物 |
 *
 * 按节点操作能用就优先用它，省去算坐标、也不怕目标被盖住。走这条通道通常是因为：
 * 控件不实现 `ACTION_CLICK`（`performAction` 返回 false），或者目标应用会检查
 * `0x800` 标记。
 *
 * ## 为什么必须经过特权进程
 *
 * `INJECT_EVENTS` 的 protectionLevel 是**纯 signature**，不带 `development` 标志，
 * 所以 `pm grant` 也授不了，App 进程永远拿不到；而 `com.android.shell` 本身持有它。
 * 所以这里的每个方法都是一次 AIDL 调用，特权进程没起来就只能失败。
 *
 * ## 都要在工作线程调用
 *
 * 每个方法都是阻塞的 binder 调用，特权进程那端还要等事件派发完成
 * （用的是 `WAIT_FOR_FINISHED` 模式——Agent 必须知道这一下到底点下去没有，
 * 否则紧接着的抓取会读到动作执行途中的中间状态）。
 */
object InjectActuator {

    private const val TAG = "KittenInjectAct"

    /** 长按时长。系统判定阈值是 500ms，留点余量。 */
    const val LONG_PRESS_DURATION_MS = 600

    /** 滑动默认时长。低于 100ms 会被识别成甩动（fling），列表会一路滚到停不下来。 */
    const val DEFAULT_SWIPE_DURATION_MS = 300

    val isAvailable: Boolean
        get() = PrivilegeManager.get() != null

    /** 在指定屏幕上点一下。 */
    fun tap(displayId: Int, x: Int, y: Int): ActionResult =
        call("tap($x,$y)") { it.injectTap(displayId, x, y) }

    /**
     * 点节点的中心。
     *
     * 坐标取自快照里的 bounds，所以**页面变了就会点错地方**——调用前该确认快照还新鲜。
     * 相比之下 [A11yActuator.click] 按节点操作，页面变了会明确失败而不是点歪。
     */
    fun tapNode(snapshot: UiSnapshot, nodeId: Int): ActionResult {
        val node = snapshot.findById(nodeId)
            ?: return ActionResult.Failed(ScreenError.NodeGone(nodeId))
        val center = node.bounds
        return tap(snapshot.displayId, center.centerX(), center.centerY())
    }

    /** 长按坐标。 */
    fun longPress(
        displayId: Int,
        x: Int,
        y: Int,
        durationMs: Int = LONG_PRESS_DURATION_MS,
    ): ActionResult = call("longPress($x,$y)") { it.injectLongPress(displayId, x, y, durationMs) }

    /** 从一点滑到另一点。[durationMs] 见 [DEFAULT_SWIPE_DURATION_MS] 的说明。 */
    fun swipe(
        displayId: Int,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Int = DEFAULT_SWIPE_DURATION_MS,
    ): ActionResult = call("swipe($fromX,$fromY→$toX,$toY)") {
        it.injectSwipe(displayId, fromX, fromY, toX, toY, durationMs)
    }

    /**
     * 发一个按键事件，keyCode 取 `KeyEvent.KEYCODE_*`。
     *
     * 这是副屏回退的**唯一**可靠办法：`AccessibilityService.performGlobalAction` 没有
     * displayId 参数，只作用于当前焦点屏，副屏上的应用未必吃得到。
     */
    fun keyEvent(displayId: Int, keyCode: Int): ActionResult =
        call("keyEvent($keyCode)") { it.injectKeyEvent(displayId, keyCode) }

    private inline fun call(
        what: String,
        action: (IPrivilegeService) -> Boolean,
    ): ActionResult {
        val service = PrivilegeManager.get() ?: run {
            Log.w(TAG, "$what 失败：特权进程未就绪")
            return ActionResult.Failed(ScreenError.PrivilegeUnavailable)
        }
        val ok = runCatching { action(service) }
            .onFailure { Log.e(TAG, "$what 执行出错", it) }
            .getOrDefault(false)
        return if (ok) {
            Log.i(TAG, "$what 成功")
            ActionResult.Ok
        } else {
            Log.w(TAG, "$what 失败")
            ActionResult.Failed(ScreenError.GestureFailed(what, "注入未成功"))
        }
    }
}
