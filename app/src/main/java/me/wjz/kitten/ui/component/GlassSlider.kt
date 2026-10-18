package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlin.math.abs
import kotlin.math.roundToInt
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 液体玻璃滑条，iOS 的样子：一条细轨道 + 一颗玻璃拨钮。
 *
 * 形状和参数照搬 catalog 的 `LiquidSlider`。拨钮的把戏和 [GlassSwitch] 一样：平时是一块
 * 实心白，按下时鼓起来变成透镜，透过它能看见被压扁、放大的轨道。
 *
 * ## 交互：动效跟手，松手才算数
 *
 * - 按下：拨钮立刻奔到手指的位置（不用先对准拨钮再拖，轨道上任意位置都能按）；
 * - 拖动：拨钮跟着手指走；
 * - 抬手：才调用 [onValueChange]，并按 [steps] 吸附；
 * - 被外层滚动接管：拨钮退回原来的值，什么都不发生。
 *
 * 和 catalog 的区别：catalog 拖动时每一帧都回调 onValueChange，点轨道则是另一套
 * `detectTapGestures`。这里合成一个手势，回调只在抬手时发一次。
 *
 * @param steps 中间的吸附档位数，和 Material Slider 的含义一致：0 表示连续，
 *   比如 0..1 配 `steps = 3` 就是 0 / 0.25 / 0.5 / 0.75 / 1 五档。
 */
@Composable
fun GlassSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    accentColor: Color = KittenTheme.colors.accent,
) {
    val trackColor = KittenTheme.colors.trackOff
    val currentOnValueChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(value)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val trackBackdrop = rememberLayerBackdrop()

    fun toFraction(v: Float): Float {
        val span = valueRange.endInclusive - valueRange.start
        return if (span <= 0f) 0f else ((v - valueRange.start) / span).coerceIn(0f, 1f)
    }

    fun fromFraction(f: Float): Float = lerp(valueRange.start, valueRange.endInclusive, f)

    // 已经提交过的位置。外部 value 变化、或者手势提交时更新，用来判断要不要补动画。
    var committed by remember { mutableFloatStateOf(toFraction(value)) }

    val drag = remember(scope, isLtr, steps, valueRange) {
        fun fractionAt(x: Float, width: Int): Float {
            if (width <= 0) return committed
            val f = (x / width).coerceIn(0f, 1f)
            return if (isLtr) f else 1f - f
        }

        fun snap(f: Float): Float {
            if (steps <= 0) return f
            val intervals = steps + 1
            return (f * intervals).roundToInt().toFloat() / intervals
        }

        DampedDragAnimation(
            animationScope = scope,
            initialValue = committed,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = { size, position -> updateValue(fractionAt(position.x, size.width)) },
            onDrag = { size, position, _ -> updateValue(fractionAt(position.x, size.width)) },
            onDragStopped = { upPosition ->
                if (upPosition == null) {
                    // 外层滚动接管了：退回原值。
                    updateValue(committed)
                } else {
                    val snapped = snap(targetValue)
                    updateValue(snapped)
                    committed = snapped
                    val newValue = fromFraction(snapped)
                    if (newValue != currentValue) currentOnValueChange(newValue)
                }
            },
        )
    }

    // 外部改了值（没有手指参与）时补动画。比较要带容差：自己提交的值经 fromFraction →
    // 调用方 → toFraction 绕一圈回来，浮点误差会让它和 committed 差个 1e-7，严格比较会
    // 误判成"外部改了"，松手后拨钮又鼓一次。
    LaunchedEffect(value) {
        val target = toFraction(value)
        if (abs(target - committed) > 0.0001f) {
            committed = target
            drag.animateToValue(target)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(SliderHeight)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value.coerceIn(valueRange.start, valueRange.endInclusive),
                    range = valueRange,
                    steps = steps,
                )
                // 读屏用户的调节入口：音量键 / 手势直接给目标值，同样只在这一下提交。
                setProgress { target ->
                    val clamped = target.coerceIn(valueRange.start, valueRange.endInclusive)
                    if (clamped != currentValue) currentOnValueChange(clamped)
                    true
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()

        // 轨道 + 已填充的那一段。它是拨钮的折射源，必须是拨钮的兄弟。
        // layerBackdrop 写在最左边，右边画的东西才会被录进去。
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .fillMaxWidth()
                .height(TrackHeight)
                .drawBehind {
                    val radius = CornerRadius(size.height / 2f)
                    drawRoundRect(color = trackColor, cornerRadius = radius)
                    val filled = size.width * drag.value
                    drawRoundRect(
                        color = accentColor,
                        // RTL 下从右往左填。
                        topLeft = Offset(if (isLtr) 0f else size.width - filled, 0f),
                        size = Size(filled, size.height),
                        cornerRadius = radius,
                    )
                },
        )

        // 拨钮。纯展示，手势在下面盖满整条的透明区域上。
        Box(
            modifier = Modifier
                .graphicsLayer {
                    // 拨钮中心对准取值位置，但两端允许探出轨道 1/4 个拨钮，不会整颗跑出去。
                    // 上限要先和下限比一下：布局初期宽度可能是 0，那时上限会小于下限，
                    // coerceIn 遇到这种区间会直接抛异常。
                    val min = -size.width / 4f
                    val max = (trackWidthPx - size.width * 3f / 4f).coerceAtLeast(min)
                    val x = (trackWidthPx * drag.value - size.width / 2f).coerceIn(min, max)
                    translationX = if (isLtr) x else -x
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val p = drag.pressProgress
                            // 纵向从 0 开始：没按下时轨道压成一条线，拨钮是实心白。
                            scale(lerp(2f / 3f, 1f, p), lerp(0f, 1f, p)) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val p = drag.pressProgress
                        blur(8.dp.toPx() * (1f - p))
                        lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = {
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = drag.pressProgress,
                        )
                    },
                    shadow = { Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.05f)) },
                    innerShadow = {
                        val p = drag.pressProgress
                        InnerShadow(radius = 4.dp * p, alpha = p)
                    },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        // 取绝对值：往左甩和往右甩应该是同一种形变。
                        val v = abs(drag.velocity) / 10f
                        scaleX /= 1f - (v * 0.75f).coerceIn(0f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(0f, 0.2f)
                    },
                    onDrawSurface = {
                        drawRect(Color.White.copy(alpha = 1f - drag.pressProgress))
                    },
                )
                .size(KnobWidth, KnobHeight),
        )

        // 手势区：盖满整条，轨道上任意位置都能按、能拖。
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(drag.modifier),
        )
    }
}

/** 整个控件的高度：拨钮按下会鼓到 36dp，留出余量不顶到上下的元素，也让手指好按。 */
private val SliderHeight = 40.dp
private val TrackHeight = 6.dp
private val KnobWidth = 40.dp
private val KnobHeight = 24.dp
