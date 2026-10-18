package me.wjz.kitten.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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

/**
 * 液体玻璃开关。
 *
 * 点一下会切换，也可以直接把滑块拖过去。按下的瞬间滑块鼓起来变成透镜，到位之后才收回
 * 原形——和 [GlassTabBar] 的滑块共用 [DampedDragAnimation] 这套驱动。
 *
 * ## 滑块看到的是什么
 *
 * 轨道单独录进 `trackBackdrop`，滑块折射的是 `背景 + 被压扁的轨道`：
 *
 * ```
 * 未按下   纵向缩放 0      → 轨道完全看不见，滑块是一块实心白
 * 按下     纵向缩放 0.75   → 轨道的颜色被挤进滑块里透出来
 * ```
 *
 * 所以按下去的那一下，滑块会从"白色拨钮"变成"真的能看见底下轨道颜色的玻璃"。这是
 * 这个开关和普通 Switch 观感上最大的区别，光靠改颜色做不出来。
 *
 * ## 为什么 Material 的 Switch 不能直接套玻璃
 *
 * `Switch` 的轨道和拨钮都是不透明的 `Surface`，会把折射盖掉；而且它的拨钮位移是
 * `animateDpAsState` 的单条曲线，没有按压形变这一维，鼓起/收缩无处挂。
 *
 * ## 关于手势和收缩的时序
 *
 * 松手时**不调** [DampedDragAnimation.animateToValue]，只调 `updateValue` 推位置。因为
 * 手指按下时 [DampedDragAnimation.modifier] 已经 `press()` 过了，抬手时它会自己
 * `release()`——而 `release()` 正是"等滑块快到位了再收缩"的那一步。再叠一个
 * `animateToValue` 相当于在收缩前又 `press()` 一次，白做一轮。
 * `animateToValue` 只留给外部改状态（没有手指参与）的情况。
 */
@Composable
fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    accentColor: Color = GlassAccent,
) {
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val travelPx = with(density) { KnobTravel.toPx() }

    // 区分"点一下"和"拖一下"。按下时会先收到一次零位移的 onDrag（见
    // inspectDragGestures 的说明），所以这里从 false 起算是准的。
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val drag = remember(scope, travelPx, isLtr) {
        DampedDragAnimation(
            animationScope = scope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = 1.5f,
            onDragStopped = {
                // 拖过：就近吸附。没拖过（纯点击）：取反。
                val next = if (didDrag) targetValue >= 0.5f else !currentChecked
                didDrag = false
                fraction = if (next) 1f else 0f
                updateValue(fraction)
                currentOnCheckedChange(next)
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val delta = dragAmount.x / travelPx * if (isLtr) 1f else -1f
                fraction = (fraction + delta).coerceIn(0f, 1f)
                updateValue(fraction)
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
        // 轨道。它是滑块的折射源，所以必须是滑块的兄弟，不能把滑块放进来。
        Box(
            modifier = Modifier
                .layerBackdrop(trackBackdrop)
                .clip(Capsule())
                .drawBehind { drawRect(lerp(TrackOffColor, accentColor, drag.value)) }
                .size(TrackWidth, TrackHeight),
        )

        // 滑块。纯展示，不带手势——手势在下面那块覆盖整条轨道的透明区域上。
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
                        // 拖得快时横向拉长、纵向压扁。除数 50 比 tab 栏的 10 大，
                        // 因为这里的值域只有 0..1，归一化后的速度量级大得多。
                        val v = drag.velocity / 50f
                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // 按下时让白色退场，把舞台交给折射。
                        drawRect(Color.White.copy(alpha = 1f - drag.pressProgress))
                    },
                )
                .size(KnobWidth, KnobHeight),
        )

        // 手势区：透明，盖满整条轨道。
        //
        // catalog 把手势挂在拨钮上，于是只有那 40dp 能点，点轨道空白处毫无反应——
        // 和系统 Switch 的习惯（整条都能点）不一致。这里单独拉一块出来，顺带保证
        // **全局只有一个节点带手势**：若再给外层加一个 clickable，点拨钮时它和拨钮
        // 的手势会各自触发一次切换，两次相互抵消，开关看着就"点不动"。
        //
        // onDrag 只用 dragAmount 的增量，和手势区自身的尺寸无关，所以放大判定区
        // 不会改变拖动的手感。
        Box(modifier = Modifier.then(drag.modifier).size(TrackWidth, TrackHeight))
    }
}

private val TrackWidth = 64.dp
private val TrackHeight = 28.dp
private val KnobWidth = 40.dp
private val KnobHeight = 24.dp
private val KnobPadding = 2.dp

/** 滑块的行程。由尺寸推出来，改上面任一个都不会和这里算歪。 */
private val KnobTravel = TrackWidth - KnobWidth - KnobPadding * 2

/** 关闭态的轨道色。深色背景上用中性灰，不带色相，免得和强调色打架。 */
private val TrackOffColor = Color(0xFF787880).copy(alpha = 0.36f)
