package me.wjz.kitten.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    internal fun press() {
        scope.launch { progress.animateTo(1f, PressInSpec) }
    }

    internal fun release() {
        scope.launch { progress.animateTo(0f, PressOutSpec) }
    }
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
 */
suspend fun PointerInputScope.detectGlassPress(
    state: GlassPressState,
    onDown: (Offset) -> Unit = {},
    onClick: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown().also { it.consume() }
        state.position = down.position
        onDown(down.position)
        state.press()
        var committed = false
        try {
            committed = trackPress(down.id, state)
        } finally {
            // 手势协程被取消（比如控件被移出组合）时也要收回按压态，否则会一直鼓着。
            state.release()
        }
        if (committed) onClick()
    }
}

/** 返回 true 表示在范围内抬手；手指滑出后抬手、或者被外层接管，都返回 false。 */
private suspend fun AwaitPointerEventScope.trackPress(
    pointerId: PointerId,
    state: GlassPressState,
): Boolean {
    // 允许手指稍微滑出边界——指头是一块面，不是一个点，贴着边抬起来不该算"滑出去了"。
    val slop = viewConfiguration.touchSlop
    var inside = true
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == pointerId } ?: return false
        if (change.changedToUpIgnoreConsumed()) return inside && !change.isConsumed
        if (change.isConsumed) return false
        state.position = change.position

        val nowInside = change.position.isWithin(size.width.toFloat(), size.height.toFloat(), slop)
        if (nowInside != inside) {
            inside = nowInside
            if (inside) state.press() else state.release()
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
