package me.wjz.kitten.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 带液体玻璃滑块的底部 tab 栏。
 *
 * 切换时滑块会**先鼓起来、挪过去、到位后再收缩**，移动途中还会被速度拉长压扁。这套
 * 动作由 [DampedDragAnimation] 驱动，形状和参数都抄自库作者的 catalog（库本身只负责画
 * 玻璃，不含这些交互）。
 *
 * ## 交互：动效跟手，松手才算数
 *
 * ```
 * 按下   滑块立刻鼓起、奔向手指下的那一格     ← 只是预览，选中项没变
 * 拖动   滑块跟着手指走
 * 抬手   落在哪格就选哪格，这时才调用 onSelect
 * 取消   往上拖出 tab 栏一整个栏高再松手、或者手势被系统收回 → 滑块回到原来那格
 * ```
 *
 * 为此最上面盖了一层透明的手势层，整条栏的触摸都由它接管；tab 自己不处理点击，只
 * 留给读屏用的语义。以前是 tab 各自 `clickable`，于是按住"设置"时什么都不动，要等抬手
 * 触发点击、选中项变了，滑块才开始走。
 *
 * ## 为什么要把内容画两遍
 *
 * 选中的 tab 变成高亮色，这里**不是**靠给每个 tab 传 selected 做到的——那样滑块移动时
 * 颜色会在某一帧整格跳变，和连续移动的滑块对不上。真正的做法是叠三层：
 *
 * ```
 * ① 可见层   圆角玻璃栏 + content（图标是正文色）
 * ② 隐藏层   和滑块一样高，alpha(0)，content 整层染成高亮色，录进 tabsBackdrop
 * ③ 滑块     折射 (背景 + tabsBackdrop)，宽度 1/tabCount，跟着选中项移动
 * ④ 手势层   透明，盖满整条栏
 * ```
 *
 * 用户看到的始终是①的白图标；但滑块③折射了②，所以**只有滑块盖住的那一块**会透出
 * 高亮色的图标。高亮区域因此是纯几何结果，和滑块严丝合缝，连移动中被折射拉歪的部分
 * 都是对的。代价是 content 被组合两次。
 *
 * 这套叠法成立的前提是**滑块不透明**——它折射的背景本身是实心的，所以能把①的白图标
 * 完全盖住。背景层要是没录进东西（见 [GlassBackground] 里记的那个 modifier 顺序的坑），
 * 滑块就是透明的，按下时②放大 1.2 倍、①没放大，两套图标错开，白色就漏出来了。
 *
 * ②必须是③的**兄弟**而不是祖先。`layerBackdrop` 要录制自己的全部内容，若③在②的子树
 * 里，③读②、②录③ ⇒ RenderNode 成环，`prepareTree` 递归到栈溢出。
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
    // 选中的图标和文字压在浅色玻璃上，用加深一档的天依蓝，本色太浅看不清。
    accentColor: Color = KittenTheme.colors.accentStrong,
    content: @Composable RowScope.() -> Unit,
) {
    val currentOnSelect by rememberUpdatedState(onSelect)
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val scope = rememberCoroutineScope()
    val tabsBackdrop = rememberLayerBackdrop()
    val colors = KittenTheme.colors

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart,
    ) {
        // 每格的宽度。减掉的是栏的内边距，滑块和内容都排在那块内部区域里。
        val tabWidth = (maxWidth - BarPadding * 2) / tabCount

        // 手指横向拖动的累计位移（px）。整条栏会朝拖动方向轻轻偏一点，像被推着走。
        val dragOffset = remember { Animatable(0f) }

        // 已经提交的选中项。滑块在手指底下怎么跑都不改它，只有抬手才改。
        var committedIndex by remember { mutableIntStateOf(selectedIndex) }

        // isLtr 等都要当 key：下面的回调是构造时捕获的，这些变了必须重建。
        val drag = remember(scope, tabCount, isLtr, density, touchSlop) {
            val padPx = with(density) { BarPadding.toPx() }
            val cancelDistancePx = with(density) { BarHeight.toPx() }
            // 手势内的临时量，只在回调里读写、不参与绘制，用普通变量就够。
            var downX = 0f
            var following = false

            /** 手指 x（手势层坐标）→ 滑块该在的位置，以格为单位。 */
            fun valueAt(x: Float, width: Int): Float {
                val perTab = (width - padPx * 2) / tabCount
                if (perTab <= 0f) return committedIndex.toFloat()
                val v = (x - padPx) / perTab - 0.5f
                // RTL 下第 0 格在最右边。
                val ordered = if (isLtr) v else tabCount - 1 - v
                return ordered.coerceIn(0f, (tabCount - 1).toFloat())
            }

            DampedDragAnimation(
                animationScope = scope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                // 按下时滑块鼓出栏外——这是故意的，像一团液体被按得涨出边界。
                pressedScale = PressedSliderScale,
                onDragStarted = { size, position ->
                    downX = position.x
                    following = false
                    // 按下就奔向手指下那一格的正中。先吸附到整格，手指晃一点点滑块不会跟着抖。
                    updateValue(valueAt(position.x, size.width).roundToInt().toFloat())
                },
                onDrag = { size, position, dragAmount ->
                    // 过了 slop 才改成连续跟手：此后滑块正中始终在手指底下。
                    if (!following && abs(position.x - downX) > touchSlop) following = true
                    if (following) updateValue(valueAt(position.x, size.width))
                    scope.launch { dragOffset.snapTo(dragOffset.value + dragAmount.x) }
                },
                onDragStopped = { upPosition ->
                    // 往上拖出一整个栏高再松手，当作反悔。tab 栏贴着屏幕底边，往下拖不出去，
                    // 所以只看上方。
                    val cancelled = upPosition == null || upPosition.y < -cancelDistancePx
                    val target =
                        if (cancelled) committedIndex
                        else targetValue.roundToInt().coerceIn(0, tabCount - 1)
                    // 只推位置，不调 animateToValue。手指按下时 modifier 已经 press()
                    // 过了，抬手时它自己会 release()，而 release() 就是"等滑块快到位了
                    // 再收缩"的那一步。再叠一轮 press/release 等于白做。
                    updateValue(target.toFloat())
                    if (target != committedIndex) {
                        committedIndex = target
                        currentOnSelect(target)
                    }
                    scope.launch { dragOffset.animateTo(0f, spring(1f, 300f, 0.5f)) }
                },
            )
        }

        // 外部改了选中项（没有手指参与）：补一段完整的动画。自己提交的那次 committedIndex
        // 已经同步过了，这里判断相等会跳过，不会对同一个目标再播一遍。
        LaunchedEffect(selectedIndex) {
            if (selectedIndex != committedIndex) {
                committedIndex = selectedIndex
                drag.animateToValue(selectedIndex.toFloat())
            }
        }

        // ① 可见层：真正承载内容的玻璃栏。
        Row(
            modifier = Modifier
                .graphicsLayer { translationX = panelShift(dragOffset.value) }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { BarShape },
                    // 和卡片同一种玻璃（见 glassEffects），只是模糊稍重一点，见 BarBlur。
                    effects = { glassEffects(GlassRadius.Large, BarBlur) },
                    layerBlock = {
                        // 按下时整条栏也胀一点点，这样滑块的鼓起看着是"顶着"外壳，
                        // 而不是单独一块在动。
                        val scale = lerp(1f, PressedBarScale, drag.pressProgress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(colors.glassTintStrong) },
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
                        shape = { SliderShape },
                        // 和①一样的玻璃：滑块平时露出的就是这一层，两层透出来的字要接得上，
                        // 不然滑块边上背后的字会错开一截。
                        effects = { glassEffects(GlassRadius.Large - BarPadding, BarBlur) },
                        highlight = { Highlight.Default.copy(alpha = drag.pressProgress) },
                        onDrawSurface = { drawRect(colors.glassTintStrong) },
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
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { SliderShape },
                    effects = {
                        // 折射强度跟着按压走：松手时滑块只是一块淡色玻璃，按下去才
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
                        // 取绝对值：往左甩和往右甩应该是同一种形变（catalog 里直接用带
                        // 符号的速度，往左甩会变成横向压扁）。
                        val v = abs(drag.velocity) / 10f
                        scaleX /= 1f - (v * 0.75f).coerceIn(0f, 0.2f)
                        scaleY *= 1f - (v * 0.25f).coerceIn(0f, 0.2f)
                    },
                    onDrawSurface = {
                        val p = drag.pressProgress
                        // 没按下时是一层淡天依蓝，看得出滑块停在哪一格；按下后让位给折射，
                        // 否则这层颜色会把透镜效果糊掉。
                        drawRect(colors.sliderIdle, alpha = 1f - p)
                        drawRect(Color.Black.copy(alpha = 0.03f * p))
                    },
                )
                .height(SliderHeight)
                .fillMaxWidth(1f / tabCount),
        )

        // ④ 手势层：透明，盖满整条栏，最后放所以在最上面。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(BarHeight)
                .then(drag.modifier),
        )
    }
}

