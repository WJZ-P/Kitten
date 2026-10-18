package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 液体玻璃开关，iOS 的样子。
 *
 * 点一下会切换，也可以直接把拨钮拖过去。按下的瞬间拨钮鼓起来变成透镜，到位之后才收回
 * 原形——和 [GlassTabBar] 的滑块共用 [DampedDragAnimation] 这套驱动。形状和参数照搬
 * catalog 的 `LiquidToggle`。
 *
 * ## 拨钮看到的是什么
 *
 * 轨道单独录进 `trackBackdrop`，拨钮折射的是 `背景 + 被压扁的轨道`：
 *
 * ```
 * 未按下   纵向缩放 0      → 轨道完全看不见，拨钮是一块实心白
 * 按下     纵向缩放 0.75   → 轨道的颜色被挤进拨钮里透出来
 * ```
 *
 * 所以按下去的那一下，拨钮会从"白色拨钮"变成"真的能看见底下轨道颜色的玻璃"。这是
 * 这个开关和普通 Switch 观感上最大的区别，光靠改颜色做不出来。
 *
 * Material 的 `Switch` 做不到这些：它的轨道和拨钮都是不透明的 `Surface`，会把折射盖掉；
 * 拨钮位移是 `animateDpAsState` 的单条曲线，没有按压形变这一维。
 *
 * ## 交互：动效跟手，松手才算数
 *
 * 按下拨钮就鼓起，拖动时跟着手指走，但 [onCheckedChange] 只在抬手时调用：没拖过就取反，
 * 拖过就就近吸附。手势被外层滚动接管（用户其实是想滚动页面）时退回原位，什么都不发生。
 *
 * 松手时只调 `updateValue`，不调 `animateToValue`：手指按下时 [DampedDragAnimation.modifier]
 * 已经 `press()` 过了，抬手时它自己会 `release()`。再叠一个 `animateToValue` 等于在收缩前
 * 又 `press()` 一次，白做一轮。`animateToValue` 只留给外部改状态（没有手指参与）的情况。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    accentColor: Color = KittenTheme.colors.accent,
) {
    val trackOff = KittenTheme.colors.trackOff
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    val travelPx = with(density) { KnobTravel.toPx() }

    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val drag = remember(scope, travelPx, isLtr, touchSlop) {
        // 手势内的临时量，只在回调里读写、不参与绘制，用普通变量就够。
        var totalDx = 0f
        var didDrag = false
        DampedDragAnimation(
            animationScope = scope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStarted = { _, _ ->
                totalDx = 0f
                didDrag = false
            },
            onDrag = { _, _, dragAmount ->
                totalDx += dragAmount.x
                // 用 slop 判断"拖过"。catalog 是"收到过非零位移就算拖"，手指落下时的一点点
                // 抖动就会让一次点击被当成拖动、吸附回原位，看起来就是"点了没反应"。
                if (!didDrag && abs(totalDx) > touchSlop) didDrag = true
                val delta = dragAmount.x / travelPx * if (isLtr) 1f else -1f
                fraction = (fraction + delta).coerceIn(0f, 1f)
                updateValue(fraction)
            },
            onDragStopped = { upPosition ->
                val next = when {
                    // 外层滚动接管了：当作没发生过。
                    upPosition == null -> currentChecked
                    // 拖过：就近吸附。
                    didDrag -> targetValue >= 0.5f
                    // 没拖过（纯点击）：取反。
                    else -> !currentChecked
                }
                fraction = if (next) 1f else 0f
                updateValue(fraction)
                if (next != currentChecked) currentOnCheckedChange(next)
            },
        )
    }

    // 外部改状态（读屏激活、或者别处把配置改了）时补上动画。手指操作的路径已经在
    // onDragStopped 里把 fraction 同步过了，这里会判断相等直接跳过，不会播两遍。
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (target != fraction) {
            fraction = target
            drag.animateToValue(target)
        }
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(
        modifier = modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(currentChecked)
            // 拖动手势是给手指的，读屏用户只能发"激活"。没有这条的话 TalkBack
            // 根本点不动这个开关。
            onClick {
                currentOnCheckedChange(!currentChecked)
                true
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 轨道。它是拨钮的折射源，所以必须是拨钮的兄弟，不能把拨钮放进来。
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(lerp(trackOff, accentColor, drag.value)) }
                .size(TrackWidth, TrackHeight),
        )

        // 拨钮。纯展示，不带手势——手势在下面那块覆盖整条轨道的透明区域上。
        Box(
            modifier = Modifier
                .graphicsLayer {
                    val pad = KnobPadding.toPx()
                    val x = lerp(pad, pad + travelPx, drag.value)
                    // RTL 下外层是右对齐的，往左走。
                    translationX = if (isLtr) x else -x
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val p = drag.pressProgress
                            // 纵向从 0 开始：没按下时轨道压成一条线，等于看不见。
                            scale(lerp(2f / 3f, 0.75f, p), lerp(0f, 0.75f, p)) {
                                drawBackdrop()
                            }
                        },
                    ),
                    shape = { Capsule() },
                    effects = {
                        val p = drag.pressProgress
                        // 模糊和折射是此消彼长的：没按下时靠模糊做出"磨砂拨钮"，
                        // 按下后换成透镜，才看得清被掰弯的轨道颜色。
                        blur(8.dp.toPx() * (1f - p))
                        lens(5.dp.toPx() * p, 10.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = {
                        val p = drag.pressProgress
                        // 拨钮比卡片小得多，高光宽度按原样会糊成一圈白边。
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = p,
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
                        // 拖得快时横向拉长、纵向压扁。取绝对值：往左甩和往右甩应该是同一种
                        // 形变（catalog 里直接用带符号的速度，往左甩会变成横向压扁）。
                        // 除数 50 比 tab 栏的 10 大，因为这里的值域只有 0..1，归一化后的
                        // 速度量级大得多。
                        val v = abs(drag.velocity) / 50f
                        scaleX /= 1f - (v * 0.75f).coerceIn(0f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(0f, 0.2f)
                    },
                    onDrawSurface = {
                        // 按下时让白色退场，把舞台交给折射。
                        drawRect(Color.White.copy(alpha = 1f - drag.pressProgress))
                    },
                )
                .size(KnobWidth, KnobHeight),
        )

        // 手势区：透明，盖满整条轨道，整条都能点能拖（catalog 只挂在拨钮上，点轨道空白处
        // 没反应，和系统开关的习惯不符）。全局只留这一个节点带手势：若再给外层加一个
        // clickable，点拨钮时两边会各切换一次，相互抵消，开关看着就"点不动"。
        Box(modifier = Modifier.then(drag.modifier).size(TrackWidth, TrackHeight))
    }
}

private val TrackWidth = 64.dp
private val TrackHeight = 28.dp
private val KnobWidth = 40.dp
private val KnobHeight = 24.dp
private val KnobPadding = 2.dp

/** 拨钮的行程。由尺寸推出来，改上面任一个都不会和这里算歪。 */
private val KnobTravel = TrackWidth - KnobWidth - KnobPadding * 2
