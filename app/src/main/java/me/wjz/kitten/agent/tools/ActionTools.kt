package me.wjz.kitten.agent.tools

import android.view.KeyEvent
import kotlinx.coroutines.delay
import me.wjz.kitten.a11y.A11yActuator
import me.wjz.kitten.a11y.ActionResult
import me.wjz.kitten.a11y.InjectActuator
import me.wjz.kitten.a11y.ScreenError
import me.wjz.kitten.a11y.ScreenTextLine
import me.wjz.kitten.a11y.UiSnapshot
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.ScreenMark
import me.wjz.kitten.agent.ScreenMarks
import me.wjz.kitten.agent.enumParam
import me.wjz.kitten.agent.flow.labelFor
import me.wjz.kitten.agent.intParam
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.data.skills.FlowStep
import org.json.JSONObject
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * 动作工具共用的提示语和小工具。
 *
 * ## 为什么每个动作后面要等一下
 *
 * 点下去之后界面要重新布局、可能还要等网络。动作一返回就紧接着 read_screen 的话，读到的
 * 多半还是旧页面，模型会以为"点了没反应"再点一次，于是点重了。等 [SettleMillis] 是个折中：
 * 够一般的页面切换完成，又不至于每步都明显变慢。页面真的还没加载完时，模型可以再调 wait。
 *
 * ## 操作记录
 *
 * 每个动作做成了，顺手往 [DeviceSession.trace] 里记一笔（节点编号当场换成选择器），一轮办完复盘时
 * 整理成能直接重放的自动流程，见 [me.wjz.kitten.agent.flow.ActionTrace]。失败的不记。
 *
 * 点、按键、滑动的执行逻辑抽成了 [tapNode]、[pressKey]、[swipe]，流程回放（FlowRunner）用的是同一套，
 * 两边点下去的方式一模一样。
 */
private const val SettleMillis = 450L

/** 界面响应需要时间，等一下再让模型去读，见上面的说明。流程回放每一步之后也等这么久。 */
internal suspend fun settle() = delay(SettleMillis.milliseconds)

/** 动作做完统一这么回：提醒模型页面已经变了，旧的 node_id 不能再用。 */
private const val PageChangedHint = "页面可能已经变化，下一步先 read_screen 拿最新的节点编号。"

private const val NoSnapshotError =
    "ERROR no_snapshot: 还没有读过屏幕，没有可用的节点编号。先调 read_screen。"

