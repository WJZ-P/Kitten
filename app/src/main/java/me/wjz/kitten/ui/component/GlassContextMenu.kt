package me.wjz.kitten.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 上下文菜单里的一项。
 *
 * @param destructive 删除这类收不回来的操作：图标和字用警示色。
 */
@Immutable
class GlassMenuItem(
    val text: String,
    val icon: ImageVector? = null,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * 上下文菜单开没开、从哪里弹出来。用 [rememberGlassContextMenuState] 建。
 *
 * 一般配合 [detectGlassPress] 的 onLongPress：长按时把手指的位置换成窗口坐标交给 [show]。
 */
@Stable
class GlassContextMenuState internal constructor(private val haptic: HapticFeedback) {

    var expanded by mutableStateOf(false)
        private set

    /** 菜单从哪一点弹出来，窗口坐标。 */
    internal var anchorInWindow by mutableStateOf(Offset.Zero)
        private set

    /** 在 [positionInWindow]（窗口坐标）处弹出菜单，带一下长按的震动。 */
    fun show(positionInWindow: Offset) {
        anchorInWindow = positionInWindow
        expanded = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun dismiss() {
        expanded = false
    }
}

@Composable
fun rememberGlassContextMenuState(): GlassContextMenuState {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { GlassContextMenuState(haptic) }
}

/**
 * 玻璃上下文菜单：长按某个东西时，在手指那里弹出来的一小块玻璃，列着能对它做的操作。
 *
 * - 弹出来、收回去都是弹簧：从手指那一点长出来，略冲过头再落定（[OpenSpec]）；收回时缩回
 *   手指那一点（[CloseSpec]）。
 * - 位置：左上角对着手指，往右下开；右边放不下往左开，下面放不下往上开，两边都放不下就贴着
 *   边。不会盖到系统栏和键盘。
 * - 交互和下拉菜单一样：菜单项按下亮起、在范围内松手才执行（执行前先收起菜单）；点菜单外面
 *   （抬手时）或者按返回键收起。菜单开着时外面不响应触摸。
 *
 * 画在 [GlassOverlayHost] 上，和下拉菜单一样，所以外面必须有一个 host。菜单只在 host 的范围里
 * 摆：放在抽屉自己的 host 里时，就只在抽屉里开。
 *
 * 菜单可以放在列表外面、几条共用一个：长按哪一条就记下是哪一条，[items] 按它来生成。这样
 * 菜单里删掉那一条之后，收起的动画还能放完——菜单要是长在那一条里面，会跟着它一起没掉。
 *
 * @param backdrop 菜单所在那一层背后的东西，和 [GlassDropdown] 的一样。
 */
@Composable
fun GlassContextMenu(
    state: GlassContextMenuState,
    items: List<GlassMenuItem>,
    backdrop: Backdrop,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(state.expanded) {
        progress.animateTo(
            targetValue = if (state.expanded) 1f else 0f,
            animationSpec = if (state.expanded) OpenSpec else CloseSpec,
        )
    }
    // 收起的弹簧放完之前菜单还得挂着。它会略冲过 0 再回来，所以看是不是正好停在 0。
    val visible by remember { derivedStateOf { state.expanded || progress.value != 0f } }

    BackHandler(enabled = state.expanded) { state.dismiss() }

    GlassOverlay(visible = visible) { host, behind ->
        ContextMenuPanel(
            host = host,
            behind = behind,
            anchorInWindow = state.anchorInWindow,
            progress = { progress.value },
            backdrop = backdrop,
            onDismiss = state::dismiss,
        ) {
            items.forEach { item ->
                ContextMenuItem(
                    item = item,
                    onClick = {
                        state.dismiss()
                        item.onClick()
                    },
                )
            }
        }
    }
}

@Composable
private fun ContextMenuPanel(
    host: GlassOverlayHostState,
    behind: Backdrop,
    anchorInWindow: Offset,
    progress: () -> Float,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = KittenTheme.colors
    val insets = WindowInsets.systemBars.union(WindowInsets.ime)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 背景 + 底下的界面：菜单透出来的是它真正盖住的东西，见 GlassOverlayHost。
    val menuBackdrop = rememberCombinedBackdrop(backdrop, behind)
    // 弹出那一刻手指在 host 里的位置。之后 host 自己动了（比如抽屉在收），菜单跟着 host 走，
    // 不钉在窗口上。只在弹出时换算一次，host 动的时候不用跟着重组。
    val anchor = remember(anchorInWindow) {
        Snapshot.withoutReadObservation { anchorInWindow - host.originInWindow }
    }
    // 手指在菜单里的相对位置，菜单从这一点长出来、缩回这一点。摆位置的时候才算得出来。
    var origin by remember { mutableStateOf(TransformOrigin(0f, 0f)) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 菜单外面：吞掉所有触摸，抬手时收起菜单。同下拉菜单。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "关闭菜单"
                    onClick {
                        currentOnDismiss()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        currentOnDismiss()
                    }
                },
        )

        Column(
            modifier = Modifier
                .layout { measurable, constraints ->
                    // 系统栏和键盘的尺寸是从窗口边缘量的。host 不一定铺满窗口（比如抽屉），但抽屉
                    // 也是上下顶满、贴着左边，照样能用。
                    val margin = MenuMargin.toPx()
                    val left = insets.getLeft(this, layoutDirection) + margin
                    val right = constraints.maxWidth - insets.getRight(this, layoutDirection) - margin
                    val top = insets.getTop(this) + margin
                    val bottom = constraints.maxHeight - insets.getBottom(this) - margin
                    val placeable = measurable.measure(
                        Constraints(
                            maxWidth = (right - left).roundToInt().coerceAtLeast(0),
                            maxHeight = (bottom - top).roundToInt().coerceAtLeast(0),
                        ),
                    )
                    val x = placeAlong(anchor.x, placeable.width, left, right)
                    val y = placeAlong(anchor.y, placeable.height, top, bottom)
                    origin = TransformOrigin(
                        pivotFractionX = ((anchor.x - x) / placeable.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                        pivotFractionY = ((anchor.y - y) / placeable.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                    )
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(x.roundToInt(), y.roundToInt())
                    }
                }
                // 菜单自己的留白处也要接住触摸，不然会落到下面的遮罩上，把菜单关掉。
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                .drawBackdrop(
                    backdrop = menuBackdrop,
                    shape = { MediumShape },
                    effects = { glassEffects(GlassRadius.Medium, MenuBlur, refraction = MenuRefraction) },
                    highlight = { GlassRim },
                    shadow = { MenuShadow },
                    layerBlock = {
                        val p = progress()
                        // 透明度走得比缩放快：缩放还在半路时菜单已经看得清了；收起时到一半才开始淡。
                        alpha = (p * 2f).coerceIn(0f, 1f)
                        val s = lerp(StartScale, 1f, p)
                        scaleX = s
                        scaleY = s
                        transformOrigin = origin
                    },
                    // 浮在页面上面，底下压着字，玻璃给实一点。同下拉菜单。
                    onDrawSurface = { drawRect(colors.glassTintStrong) },
                )
                // 宽度跟着最长的那一项走，但不窄于 MenuMinWidth。
                .widthIn(min = MenuMinWidth)
                .width(IntrinsicSize.Max)
                .padding(MenuPadding),
        ) {
            content()
        }
    }
}

/**
 * 一个方向上把菜单摆在哪：默认从手指往后开（往右、往下），放不下就往前开（往左、往上），
 * 两边都放不下就贴着边界。
 */
private fun placeAlong(anchor: Float, size: Int, start: Float, end: Float): Float {
    val wanted = when {
        anchor + size <= end -> anchor
        anchor - size >= start -> anchor - size
        else -> end - size
    }
    return wanted.coerceIn(start, max(start, end - size))
}

/** 菜单里的一项：图标 + 文字。按下垫一层淡天依蓝，和下拉菜单的选项一样。 */
@Composable
private fun ContextMenuItem(item: GlassMenuItem, onClick: () -> Unit) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val tint = if (item.destructive) colors.danger else colors.textPrimary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ItemHeight)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnClick() } }
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) {
                    drawRoundRect(colors.sliderIdle, cornerRadius = CornerRadius(ItemRadius.toPx()), alpha = pressed)
                }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (item.icon != null) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(ItemIconSize),
            )
        }
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ItemHeight = 44.dp
private val ItemIconSize = 20.dp
private val MenuPadding = 6.dp

