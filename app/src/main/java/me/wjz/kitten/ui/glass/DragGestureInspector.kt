package me.wjz.kitten.ui.glass

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

/**
 * 「旁观式」拖动手势：立刻响应按下，且不消费事件。
 *
 * 和 `detectDragGestures` / `detectHorizontalDragGestures` 的两点关键差别：
 *
 * 1. **不等 touch slop。** 标准的拖动检测要手指先移动几个 dp 才认为开始拖，在那之前
 *    什么回调都不发。但液体玻璃要的是手指一落下玻璃就鼓起来，等 slop 就慢了半拍。
 *    所以这里 `awaitFirstDown` 一拿到就发 `onDragStart`。
 *
 * 2. **不消费（consume）事件。** 标准实现会把事件标记为已消费，好让外层的滚动容器
 *    别抢。这里反过来——滑块盖在 tab 上面，如果它把事件吃掉，底下那个 tab 的
 *    `clickable` 就收不到点击了。不消费，事件继续往下传，点击照常生效。
 *
 * 代价是手势判定要自己写（下面两个私有函数），因为 foundation 里能复用的 `drag()`
 * 都默认带消费语义。
 *
 * 另外注意：按下时会立刻发一次 `onDrag(change, Offset.Zero)`，位移为零。这不是多余的
 * 调用——开关靠「抬手前有没有收到非零位移」来区分"点一下"和"拖一下"，这一发零位移
 * 正好把状态初始化成"还没拖过"。
 */
suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit,
) {
    awaitEachGesture {
        // 在 Initial 阶段取按下点：此时还没有任何节点处理过这个事件，拿到的坐标最准。
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)

        val upEvent = awaitDrag(initialDown.id) { onDrag(it, it.positionChange()) }
        if (upEvent == null) onDragCancel() else onDragEnd(upEvent)
    }
}

/**
 * 跟踪一根手指直到抬起。
 *
 * 返回抬起时的那个 change，中途被别人消费掉则返回 null（当成取消）。
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
        // 被外层（比如祖先的滚动容器）消费了，说明这个手势不归我们管，撤。
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
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