/** 取 node_id 参数。模型偶尔会把它写成字符串，所以两种都收。 */
private fun JSONObject.nodeId(): Int? {
    if (!has("node_id")) return null
    return optInt("node_id", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        ?: optString("node_id").toIntOrNull()
}

/** 节点在快照里的说明文字，写进回执里让模型确认点对了没有。 */
private fun UiSnapshot.labelOf(nodeId: Int): String =
    findById(nodeId)?.displayLabel?.let { " \"$it\"" } ?: ""

/**
 * 点（或长按）画面上认出的一行字（见 [UiSnapshot.screenText]）：它没有节点，按坐标点它的中间，走特权进程的
 * 注入，和节点的坐标点击一样。
 *
 * 不进操作记录：没有节点，写不出选择器（见 [me.wjz.kitten.agent.flow.ActionTrace]）。
 */
private suspend fun pressScreenText(snapshot: UiSnapshot, line: ScreenTextLine, long: Boolean): String {
    val x = line.bounds.centerX()
    val y = line.bounds.centerY()
    val result = if (long) InjectActuator.longPress(snapshot.displayId, x, y) else InjectActuator.tap(snapshot.displayId, x, y)
    return when (result) {
        is ActionResult.Ok -> {
            ScreenMarks.emit(ScreenMark.Touch(snapshot.displayId, x, y, long))
            settle()
            "OK ${if (long) "长按" else "点击"}了画面上的 [${line.id}] \"${line.text}\"（按位置点的）。$PageChangedHint"
        }
        is ActionResult.Failed -> result.error.describe()
    }
}

/** 拿画面上认出的字去输入、滚动：它不是控件。 */
private fun notAControl(line: ScreenTextLine): String =
    "ERROR not_a_control: [${line.id}] 是从画面上认出的字，不是控件，只能 tap 或 long_press。" +
        "输入、滚动要用节点树里控件的编号。"

/** 点一个节点的结果：点成了没有，是不是退回了坐标点击。 */
internal class TapOutcome(val result: ActionResult, val byCoordinate: Boolean)

/**
 * 点一个节点：先走无障碍的 ACTION_CLICK（按节点，页面变了会明确失败而不是点歪）；控件不认这个
 * 动作时（自绘控件很常见）退回按节点中心的坐标点击。两条通道的差别见 [InjectActuator] 的类注释。
 *
 * 只有"控件不认这个动作"才换条路；节点失效、被禁用都原样返回，该如实告诉模型。
 */
internal fun tapNode(snapshot: UiSnapshot, nodeId: Int): TapOutcome {
    val result = A11yActuator.click(snapshot, nodeId)
    if (result !is ActionResult.Failed || result.error !is ScreenError.ActionRejected) {
        return TapOutcome(result, byCoordinate = false)
    }
    val bounds = (snapshot.findById(nodeId) ?: snapshot.findRawById(nodeId))?.bounds
        ?: return TapOutcome(ActionResult.Failed(ScreenError.NodeGone(nodeId)), byCoordinate = false)
    val byCoordinate = InjectActuator.tap(snapshot.displayId, bounds.centerX(), bounds.centerY())
    return TapOutcome(byCoordinate, byCoordinate = true)
}

/** 按坐标滑一下，走特权进程的注入（指定了屏，副屏上也管用）。滑成了在直播画面上画一道。 */
internal fun swipe(displayId: Int, fromX: Int, fromY: Int, toX: Int, toY: Int, durationMs: Int): ActionResult =
    InjectActuator.swipe(displayId, fromX, fromY, toX, toY, durationMs).also {
        if (it is ActionResult.Ok) ScreenMarks.emit(ScreenMark.Slide(displayId, fromX, fromY, toX, toY))
    }

/**
 * 在直播画面上标出刚动过的节点：在它中间泛一圈（见 [ScreenMarks]）。点、长按、填字都用它。节点在快照里
 * 找不到就不标。
 */
internal fun markTouch(snapshot: UiSnapshot, nodeId: Int, long: Boolean = false) {
    val bounds = (snapshot.findById(nodeId) ?: snapshot.findRawById(nodeId))?.bounds ?: return
    ScreenMarks.emit(ScreenMark.Touch(snapshot.displayId, bounds.centerX(), bounds.centerY(), long))
}

/**
 * 滚动是无障碍的滚动动作，没有手指的轨迹。在容器中间画一道和手指方向一样的短线：往后翻是手指往上推。
 */
internal fun markScroll(snapshot: UiSnapshot, nodeId: Int, forward: Boolean) {
    val bounds = (snapshot.findById(nodeId) ?: snapshot.findRawById(nodeId))?.bounds ?: return
    val low = bounds.top + (bounds.height() * (1 - ScrollMarkSpan) / 2).roundToInt()
    val high = bounds.top + (bounds.height() * (1 + ScrollMarkSpan) / 2).roundToInt()
    val (from, to) = if (forward) high to low else low to high
    ScreenMarks.emit(ScreenMark.Slide(snapshot.displayId, bounds.centerX(), from, bounds.centerX(), to))
}

/** 滚动那道短线占容器高度的几成：太短看不出方向，太长像是滑了一整屏。 */
private const val ScrollMarkSpan = 0.4f

/** 按键的结果。 */
internal sealed interface KeyOutcome {
    /** 按成了。 */
    data object Done : KeyOutcome

    /** 没按成，[message] 是给模型看的那句。 */
    class Failed(val message: String) : KeyOutcome
}

/**
 * 发一个按键：back、home、enter、delete。
 *
 * 走特权进程的注入，指定发到哪块屏（见 [InjectActuator.keyEvent]）。无障碍的 `performGlobalAction`
 * 没有 displayId 参数，只作用于用户眼前那块屏，副屏上的应用收不到，所以不用它。
 */
internal fun pressKey(displayId: Int, key: String): KeyOutcome {
    val keyCode = when (key) {
        "back" -> KeyEvent.KEYCODE_BACK
        "home" -> KeyEvent.KEYCODE_HOME
        "enter" -> KeyEvent.KEYCODE_ENTER
        "delete" -> KeyEvent.KEYCODE_DEL
        else -> return KeyOutcome.Failed("ERROR bad_args: 不认识的按键 \"$key\"，只能是 back / home / enter / delete。")
    }
    return when (val result = InjectActuator.keyEvent(displayId, keyCode)) {
        is ActionResult.Ok -> KeyOutcome.Done
        is ActionResult.Failed -> KeyOutcome.Failed(result.error.describe())
    }
}

/** 点一个节点，见 [tapNode]。 */
class TapTool(private val session: DeviceSession) : AgentTool {

    override val name = "tap"

    override val description =
        "点击一个界面元素。node_id 来自最近一次 read_screen，画面上认出的字的编号也行（按位置点它的中间）。" +
            "控件不支持标准点击动作时会自动改用坐标点击，不需要你处理。"

    override val parameters = objectSchema(
        "node_id" to intParam("要点击的元素编号，来自最近一次 read_screen"),
        required = listOf("node_id"),
    )

    override suspend fun execute(args: JSONObject): String {
        val snapshot = session.lastSnapshot ?: return NoSnapshotError
        val nodeId = args.nodeId() ?: return "ERROR bad_args: 没有给 node_id。"
        snapshot.screenTextLine(nodeId)?.let { return pressScreenText(snapshot, it, long = false) }
        val label = snapshot.labelOf(nodeId)

        val outcome = tapNode(snapshot, nodeId)
        return when (val result = outcome.result) {
            is ActionResult.Ok -> {
                session.trace.recordNode(snapshot, nodeId) { FlowStep.Tap(it, snapshot.labelFor(nodeId)) }
                markTouch(snapshot, nodeId)
                settle()
                if (outcome.byCoordinate) {
                    "OK [$nodeId]$label 不支持标准点击动作，已改用坐标点击。$PageChangedHint"
                } else {
                    "OK 点击了 [$nodeId]$label。$PageChangedHint"
                }
            }
            is ActionResult.Failed -> result.error.describe()
        }
    }
}

/** 长按一个节点，比如呼出"复制 / 删除"这类菜单。 */
class LongPressTool(private val session: DeviceSession) : AgentTool {

    override val name = "long_press"

    override val description =
        "长按一个界面元素，常用来呼出上下文菜单。node_id 来自最近一次 read_screen，画面上认出的字的编号也行。"

    override val parameters = objectSchema(
        "node_id" to intParam("要长按的元素编号"),
        required = listOf("node_id"),
    )

    override suspend fun execute(args: JSONObject): String {
        val snapshot = session.lastSnapshot ?: return NoSnapshotError
        val nodeId = args.nodeId() ?: return "ERROR bad_args: 没有给 node_id。"
        snapshot.screenTextLine(nodeId)?.let { return pressScreenText(snapshot, it, long = true) }
        val label = snapshot.labelOf(nodeId)
        return when (val result = A11yActuator.longClick(snapshot, nodeId)) {
            is ActionResult.Ok -> {
                session.trace.recordNode(snapshot, nodeId) { FlowStep.LongPress(it, snapshot.labelFor(nodeId)) }
                markTouch(snapshot, nodeId, long = true)
                settle()
                "OK 长按了 [$nodeId]$label。$PageChangedHint"
            }
            is ActionResult.Failed -> result.error.describe()
        }
    }
}

/** 往输入框里写字。 */
class InputTextTool(private val session: DeviceSession) : AgentTool {

    override val name = "input_text"

    override val description =
        "把文字写进一个输入框（节点带 editable 标记的那种）。" +
            "这是整段替换，不是在原有内容后面追加。写完通常还要点一下\"搜索\"或\"发送\"。"

    override val parameters = objectSchema(
        "node_id" to intParam("输入框的元素编号"),
        "text" to stringParam("要写进去的内容"),
        required = listOf("node_id", "text"),
    )

    override suspend fun execute(args: JSONObject): String {
        val snapshot = session.lastSnapshot ?: return NoSnapshotError
        val nodeId = args.nodeId() ?: return "ERROR bad_args: 没有给 node_id。"
        snapshot.screenTextLine(nodeId)?.let { return notAControl(it) }
        if (!args.has("text")) return "ERROR bad_args: 没有给 text。"
        val text = args.optString("text")
        val label = snapshot.labelOf(nodeId)
        return when (val result = A11yActuator.setText(snapshot, nodeId, text)) {
            is ActionResult.Ok -> {
                // 密码框里的也记，描述里藏起来，见 ActionTrace.recordNode。
                session.trace.recordNode(snapshot, nodeId) { FlowStep.Input(it, text, snapshot.labelFor(nodeId)) }
                markTouch(snapshot, nodeId)
                settle()
                "OK 已把内容写进 [$nodeId]$label。$PageChangedHint"
            }
            is ActionResult.Failed -> result.error.describe()
        }
    }
}

/** 滚动一个可滚动的容器（列表、网页）。 */
class ScrollTool(private val session: DeviceSession) : AgentTool {

    override val name = "scroll"

    override val description =
        "滚动一个可滚动的容器（节点带 scrollable 标记的那种），用来翻看列表的后面几屏。" +
            "一次滚大约一屏。找不到可滚动节点时改用 swipe。"

    override val parameters = objectSchema(
        "node_id" to intParam("可滚动容器的元素编号"),
        "direction" to enumParam("forward 往下/往后翻，backward 往上/往前翻", listOf("forward", "backward")),
        required = listOf("node_id"),
    )

    override suspend fun execute(args: JSONObject): String {
        val snapshot = session.lastSnapshot ?: return NoSnapshotError
        val nodeId = args.nodeId() ?: return "ERROR bad_args: 没有给 node_id。"
        snapshot.screenTextLine(nodeId)?.let { return notAControl(it) }
        val forward = args.optString("direction").lowercase() != "backward"
        val label = snapshot.labelOf(nodeId)
        return when (val result = A11yActuator.scroll(snapshot, nodeId, forward)) {
            is ActionResult.Ok -> {
                session.trace.recordNode(snapshot, nodeId) { FlowStep.Scroll(it, forward, snapshot.labelFor(nodeId)) }
                markScroll(snapshot, nodeId, forward)
                settle()
                "OK 把 [$nodeId]$label 往${if (forward) "后" else "前"}滚了一屏。$PageChangedHint"
            }
            is ActionResult.Failed -> result.error.describe()
        }
    }
}

/** 按坐标滑动。[ScrollTool] 找不到可滚动节点时的兜底，也用来做侧滑返回这类手势。 */
class SwipeTool(private val session: DeviceSession) : AgentTool {

    override val name = "swipe"

    override val description =
        "在屏幕上从一点滑到另一点。坐标是像素，可以照着 read_screen 里节点的 bounds 估算。" +
            "优先用 scroll 翻列表，只有找不到可滚动节点、或者要做侧滑这类手势时才用它。"

    override val parameters = objectSchema(
        "from_x" to intParam("起点横坐标（像素）"),
        "from_y" to intParam("起点纵坐标（像素）"),
        "to_x" to intParam("终点横坐标（像素）"),
        "to_y" to intParam("终点纵坐标（像素）"),
        "duration_ms" to intParam(
            "滑动时长，毫秒。默认 300。低于 100 会被当成甩动，列表会一路滚到停不下来。",
        ),
        required = listOf("from_x", "from_y", "to_x", "to_y"),
    )

    override suspend fun execute(args: JSONObject): String {
        val fromX = args.optInt("from_x", -1)
        val fromY = args.optInt("from_y", -1)
        val toX = args.optInt("to_x", -1)
        val toY = args.optInt("to_y", -1)
        if (fromX < 0 || fromY < 0 || toX < 0 || toY < 0) {
            return "ERROR bad_args: 四个坐标都要给，而且不能是负数。"
        }
        val duration = args.optInt("duration_ms", DefaultSwipeMillis).coerceAtLeast(MinSwipeMillis)
        return when (val result = swipe(session.displayId, fromX, fromY, toX, toY, duration)) {
            is ActionResult.Ok -> {
                session.trace.record(FlowStep.Swipe(fromX, fromY, toX, toY, duration), session.lastSnapshot?.packageName)
                settle()
                "OK 从 ($fromX,$fromY) 滑到了 ($toX,$toY)。$PageChangedHint"
            }
            is ActionResult.Failed -> result.error.describe()
        }
    }

    private companion object {
        const val DefaultSwipeMillis = 300
        const val MinSwipeMillis = 100
    }
}

/** 发一个按键：返回、Home、回车、退格，见 [pressKey]。 */
class PressKeyTool(private val session: DeviceSession) : AgentTool {

    override val name = "press_key"

    override val description =
        "按一个系统按键。back 返回上一页（最常用），home 回桌面，enter 确认/发送，delete 退格。"

    override val parameters = objectSchema(
        "key" to enumParam("要按哪个键", listOf("back", "home", "enter", "delete")),
        required = listOf("key"),
    )

    override suspend fun execute(args: JSONObject): String {
        val key = args.optString("key").lowercase()
        return when (val outcome = pressKey(session.displayId, key)) {
            is KeyOutcome.Failed -> outcome.message
            is KeyOutcome.Done -> {
                session.trace.record(FlowStep.Key(key), session.lastSnapshot?.packageName)
                settle()
                "OK 按了 $key。$PageChangedHint"
            }
        }
    }
}

/** 单纯等一会儿，给页面加载、动画播放留时间。 */
class WaitTool(private val session: DeviceSession) : AgentTool {

    override val name = "wait"

    override val description =
        "等待一段时间，用在页面还在加载、动画还没播完的时候。等完再 read_screen 看看好了没有。"

    override val parameters = objectSchema(
        "seconds" to intParam("等几秒，1 到 $MaxWaitSeconds"),
        required = listOf("seconds"),
    )

    override suspend fun execute(args: JSONObject): String {
        val seconds = args.optInt("seconds", 1).coerceIn(1, MaxWaitSeconds)
        delay((seconds * 1000L).milliseconds)
        // 记下来：重放时页面一样要等这么久才加载好。
        session.trace.record(FlowStep.Wait(seconds), session.lastSnapshot?.packageName)
        return "OK 等了 $seconds 秒。"
    }

    private companion object {
        /** 一次最多等多久。等太久不如把这一步拆开，也免得整轮任务卡在这里。 */
        const val MaxWaitSeconds = 10
    }
}