/** 菜单最窄多宽。只有一两个字的菜单项时，菜单也不该缩成一小条。 */
private val MenuMinWidth = 160.dp

/** 菜单项高亮的圆角：菜单圆角减去留白，两圈边才同心。 */
private val ItemRadius = GlassRadius.Medium - MenuPadding

/** 菜单离系统栏、键盘和 host 的边至少留这么多。 */
private val MenuMargin = 8.dp

/** 菜单的模糊和折射，和下拉菜单一样：底下压着一行行字，糊到只剩一团团颜色。 */
private val MenuBlur = 8.dp
private const val MenuRefraction = 4f

/** 浮层的阴影比卡片的重：它是整页最上面的东西。 */
private val MenuShadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f))

/** 弹出时从多大开始长。越小，"从手指里长出来"越明显。 */
private const val StartScale = 0.5f

/**
 * 弹出：阻尼小于 1，略冲过头（菜单比最终大一点）再落定，像被弹出来的。dampingRatio 越小
 * 晃得越厉害，stiffness 越大越快。
 */
private val OpenSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 500f)

/**
 * 收回：也是弹簧，但阻尼大一些、更硬，干脆地缩回手指那一点，不在原地晃。冲过 0 的那一下
 * 已经完全透明，看不见。
 */
private val CloseSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 700f)
