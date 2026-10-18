package me.wjz.kitten.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 玻璃下拉菜单。
 *
 * 收起时是一个和输入框同款的玻璃槽（[glassWell]），显示当前选中项和一个箭头；点开后在
 * 它正下方浮出一块玻璃菜单，从槽的那一边长出来。下面放不下、上面空间更大时往上开。
 *
 * 交互照老规矩：按下就有反馈，松手才算数。槽和菜单项都是按下亮起、在范围内抬手才生效；
 * 点菜单外面任意位置（同样是抬手时）或者按返回键，收起菜单。菜单开着时外面的页面不响应
 * 触摸，免得一边开着菜单一边把页面滚走。
 *
 * 菜单画在 [GlassOverlayHost] 上而不是 Popup 里，原因见那边。所以用到它的界面外面必须有
 * 一个 [GlassOverlayHost]。
 *
 * @param selected 当前选中的项。为 null 时槽里显示 [placeholder]——比如用户手填了一个
 *   不在列表里的值。
 */
@Composable
fun <T> GlassDropdown(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    itemLabel: (T) -> String,
    label: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    placeholder: String = "",
) {
    val colors = KittenTheme.colors
    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) OpenSpec else CloseSpec,
        )
    }
    // 收起的动画放完之前菜单还得挂着。
    val menuVisible by remember { derivedStateOf { expanded || progress.value > 0f } }
    // 窗口坐标。只在菜单挂着时才有人读，平时滚动页面时更新它不会引起重组。
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val press = rememberGlassPressState()
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }

    fun toggle() {
        // 键盘开着时先收起来：它一收，界面会往下回弹，菜单就对不上槽了。
        if (!expanded) focusManager.clearFocus()
        expanded = !expanded
    }

    BackHandler(enabled = expanded) { expanded = false }

    val open = progress.value.coerceIn(0f, 1f)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = lerp(colors.textSecondary, colors.accentStrong, open),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { anchorBounds = Rect(it.positionInWindow(), it.size.toSize()) }
                .semantics(mergeDescendants = true) {
                    role = Role.DropdownList
                    stateDescription = if (expanded) "已展开" else "已收起"
                    onClick(label = if (expanded) "收起" else "展开") {
                        toggle()
                        true
                    }
                }
                .pointerInput(press) { detectGlassPress(press) { toggle() } }
                .glassWell(backdrop = backdrop, colors = colors, active = open) {
                    drawGlassGlow(
                        glow = glow,
                        intensity = press.pressProgress.coerceIn(0f, 1f) * GlowStrength,
                        center = press.position,
                        colors = colors.glowOnGlass,
                    )
                }
                .heightIn(min = 44.dp)
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = selected?.let(itemLabel) ?: placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected != null) colors.textPrimary else colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
            )
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = 180f * progress.value },
            )
        }
    }

    GlassOverlay(visible = menuVisible) { host ->
        DropdownMenuPanel(
            host = host,
            anchorBounds = anchorBounds,
            itemCount = items.size,
            progress = { progress.value },
            backdrop = backdrop,
            onDismiss = { expanded = false },
        ) {
            items.forEach { item ->
                MenuItem(
                    text = itemLabel(item),
                    selected = item == selected,
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun DropdownMenuPanel(
    host: GlassOverlayHostState,
    anchorBounds: Rect,
    itemCount: Int,
    progress: () -> Float,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KittenTheme.colors
    val density = LocalDensity.current
    val insets = WindowInsets.systemBars.union(WindowInsets.ime)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 背景 + 底下的界面：菜单透出来的是它真正盖住的东西，见 GlassOverlayHost。
    val menuBackdrop = rememberCombinedBackdrop(backdrop, host.contentBackdrop)

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // 菜单外面：吞掉所有触摸，抬手时收起菜单。
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

        with(density) {
            // 槽的位置是窗口坐标，换算成 host 里的。系统栏和键盘的尺寸是从窗口边缘量的，
            // host 铺满整个窗口（见 KittenApp），直接拿来当上下边界。
            val anchor = anchorBounds.translate(-host.originInWindow)
            val top = insets.getTop(this) + MenuMargin.toPx()
            val bottom = constraints.maxHeight - insets.getBottom(this) - MenuMargin.toPx()
            val gap = MenuGap.toPx()
            val wanted = (ItemHeight * itemCount + MenuPadding * 2).toPx()
            val spaceBelow = bottom - (anchor.bottom + gap)
            val spaceAbove = (anchor.top - gap) - top
            // 下面放得下就往下开；放不下时哪边空间大往哪边开，放不下的部分在菜单里滚动。
            val openUp = spaceBelow < wanted && spaceAbove > spaceBelow
            val height = min(wanted, if (openUp) spaceAbove else spaceBelow).coerceAtLeast(0f)
            val y = if (openUp) anchor.top - gap - height else anchor.bottom + gap

            Column(
                modifier = Modifier
                    .offset { IntOffset(anchor.left.roundToInt(), y.roundToInt()) }
                    .size(width = anchor.width.toDp(), height = height.toDp())
                    // 菜单自己的留白处也要接住触摸，不然会落到下面的遮罩上，把菜单关掉。
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                    .drawBackdrop(
                        backdrop = menuBackdrop,
                        shape = { MediumShape },
                        effects = { glassEffects(GlassRadius.Medium, MenuBlur) },
                        highlight = { GlassRim },
                        shadow = { MenuShadow },
                        layerBlock = {
                            val p = progress()
                            alpha = p.coerceIn(0f, 1f)
                            scaleX = lerp(0.94f, 1f, p)
                            scaleY = lerp(0.85f, 1f, p)
                            // 从槽的那一边长出来。
                            transformOrigin = TransformOrigin(0.5f, if (openUp) 1f else 0f)
                        },
                        // 浮在页面上面，底下压着卡片里的字，玻璃给实一点。
                        onDrawSurface = { drawRect(colors.glassTintStrong) },
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(MenuPadding),
                content = content,
            )
        }
    }
}

/**
 * 菜单里的一项。选中项垫一层淡天依蓝、尾部打勾；按下时同一层再叠一遍，手指压着哪一项
 * 一眼就能看出来。
 */
@Composable
private fun MenuItem(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ItemHeight)
            .semantics(mergeDescendants = true) {
                this.selected = selected
                onClick {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnClick() } }
            .drawBehind {
                val radius = CornerRadius(ItemRadius.toPx())
                if (selected) drawRoundRect(colors.sliderIdle, cornerRadius = radius)
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) drawRoundRect(colors.sliderIdle, cornerRadius = radius, alpha = pressed)
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = colors.accentStrong,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private val ItemHeight = 44.dp
private val MenuPadding = 6.dp

/** 菜单项高亮的圆角：菜单圆角减去留白，两圈边才同心。 */
private val ItemRadius = GlassRadius.Medium - MenuPadding

/** 菜单和槽之间的缝。 */
private val MenuGap = 6.dp

/** 菜单离系统栏、键盘至少留这么多。 */
private val MenuMargin = 8.dp

/**
 * 菜单的模糊比卡片重得多：它底下压着的是一行行字和输入框，糊轻了（试过 12dp）字的影子
 * 和输入框的白条都还在，和菜单项叠在一起显得乱。糊到只剩一团团颜色（天依蓝的按钮、
 * 卡片的底色），看得出底下有什么，又不干扰阅读。
 */
private val MenuBlur = 8.dp

/** 浮层的阴影比卡片的重：它是整页最上面的东西。 */
private val MenuShadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f))

/** 展开带一点过冲，像被弹出来的；收起不过冲，干脆利落。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f)

/** 槽按下时光雾的亮度，和按钮一样。 */
private const val GlowStrength = 0.25f
