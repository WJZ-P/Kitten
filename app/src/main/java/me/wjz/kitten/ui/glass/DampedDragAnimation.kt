package me.wjz.kitten.ui.glass

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 液体玻璃控件的动画驱动器：一个能拖、带阻尼、按下时会鼓起来的数值。
 *
 * 移植自 backdrop 作者自己的 catalog（`DampedDragAnimation`），因为这部分不在发布的
 * 库里——库只提供"怎么画玻璃"，"玻璃怎么动"是示例代码。照搬的理由是这些弹簧参数是
 * 调出来的，自己瞎配出来的手感会差很远。
 *
 * ## 为什么能做出"先放大、再挪过去、最后缩小"
 *
 * 关键在 [animateToValue] 把三件事串成一个括号：
 *
 * 1. [press] —— `scaleX`/`scaleY` 弹到 `pressedScale`，`pressProgress` 升到 1。
 *    滑块鼓起来，同时玻璃的折射、高光、阴影都跟着 `pressProgress` 变强。
 * 2. 位置动画 —— `valueAnimation` 朝目标弹过去。
 * 3. [release] —— **先等** `value` 逼近 `targetValue`（见下），**再**缩回去。
 *
 * 第 3 步的等待是整个效果的要点。如果放大和缩小同时开始，滑块会在移动途中就瘪掉，
 * 看着像抖了一下；只有"移动基本结束了才收缩"，才是那种液体被推过去、到位后表面
 * 张力把它收圆的感觉。
 *
 * ## 五条弹簧为什么参数各不相同
 *
 * - `value` 用临界阻尼（dampingRatio = 1）+ 高刚度：位置不该回弹，用户点了第三个 tab
 *   结果滑块冲过去又荡回来，会显得不受控。
 * - `scaleX` / `scaleY` 用欠阻尼（0.6 / 0.7）：形变需要一点过冲才像有弹性的液体，
 *   而且两轴的阻尼故意不等——一起弹会像在缩放一张图片，错开才像被挤压。
 * - `velocity` 单独做一条弹簧而不是直接用瞬时速度：原始速度抖得厉害，直接拿去驱动
 *   形变会让滑块高频颤动。
 *
 * ## 标成 [Stable] 的原因
 *
 * 它的公开读取口（[value]、[pressProgress] 等）全部落在 `Animatable.value` 上，也就是
 * 快照状态，变更会正常通知重组；类本身没有公开的 `var`。满足 [Stable] 的契约。
 *
 * 这不只是形式上的标注：不标的话 Compose 会把捕获了本对象的 lambda 判为不可记忆，
 * 于是 `rememberBackdrop(...) { ... }` 这类调用每次重组都拿到新 key、重建一个 Backdrop。
 */
@Stable
class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    initialValue: Float,
    private val valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    private val initialScale: Float,
    private val pressedScale: Float,
    private val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit = {},
    private val onDragStopped: DampedDragAnimation.() -> Unit = {},
    private val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()

    /** 值域跨度。tabCount == 1 时值域会退化成一个点，拿它做除数会算出 NaN。 */
    private val span: Float
        get() = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f

    val value: Float get() = valueAnimation.value
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    /** 挂在被拖的那个元素上。按下即响应，不等 slop——玻璃要在手指落下的那一帧就鼓起来。 */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragStopped()
                release()
            },
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    fun press() {
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        animationScope.launch {
            // 先让出一帧：release() 常常紧跟在 press() 后面被调用（点击而非拖动），
            // 不等一帧的话 targetValue 可能还没被位置动画写进去，下面的判断会误判成
            // "已经到位了"，于是放大和缩小叠在同一帧，整个效果看不见。
            withFrameNanos {}
            if (value != targetValue) {
                // 不等完全停住。弹簧的尾巴很长，等到严格相等会多等掉一两百毫秒，
                // 看着像卡住了。到 2.5% 以内就认为"到位"，此时开始收缩刚好接得上。
                val threshold = span * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /**
     * 跟手更新：拖动过程中用这个。
     *
     * 不做 press/release，因为手指还在屏幕上——鼓起和收缩由 [modifier] 的按下/抬起
     * 事件负责。这里只把目标值推过去，顺带记录速度给形变用。
     */
    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        animationScope.launch {
            valueAnimation.animateTo(target, valueAnimationSpec) { updateVelocity() }
        }
    }

    /** 整段播放：点击、或者外部改了选中项时用这个，走完"放大 → 挪过去 → 缩小"。 */
    fun animateToValue(value: Float) {
        animationScope.launch {
            // 连点两个 tab 时，后一次要能干掉前一次的括号，否则两组 press/release
            // 交错，滑块会鼓一半又被按回去。
            mutatorMutex.mutate {
                press()
                val target = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        // VelocityTracker 要的是"时间 + 位置"的序列。这里喂的位置是抽象的 value
        // （tab 序号 / 开关进度），不是像素，所以算出来的速度要除以值域跨度归一化，
        // 否则 0..2 的 tab 和 0..1 的开关会得到差一倍的形变强度。
        velocityTracker.addPosition(SystemClock.uptimeMillis(), Offset(value, 0f))
        val target = velocityTracker.calculateVelocity().x / span
        animationScope.launch { velocityAnimation.animateTo(target, velocityAnimationSpec) }
    }
}
