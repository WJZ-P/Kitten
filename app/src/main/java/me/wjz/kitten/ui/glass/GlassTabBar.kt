package me.wjz.kitten.ui.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 带液体玻璃滑块的底部 tab 栏。
 *
 * 切换时滑块会**先鼓起来、挪过去、到位后再收缩**，移动途中还会被速度拉长压扁。这套
 * 动作由 [DampedDragAnimation] 驱动，参数都抄自库作者的 catalog（库本身只负责画玻璃，
 * 不含这些交互）。滑块也能直接用手指横向拖。
 *
 * ## 为什么要把内容画两遍
 *
 * 选中的 tab 变成高亮色，这里**不是**靠给每个 tab 传 selected 做到的——那样滑块移动时
 * 颜色会在某一帧整格跳变，和连续移动的滑块对不上。真正的做法是叠三层：
 *
 * ```
 * ① 可见层   64dp 胶囊玻璃 + content（图标是白的）
 * ② 隐藏层   56dp，alpha(0)，content 整层染成高亮色，录进 tabsBackdrop
 * ③ 滑块     折射 (背景 + tabsBackdrop)，宽度 1/tabCount，跟着选中项移动
 * ```
 *
 * 用户看到的始终是①的白图标；但滑块③折射了②，所以**只有滑块盖住的那一块**会透出
 * 高亮色的图标。高亮区域因此是纯几何结果，和滑块严丝合缝，连移动中被折射拉歪的部分
 * 都是对的。代价是 content 被组合两次。
 *
 * ②必须是③的**兄弟**而不是祖先。`layerBackdrop` 要录制自己的全部内容，若③在②的子树
 * 里，③读②、②录③ ⇒ RenderNode 成环，`prepareTree` 递归到栈溢出（详见 [GlassBackground]
 * 里记的那次崩溃）。
 *
 * @param selectedIndex 当前选中项。外部改它（比如从对话页跳设置页）也会播放完整动画。
 * @param tabCount [content] 里 [GlassTab] 的个数，滑块宽度按它平分。
 */
