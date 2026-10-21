package me.wjz.kitten.ui.component

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.tanh

/**
 * 按钮类控件的按压进度。
 *
 * Kitten 所有控件遵守同一条交互约定：**动效跟手，松手才算数。**
 *
 * - 手指落下的那一帧就有反馈（[pressProgress] 开始涨），不等 touch slop，也不等抬手；
 * - 手指滑出控件，反馈收回；滑回来，反馈再起——视觉始终反映"现在松手会不会生效"；
 * - 只有在控件范围内抬手才触发 onClick；被外层滚动接管则什么都不发生。
 *
 * 这和 Material 的 `clickable` 看起来像，差别在于这里把"按下程度"和"手指在哪"作为连续的
 * 数值交出去，组件拿它去驱动光雾（[GlassGlow]）和玻璃的形变，而不只是叠一层水波纹。
 */
@Stable
class GlassPressState internal constructor(private val scope: CoroutineScope) {

    private val progress = Animatable(0f, 0.001f)

    /**
     * 按下时迅速涨到 1，松开时带回弹落回 0。
     *
     * 回弹意味着它会**短暂略低于 0**：拿去驱动缩放正合适（松手时轻轻弹一下），拿去当
     * 透明度或光雾亮度时要自己 `coerceIn(0f, 1f)`。
     */
    val pressProgress: Float get() = progress.value

    /** 手指在控件里的位置（控件自身坐标），给光雾定位用。还没按过时是 Unspecified。 */
    var position by mutableStateOf(Offset.Unspecified)
        internal set

    /** 最近一次手指落下的位置（控件自身坐标）。还没按过时是 Unspecified。 */
    var downPosition by mutableStateOf(Offset.Unspecified)
        internal set

    /**
     * 手指现在是不是按着、并且还在范围里（现在松手算不算数）。和 [pressProgress] 不同，它是
     * 立刻翻转的，不带动画：组件要自己编排按下、松开的动画时看它。
     */
    var isPressed by mutableStateOf(false)
        private set

    private val drag = Animatable(Offset.Zero, Offset.VectorConverter)

    /**
     * 按住后拖了多远：手指当前位置减去落下的位置。只在 [detectGlassPress] 打开了 followDrag
     * 时才有值；拖动时紧跟手指，手指滑出范围或者松手后带回弹落回零。组件拿它去驱动跟手的
     * 形变（[dragStretch]）。
     */
    val dragOffset: Offset get() = drag.value

    internal fun press() {
        isPressed = true
        scope.launch { progress.animateTo(1f, PressInSpec) }
    }

    internal fun release() {
        isPressed = false
        scope.launch { progress.animateTo(0f, PressOutSpec) }
    }

    internal fun dragTo(offset: Offset) {
        // 用很硬的弹簧追，不直接跳过去：每来一次移动就换一个目标，接着当前的速度追，跟手又不
        // 生硬。手指滑出去再滑回来时，形变也是追回来的，不会一下子跳到拉满。
        scope.launch { drag.animateTo(offset, DragFollowSpec) }
    }

    internal fun releaseDrag() {
        scope.launch { drag.animateTo(Offset.Zero, DragReleaseSpec) }
    }
}

/**
 * 按压变形的进度 0..1，给"按下鼓起、变成透镜，松手再磨砂回去"这类效果用（见 [GlassToggleButton]、
 * [GlassButton]）。
 *
 * 和 [GlassPressState.pressProgress] 的区别：快速点一下时按压态一闪就过，变形来不及播完。这条
 * 动画按下就涨，松手时先涨满、在最鼓处停够 [minMillis]，再落回去，再快的点按也能看到完整一轮。
 *
 * @param inSpec 鼓起的动画。
 * @param outSpec 收回的动画。带过冲的话会略冲过 0，拿去驱动缩放正好弹一下，驱动透明度、模糊时
 *   自己 coerceIn(0f, 1f)。
 */
