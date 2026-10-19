package me.wjz.kitten.ui.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import kotlin.math.abs

/**
 * 液体玻璃控件用的拖动手势：按下立刻响应，横向拖过 slop 才认领事件。
 *
 * 和 `detectDragGestures` / `detectHorizontalDragGestures` 的差别：
 *
 * 1. **不等 touch slop 就开始回调。** 标准的拖动检测要手指先移动几个 dp 才认为开始拖，
 *    在那之前什么回调都不发。但液体玻璃要的是手指一落下玻璃就鼓起来，等 slop 就慢了
 *    半拍。所以这里 `awaitFirstDown` 一拿到就发 `onDragStart`。
 *
 * 2. **slop 只用来决定"这次拖动归不归我"。** 横向累计位移先超过 slop、且横向大于纵向，
 *    就开始消费事件，外层（对话页的侧滑抽屉、设置页的纵向滚动）就抢不走了。反过来，
 *    纵向先超过 slop 说明用户想滚动页面，这里不消费，让外层接管。
 *
 * 3. **外层接管时会收到取消。** 外层滚动容器在 Main 阶段比子节点**晚**处理事件，所以在
 *    Main 阶段看到的永远是"还没被消费"。要到 Final 阶段回头看一眼，才知道外层刚才是不是
 *    把这个事件吃掉了——吃掉了就当取消处理，组件据此复原，而不是把一次滚动误当成点击。
 *    这个写法照抄 foundation 里 `waitForUpOrCancellation` 的做法。
 *
 * 另外注意：按下时会立刻发一次 `onDrag(change, Offset.Zero)`，位移为零。这不是多余的
 * 调用——它把"手指在哪"第一时间交给调用方，组件靠它决定按下时滑块该往哪儿走。
 *
 * @param claimHorizontalDrag 横向拖动过 slop 后是否消费事件。Kitten 的这几个控件（tab 栏、
 *   开关、滑条）都是横向操作，默认认领。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    claimHorizontalDrag: Boolean = true,
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        // 在 Initial 阶段取按下点：此时还没有任何节点处理过这个事件，拿到的坐标最准。
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        // 按下事件消费掉，给外层看的记号：玻璃卡片只接没被消费的按下，按在开关、滑条上时卡片
        // 不会跟着一起动。外层的滚动不受影响，它认按下时不管消没消费过。
        val down = awaitFirstDown(requireUnconsumed = false).also { it.consume() }

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)

        val touchSlop = viewConfiguration.touchSlop
        var claimed = false
        var totalX = 0f
        var totalY = 0f

        val upEvent = awaitDrag(initialDown.id) { change ->
            val dragAmount = change.positionChange()
            onDrag(change, dragAmount)
            if (claimHorizontalDrag) {
                if (!claimed) {
                    totalX += dragAmount.x
                    totalY += dragAmount.y
                    claimed = abs(totalX) > touchSlop && abs(totalX) > abs(totalY)
                }
                if (claimed) change.consume()
            }
        }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

/**
 * 跟踪一根手指直到抬起。
 *
 * 返回抬起时的那个 change；中途被外层消费掉则返回 null（当成取消）。
 */
private suspend inline fun AwaitPointerEventScope.awaitDrag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit,
): PointerInputChange? {
    // 手势开始的那一帧手指可能已经抬起来了（极短的点击），这里要先确认它还按着。
    if (currentEvent.changes.firstOrNull { it.id == pointerId }?.pressed != true) return null

    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
        // onDrag 里已经认领（消费）了就不用再看：Final 阶段看到的"已消费"是自己干的。
        if (!change.isConsumed) {
            val finalPass = awaitPointerEvent(PointerEventPass.Final)
            if (finalPass.changes.any { it.id == pointer && it.isConsumed }) return null
        }
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId,
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.firstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            // 这根手指抬了，但如果还有别的手指按着，就交接给它继续拖——多指操作时
            // 抬起一根不该让滑块突然停下。
            val otherDown = event.changes.firstOrNull { it.pressed }
            if (otherDown == null) return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}