@Composable
fun GlassTabBar(
    backdrop: Backdrop,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    tabCount: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = GlassAccent,
    content: @Composable RowScope.() -> Unit,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        // 每格的宽度。减掉的是胶囊内边距，滑块和内容都排在那块内部区域里。
        val tabWidth = (maxWidth - BarPadding * 2) / tabCount

        // 手指横向拖动的累计位移（px）。整条栏会朝拖动方向轻轻偏一点，像被推着走。
        val dragOffset = remember { Animatable(0f) }

        // 记住"动画已经认过"的选中项。少了这个的话，拖完手之后外部状态回流进来，
        // 下面的 LaunchedEffect 会对同一个目标再播一遍 press/release，滑块多抖一下。
        var committedIndex by remember { mutableIntStateOf(selectedIndex) }

        // isLtr 要当 key：下面 onDrag 的方向判断是构造时捕获的，布局方向变了必须重建。
        val drag = remember(scope, tabCount, isLtr) {
            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 按下时滑块从 56dp 鼓到 78dp，会溢出 64dp 的胶囊——这是故意的，
                // 像一团液体被按得涨出边界。外层留的 8dp 间距正好容得下。
                pressedScale = PressedSliderHeight / SliderHeight,
                onDragStopped = {
                    val target = targetValue.roundToInt().coerceIn(0, tabCount - 1)
                    committedIndex = target
                    // 只推位置，不调 animateToValue。手指按下时 modifier 已经 press()
                    // 过了，抬手时它自己会 release()，而 release() 就是"等滑块快到位了
                    // 再收缩"的那一步。再叠一轮 press/release 等于白做。
                    updateValue(target.toFloat())
                    currentOnSelect(target)
                    scope.launch { dragOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
                onDrag = { size, dragAmount ->
                    // size 是滑块自己的宽度，正好等于一格。不捕获上面算出的 tabWidth，
                    // 是因为这个 lambda 只在构造时记一次，转屏之后捕获的值就过期了。
                    val perTab = size.width.toFloat()
                    if (perTab > 0f) {
                        val delta = dragAmount.x / perTab * if (isLtr) 1f else -1f
                        updateValue(targetValue + delta)
                        scope.launch { dragOffset.snapTo(dragOffset.value + dragAmount.x) }
                    }
                },
            )
        }

        LaunchedEffect(selectedIndex) {
            if (selectedIndex != committedIndex) {
                committedIndex = selectedIndex
                drag.animateToValue(selectedIndex.toFloat())
            }
        }

        // ① 可见层：真正承载内容的玻璃胶囊。
        Row(
            modifier = Modifier
                .graphicsLayer { translationX = panelShift(dragOffset.value) }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(8.dp.toPx())
                        lens(24.dp.toPx(), 24.dp.toPx())
                    },
                    layerBlock = {
                        // 按下时整条栏也胀一点点，这样滑块的鼓起看着是"顶着"外壳，
                        // 而不是单独一块在动。
                        //
                        // 宽度为 0 时必须早退：16/0 得 Inf，而 lerp(1, Inf, 0) 是
                        // Inf * 0 = NaN，拿 NaN 当 scale 会让整条栏直接不绘制。
                        val scale =
                            if (size.width > 0f) {
                                lerp(1f, 1f + 16.dp.toPx() / size.width, drag.pressProgress)
                            } else {
                                1f
                            }
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(BarSurface) },
                )
                .height(BarHeight)
                .fillMaxWidth()
                .padding(BarPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )

        // ② 隐藏层：同样的内容，整层染成高亮色，只用来给滑块当折射源。
        CompositionLocalProvider(
            LocalGlassTabScale provides { lerp(1f, 1.2f, drag.pressProgress) },
        ) {
            Row(
                modifier = Modifier
                    // 无障碍树里只应该有一套 tab。不清掉的话读屏会把每个 tab 念两遍。
                    .clearAndSetSemantics {}
                    // alpha(0) 只影响上屏合成，不影响 layerBackdrop 录下来的内容——
                    // 所以这层在屏幕上不可见，但滑块折射到的是它完整的样子。
                    // 这两个 modifier 的顺序不能换。
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelShift(dragOffset.value) }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                            val p = drag.pressProgress
                            lens(24.dp.toPx() * p, 24.dp.toPx() * p)
                        },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        onDrawSurface = { drawRect(BarSurface) },
                    )
                    .height(SliderHeight)
                    .fillMaxWidth()
                    .padding(horizontal = BarPadding)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }

        // ③ 滑块。
        Box(
            modifier = Modifier
                .padding(horizontal = BarPadding)
                .graphicsLayer {
                    val offset = (tabWidth * drag.value).toPx()
                    // RTL 下外层是右对齐的，所以往左（负方向）走。
                    translationX =
                        (if (isLtr) offset else -offset) + panelShift(dragOffset.value)
                }
                .then(drag.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        // 折射强度跟着按压走：松手时滑块只是一块淡色胶囊，按下去才
                        // 真正变成透镜，把底下的图标掰弯。色散让边缘透出一点彩边。
                        val p = drag.pressProgress
                        lens(10.dp.toPx() * p, 14.dp.toPx() * p, chromaticAberration = true)
                    },
                    highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                    shadow = { Shadow(alpha = drag.pressProgress) },
                    innerShadow = {
                        val p = drag.pressProgress
                        InnerShadow(radius = 8.dp * p, alpha = p)
                    },
                    layerBlock = {
                        scaleX = drag.scaleX
                        scaleY = drag.scaleY
                        // 按移动速度横向拉长、纵向压扁，再加上体积守恒的那点手感：
                        // 这是"液体"而不是"一块圆角矩形在平移"的全部来源。
                        val v = drag.velocity / 10f
                        scaleX /= 1f - (v * 0.75f).coerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val p = drag.pressProgress
                        // 没按下时是一层淡白，看得出滑块在哪；按下后让位给折射，
                        // 否则这层白会把透镜效果糊掉。
                        drawRect(SliderSurface, alpha = 1f - p)
                        drawRect(Color.Black.copy(alpha = 0.03f * p))
                    },
                )
                .height(SliderHeight)
                .fillMaxWidth(1f / tabCount),
        )
    }
}

/**
 * [GlassTabBar] 里的一格。
 *
 * 没有 selected 参数：高亮是滑块折射出来的，不是这里染的，内容一律按未选中的样子给。
 */
@Composable
fun RowScope.GlassTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalGlassTabScale.current
    Column(
        modifier = modifier
            .clip(Capsule())
            // 不要 Material 的水波纹：滑块本身就是按压反馈，再叠一层涟漪会脏。
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val s = scale()
                scaleX = s
                scaleY = s
            },
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/**
 * 按下时 tab 内容的放大倍数。
 *
 * 只有隐藏层（②）会拿到非 1 的值，所以放大的是**滑块里透出来的**那个图标，可见层的
 * 图标不动。效果上像是凸起的玻璃把底下的图标放大了，符合透镜的直觉。
 */
private val LocalGlassTabScale = staticCompositionLocalOf<() -> Float> { { 1f } }

/**
 * 把累计拖动位移换成整条栏的偏移。
 *
 * 拖得越多偏移越大但增速递减（EaseOut），上限 4dp。给的是阻力感——栏跟着手走一点点，
 * 但明确地"推不动"，不会让人以为整条栏能被拖走。
 */
private fun GraphicsLayerScope.panelShift(dragPx: Float): Float {
    if (size.width <= 0f) return 0f
    val fraction = (dragPx / size.width).coerceIn(-1f, 1f)
    return 4.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction))
}

private val BarHeight = 64.dp
private val SliderHeight = 56.dp
private val PressedSliderHeight = 78.dp
private val BarPadding = 4.dp

/** 胶囊的表面色。纯折射会发灰，补一点白把玻璃"托"起来。 */
private val BarSurface = Color.White.copy(alpha = 0.08f)
private val SliderSurface = Color.White.copy(alpha = 0.12f)