@Composable
internal fun rememberPressMorph(
    press: GlassPressState,
    minMillis: Long,
    inSpec: AnimationSpec<Float>,
    outSpec: AnimationSpec<Float>,
): Animatable<Float, AnimationVector1D> {
    val morph = remember { Animatable(0f) }
    val currentInSpec by rememberUpdatedState(inSpec)
    val currentOutSpec by rememberUpdatedState(outSpec)
    LaunchedEffect(press, morph, minMillis) {
        var pressedAt = 0L
        snapshotFlow { press.isPressed }.collectLatest { pressed ->
            if (pressed) {
                pressedAt = SystemClock.uptimeMillis()
                morph.animateTo(1f, currentInSpec)
            } else if (pressedAt != 0L) {
                // 快速点按时鼓起还没放完：先放完，在最鼓处停够时间再收回。
                morph.animateTo(1f, currentInSpec)
                val held = SystemClock.uptimeMillis() - pressedAt
                if (held < minMillis) delay(minMillis - held)
                morph.animateTo(0f, currentOutSpec)
            }
        }
    }
    return morph
}

@Composable
fun rememberGlassPressState(): GlassPressState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassPressState(scope) }
}

/**
 * 在 `pointerInput` 里调用：按下驱动 [state]，在控件范围内松手才调用 [onClick]。
 *
 * [onClick] 只在构造时被捕获一次，调用方要用 `rememberUpdatedState` 包一层再传进来，
 * 否则重组后换了新的回调这里也看不到。
 *
 * 按下事件一拿到就消费掉，和 Compose 的 `clickable` 一样。这是给外层看的记号：玻璃卡片这类
 * 本身也响应按压的容器只接"没被消费"的按下，按在卡片里的按钮上时卡片就不会跟着一起动。
 * 外层的滚动不受影响——滚动认按下时不管它有没有被消费过。
 *
 * @param onDown 手指落下的那一刻，参数是按下的位置。"按下就开始"的效果（比如把卡片里的
 *   光雾冲开）从这里起。
 * @param followDrag 按住拖动时把手指的位移交给 [GlassPressState.dragOffset]，组件拿它做跟手
 *   的形变。只是跟着，**不接管手势**：按钮多半在能滚的页面里，从按钮上开始往上划，页面照样
 *   滚，滚动一接管，形变就弹回去。拖动之后在范围里抬手照样算点击。
 *
 *   打开它时"滑出范围"的判定放宽到 [DragExitTolerance]：按钮小，只放宽一个 touch slop 的话，
 *   刚拖出一点形变就弹回去了，看不出在跟手。iOS 的按钮也是滑出一段距离才算出去。
 */
suspend fun PointerInputScope.detectGlassPress(
    state: GlassPressState,
    onDown: (Offset) -> Unit = {},
    followDrag: Boolean = false,
    onClick: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown().also { it.consume() }
        state.position = down.position
        state.downPosition = down.position
        onDown(down.position)
        state.press()
        var committed = false
        try {
            committed = trackPress(down.id, down.position, state, followDrag)
        } finally {
            // 手势协程被取消（比如控件被移出组合）时也要收回按压态，否则会一直鼓着。
            state.release()
            if (followDrag) state.releaseDrag()
        }
        if (committed) onClick()
    }
}

/** 返回 true 表示在范围内抬手；手指滑出后抬手、或者被外层接管，都返回 false。 */
private suspend fun AwaitPointerEventScope.trackPress(
    pointerId: PointerId,
    downPosition: Offset,
    state: GlassPressState,
    followDrag: Boolean,
): Boolean {
    // 允许手指稍微滑出边界——指头是一块面，不是一个点，贴着边抬起来不该算"滑出去了"。
    // 跟手形变时放得更宽，见 detectGlassPress 的 followDrag。
    val tolerance = if (followDrag) DragExitTolerance.toPx() else viewConfiguration.touchSlop
    var inside = true
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.changedToUpIgnoreConsumed()) return inside && !change.isConsumed
        if (change.isConsumed) return false
        state.position = change.position

        val nowInside = change.position.isWithin(size.width.toFloat(), size.height.toFloat(), tolerance)
        if (nowInside != inside) {
            inside = nowInside
            if (inside) state.press() else state.release()
        }
        // 形变和按压态一起表示"现在松手算不算数"：在范围里就跟着手指，滑出去就弹回。
        if (followDrag) {
            if (inside) state.dragTo(change.position - downPosition) else state.releaseDrag()
        }

        // 外层滚动容器在 Main 阶段比子节点晚处理，要到 Final 阶段才看得出它有没有接管。
        val finalPass = awaitPointerEvent(PointerEventPass.Final)
        if (finalPass.changes.any { it.id == pointerId && it.isConsumed }) return false
    }
}