/**
 * [GlassTabBar] 里的一格。
 *
 * 不处理触摸——按下、拖动、松手切换都由 [GlassTabBar] 的手势层统一负责。这里只提供读屏
 * 用的语义：TalkBack 用户双击时走 [onSelect]。
 *
 * [selected] 也只用于语义（让读屏念出"已选中"）。视觉上的高亮是滑块折射出来的，不在
 * 这里染色，内容一律按未选中的样子给。
 */
@Composable
fun RowScope.GlassTab(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = LocalGlassTabScale.current
    Column(
        modifier = modifier
            // 图标和文字合成一个可聚焦节点，读屏念一次就够。
            .semantics(mergeDescendants = true) {
                role = Role.Tab
                this.selected = selected
                onClick {
                    onSelect()
                    true
                }
            }
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

private val BarHeight = 56.dp

/**
 * 栏的模糊。液态玻璃的畸变感来自**轻模糊 + 强折射**：背后的字基本看得清，边缘把它们放大、
 * 掰弯。糊重了（以前是 8dp）字先变成一团团色块，折射掰弯色块是看不出来的，就只剩高斯模糊。
 */
private val BarBlur = 2.dp

/** 滑块和栏边缘之间的间距。为 0 时滑块正好贴满栏的边缘。 */
private val BarPadding = 2.dp

/**
 * 滑块平时的高度和圆角都从栏推出来，不单独给：高度是栏高减去上下间距，圆角是栏的圆角
 * 减去间距（两圈边同心）。这样不管栏怎么调，滑块平时最多贴到栏的边缘，不会戳出去——
 * 单独给的话，滑块和栏一样高、圆角又比栏小，四个角就会从栏的圆角外面露出来。
 */
private val SliderHeight = BarHeight - BarPadding * 2
private val BarShape = LargeShape
private val SliderShape = RoundedCornerShape(GlassRadius.Large - BarPadding)

/**
 * 按住时滑块放大到原来的几倍。会鼓出栏外，每边溢出滑块高度的 (倍数 − 1) / 2，
 * 1.4 就是上下各 20%。外层给栏留的上下间距要容得下这一截。
 */
private const val PressedSliderScale = 1.4f

/** 按住时整条栏跟着胀大的倍数。 */
private const val PressedBarScale = 1.05f