private fun Offset.isWithin(width: Float, height: Float, slop: Float): Boolean =
    x >= -slop && x <= width + slop && y >= -slop && y <= height + slop

/** 按下要快：手指落下后 100ms 内就该看到反馈。 */
private val PressInSpec = spring<Float>(dampingRatio = 1f, stiffness = 1200f)

/** 松开带一点过冲，像液体被放开后弹了一下。 */
private val PressOutSpec = spring<Float>(dampingRatio = 0.55f, stiffness = 400f)

/** 拖动后松手，形变弹回原样：比按压回弹更软、晃得更明显，像一块果冻被放开。 */
private val DragReleaseSpec = spring(dampingRatio = 0.45f, stiffness = 300f, visibilityThreshold = Offset(0.5f, 0.5f))

/** 拖动时形变追手指的弹簧：很硬、不过冲，看起来是紧跟着的，只是不生硬。 */
private val DragFollowSpec = spring(dampingRatio = 1f, stiffness = 2000f, visibilityThreshold = Offset(0.5f, 0.5f))

/** 跟手形变时，手指滑出边界多远才算"滑出去了"。 */
private val DragExitTolerance = 40.dp

/**
 * 按住拖动时的形变参数，见 [dragStretch]。
 *
 * @param range 手指拖多远形变基本到顶。
 * @param stretch 朝手指方向最多拉长多少，按组件尺寸的比例。0.06 就是最多长出 6%。
 * @param squeeze 拉长时另一个方向跟着收多少，按拉长量的比例。0 就是只拉长不收窄。
 * @param tilt 按在边上拖动时最多歪多少度。按在正中间拖不歪。
 */
@Immutable
internal data class GlassDragStyle(
    val range: Dp,
    val stretch: Float,
    val squeeze: Float,
    val tilt: Float,
)

/**
 * 按住拖动时的形变，像一块果冻被手指拽着。在 graphicsLayer 或 drawBackdrop 的 layerBlock 里
 * 调，叠在已有的缩放上面。
 *
 * - 朝手指的方向拉长，另一个方向收一点。远离手指的那一边不动，靠近手指的那一边被拽过去：
 *   以中心缩放，再往手指那边平移拉长量的一半。
 * - 拖得越远拉得越长，但越来越拉不动（tanh），拖到 range 左右基本到顶。
 * - 按的位置离中心越远，拖动时越歪：按在上半截往右拖，上边被拽得多、下边少，整块顺时针
 *   歪一点。
 *
 * 写在 drawBackdrop 的 layerBlock 里时，玻璃背后的内容不会跟着变形：库会把缩放、旋转反着
 * 抵消掉，平移靠它按图层变换后的位置重新对齐。
 *
 * @param drag 手指拖了多远（[GlassPressState.dragOffset]）。
 * @param grab 手指落下的位置，按这个图层自己的坐标。
 */
internal fun GraphicsLayerScope.dragStretch(drag: Offset, grab: Offset, style: GlassDragStyle) {
    if (drag == Offset.Zero || !grab.isSpecified || size.width <= 0f || size.height <= 0f) return
    val range = style.range.toPx()
    val fx = tanh(drag.x / range)
    val fy = tanh(drag.y / range)
    val stretchX = style.stretch * abs(fx)
    val stretchY = style.stretch * abs(fy)
    scaleX *= (1f + stretchX) * (1f - stretchY * style.squeeze)
    scaleY *= (1f + stretchY) * (1f - stretchX * style.squeeze)
    translationX += sign(fx) * stretchX * size.width / 2f
    translationY += sign(fy) * stretchY * size.height / 2f
    // 按的位置换算到 -1..1，中心是 0。
    val gx = (grab.x / size.width * 2f - 1f).coerceIn(-1f, 1f)
    val gy = (grab.y / size.height * 2f - 1f).coerceIn(-1f, 1f)
    rotationZ += style.tilt * (fx * -gy + fy * gx)
}
