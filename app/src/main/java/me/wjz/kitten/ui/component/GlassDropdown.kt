package me.wjz.kitten.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.max
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
 * @param label 槽上方的标签。为 null 时不占那一行，给放在紧凑卡片里、旁边已经有标题的场合用。
 * @param onRemove 传了的话，每一项右边多一个"×"，点了把这一项删掉（菜单不收起）。给用户能
 *   自己增删的列表用，比如模型名。
 * @param footer 菜单底部固定的一块，不跟着列表滚动，和列表之间隔一道细线。参数是菜单自己那层
 *   玻璃（里面的玻璃控件该折射它）和收起菜单的方法。给"在列表里手动加一项"这类输入用。
 */
@Composable
fun <T> GlassDropdown(
    items: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    itemLabel: (T) -> String,
    label: String?,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    onRemove: ((T) -> Unit)? = null,
    footer: (@Composable (menuBackdrop: Backdrop, close: () -> Unit) -> Unit)? = null,
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
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = lerp(colors.textSecondary, colors.accentStrong, open),
            )
        }
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

    GlassOverlay(visible = menuVisible) { host, behind ->
        DropdownMenuPanel(
            host = host,
            behind = behind,
            anchorBounds = anchorBounds,
            contentHeight = ItemHeight * items.size,
            progress = { progress.value },
            backdrop = backdrop,
            onDismiss = { expanded = false },
            footer = if (footer != null) {
                { menuBackdrop -> footer(menuBackdrop) { expanded = false } }
            } else {
                null
            },
        ) {
            items.forEach { item ->
                MenuItem(
                    text = itemLabel(item),
                    selected = item == selected,
                    onClick = {
                        onSelect(item)
                        expanded = false
                    },
                    onRemove = onRemove?.let { remove -> { remove(item) } },
                )
            }
        }
    }
}

/** 分组菜单里的一组：组名和这一组的选项。 */
@Immutable
class GlassMenuGroup<T>(val title: String, val items: List<T>)

/**
 * 分组的玻璃下拉菜单：每组一个小标题，下面列着这一组的选项。比如对话页输入栏上的模型切换——
 * 每家服务商一组，组里是它的模型。
 *
 * 和 [GlassDropdown] 是同一块玻璃、同一套弹簧和交互（按下亮起、松手才算数；点外面或者按返回键
 * 收起），区别在：
 *
 * - **不自带触发它的槽。** 用什么打开由调用方决定（按钮、一行字……），这里只要那个东西在窗口里
 *   的位置 [anchorBounds]，菜单摆在它正上方或正下方；开没开也由调用方管（[expanded]）。
 * - **可以比触发它的东西宽。** 触发它的多半是个小按钮，菜单至少 [GroupedMenuMinWidth] 宽：先和
 *   按钮左边对齐往右伸，右边放不下就和按钮右边对齐往左伸。
 * - **每组最多露出 [maxVisibleItems] 项。** 多出来的在组里上下滑，一家塞了几十个选项也不会把菜单
 *   撑得老长；组的上下边缘淡出，提示还能滑。组多到整个菜单放不下时，整个菜单再滚。
 *
 * 选中一项先调 [onSelect]，再调 [onDismissRequest] 收起。
 *
 * 和 [GlassDropdown] 一样画在 [GlassOverlayHost] 上，外面必须有一个 host。
 *
 * @param anchorBounds 触发它的东西在窗口里的位置。是个函数：只在菜单挂着时才读，平时那个东西跟着
 *   页面、键盘动来动去，不会引起重组。
 * @param selected 当前选中的项，打勾。打开时它所在的组会滚到让它露出来。
 * @param backdrop 菜单背后的背景，同 [GlassDropdown]：只要背景，底下的界面浮层自己会录。
 */
@Composable
fun <T> GlassGroupedMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchorBounds: () -> Rect,
    groups: List<GlassMenuGroup<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    itemLabel: (T) -> String,
    backdrop: Backdrop,
    maxVisibleItems: Int = DefaultMaxVisibleItems,
) {
    val colors = KittenTheme.colors
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(expanded) {
        progress.animateTo(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = if (expanded) OpenSpec else CloseSpec,
        )
    }
    // 收起的动画放完之前菜单还得挂着。
    val menuVisible by remember { derivedStateOf { expanded || progress.value > 0f } }

    BackHandler(enabled = expanded) { currentOnDismiss() }

    GlassOverlay(visible = menuVisible) { host, behind ->
        DropdownMenuPanel(
            host = host,
            behind = behind,
            anchorBounds = anchorBounds(),
            contentHeight = groups.fold(0.dp) { sum, group ->
                sum + GroupHeaderHeight + ItemHeight * min(group.items.size, maxVisibleItems)
            } + GroupDividerHeight * (groups.size - 1).coerceAtLeast(0),
            minMenuWidth = GroupedMenuMinWidth,
            progress = { progress.value },
            backdrop = backdrop,
            onDismiss = currentOnDismiss,
            footer = null,
        ) {
            groups.forEachIndexed { index, group ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = GroupDividerPadding)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.hairline),
                    )
                }
                MenuGroupSection(
                    group = group,
                    selected = selected,
                    maxVisibleItems = maxVisibleItems,
                    itemLabel = itemLabel,
                    onSelect = { item ->
                        onSelect(item)
                        currentOnDismiss()
                    },
                )
            }
        }
    }
}

/**
 * 分组菜单里的一组：组名，下面是选项。选项超过 [maxVisibleItems] 个时，这一组只露出那么高，
 * 多的在组里滑，上下边缘淡出（[fadingEdges]）。
 */
@Composable
private fun <T> MenuGroupSection(
    group: GlassMenuGroup<T>,
    selected: T?,
    maxVisibleItems: Int,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit,
) {
    val colors = KittenTheme.colors
    val items: @Composable () -> Unit = {
        group.items.forEach { item ->
            MenuItem(
                text = itemLabel(item),
                selected = item == selected,
                onClick = { onSelect(item) },
                onRemove = null,
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        // 组名：比选项（bodyMedium，14sp 常规）大一号、加粗，压得住下面那一列选项，不会头轻脚重；
        // 用深一档的天依蓝，和黑色的选项一眼分得开是标题。和选项的字左边对齐。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(GroupHeaderHeight)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = group.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentStrong,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (group.items.size <= maxVisibleItems) {
            items()
        } else {
            val density = LocalDensity.current
            // 打开时让选中的那一项露出来，尽量摆在中间。超过能滚的范围时 ScrollState 自己会收回来。
            val scroll = rememberScrollState(
                initial = with(density) {
                    val index = group.items.indexOfFirst { it == selected }
                    (ItemHeight * (index - maxVisibleItems / 2).coerceAtLeast(0)).roundToPx()
                },
            )
            Column(
                modifier = Modifier
                    .height(ItemHeight * maxVisibleItems)
                    .fadingEdges(scroll)
                    .verticalScroll(scroll),
            ) {
                items()
            }
        }
    }
}

/**
 * 能滚的那一组上下边缘淡出，提示还能滑：上面还有内容就淡出上沿，下面还有就淡出下沿。
 *
 * 淡出的高度跟着离尽头还有多远走，滚到头时正好收成零，最后一项完整露出来，不会"啪"地一下
 * 突然变清楚。
 *
 * 用 DstIn 擦掉内容的 alpha，所以要单独一层离屏画（Offscreen），不然擦掉的是菜单的玻璃。
 */
private fun Modifier.fadingEdges(scroll: ScrollState): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val maxFade = EdgeFade.toPx()
            val topFade = min(scroll.value.toFloat(), maxFade)
            val bottomFade = min((scroll.maxValue - scroll.value).toFloat(), maxFade)
            if (topFade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), endY = topFade),
                    size = Size(size.width, topFade),
                    blendMode = BlendMode.DstIn,
                )
            }
            if (bottomFade > 0f) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Black, Color.Transparent),
                        startY = size.height - bottomFade,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - bottomFade),
                    size = Size(size.width, bottomFade),
                    blendMode = BlendMode.DstIn,
                )
            }
        }

/**
 * 菜单那块玻璃，[GlassDropdown] 和 [GlassGroupedMenu] 共用：摆在 [anchorBounds]（窗口坐标）正下方，
 * 下面放不下、上面空间更大时摆在正上方；外面一层遮罩，点外面收起。
 *
 * @param contentHeight 列表部分想要多高，不含四周的留白和 [footer]。放不下时列表在菜单里滚动。
 * @param minMenuWidth 菜单最窄多宽。默认和 [anchorBounds] 一样宽；要比它宽时，先和它左边对齐往右伸，
 *   右边放不下再和它右边对齐往左伸。（不叫 minWidth：BoxWithConstraints 里有个同名的约束。）
 */
@Composable
private fun DropdownMenuPanel(
    host: GlassOverlayHostState,
    behind: Backdrop,
    anchorBounds: Rect,
    contentHeight: Dp,
    progress: () -> Float,
    backdrop: Backdrop,
    onDismiss: () -> Unit,
    footer: (@Composable (menuBackdrop: Backdrop) -> Unit)?,
    minMenuWidth: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KittenTheme.colors
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val insets = WindowInsets.systemBars.union(WindowInsets.ime)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // 背景 + 底下的界面（和模态窗之类先挂上来的浮层）：菜单透出来的是它真正盖住的东西，
    // 见 GlassOverlayHost。
    val menuBackdrop = rememberCombinedBackdrop(backdrop, behind)
    // 菜单自己那层玻璃（不含里面的内容），给底部那块里的玻璃控件折射：折射菜单背后的东西的话，
    // 控件透出来的是菜单外面的颜色，像在菜单上挖了个洞。
    val menuSurface = rememberLayerBackdrop()

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
            val left = insets.getLeft(this, layoutDirection) + MenuMargin.toPx()
            val right = constraints.maxWidth - insets.getRight(this, layoutDirection) - MenuMargin.toPx()
            val top = insets.getTop(this) + MenuMargin.toPx()
            val bottom = constraints.maxHeight - insets.getBottom(this) - MenuMargin.toPx()
            val gap = MenuGap.toPx()
            val footerHeight = if (footer != null) FooterHeight else 0.dp
            val wanted = (contentHeight + MenuPadding * 2 + footerHeight).toPx()
            val spaceBelow = bottom - (anchor.bottom + gap)
            val spaceAbove = (anchor.top - gap) - top
            // 下面放得下就往下开；放不下时哪边空间大往哪边开，放不下的部分在菜单里滚动。
            val openUp = spaceBelow < wanted && spaceAbove > spaceBelow
            val height = min(wanted, if (openUp) spaceAbove else spaceBelow).coerceAtLeast(0f)
            val y = if (openUp) anchor.top - gap - height else anchor.bottom + gap
            // 至少和槽一样宽，但不超出左右边界。
            val width = max(anchor.width, minMenuWidth.toPx()).coerceAtMost((right - left).coerceAtLeast(0f))
            val x = when {
                anchor.left + width <= right -> anchor.left
                anchor.right - width >= left -> anchor.right - width
                else -> right - width
            }.coerceIn(left, max(left, right - width))
            // 从槽那里长出来：横向对着槽的中间，纵向对着槽的那一边。
            val pivotX = if (width > 0f) ((anchor.center.x - x) / width).coerceIn(0f, 1f) else 0.5f

            Column(
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(width = width.toDp(), height = height.toDp())
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
                            alpha = p.coerceIn(0f, 1f)
                            scaleX = lerp(0.94f, 1f, p)
                            scaleY = lerp(0.85f, 1f, p)
                            transformOrigin = TransformOrigin(pivotX, if (openUp) 1f else 0f)
                        },
                        // 浮在页面上面，底下压着卡片里的字，玻璃给实一点。
                        onDrawSurface = { drawRect(colors.glassTintStrong) },
                        exportedBackdrop = menuSurface,
                    )
                    .padding(MenuPadding),
            ) {
                // 列表在上面、放不下的部分在这一块里滚动；底部那块固定在菜单最下面，不跟着滚。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    content = content,
                )
                if (footer != null) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = FooterDividerPadding)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.hairline),
                    )
                    footer(menuSurface)
                }
            }
        }
    }
}

/**
 * 菜单里的一项。选中项垫一层淡天依蓝、尾部打勾；按下时同一层再叠一遍，手指压着哪一项
 * 一眼就能看出来。
 *
 * [onRemove] 不为 null 时最右边多一个"×"，就是一个普通的图标，不做玻璃：它在一项里只占一小块，
 * 点它删掉这一项。它先消费了按下，所以点"×"时这一项不会跟着被选中。
 */
@Composable
private fun MenuItem(text: String, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
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
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除 $text",
                tint = colors.textTertiary,
                modifier = Modifier
                    .size(RemoveTouchSize)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Button,
                        onClick = onRemove,
                    )
                    .padding((RemoveTouchSize - RemoveIconSize) / 2),
            )
        }
    }
}

private val ItemHeight = 44.dp
private val MenuPadding = 6.dp

/** "×"的触摸区和图标大小：图标小、触摸区比图标大一圈，好点。 */
private val RemoveTouchSize = 32.dp
private val RemoveIconSize = 16.dp

/** 底部那块和列表之间那道细线上下的留白。 */
private val FooterDividerPadding = 6.dp

/** 底部那块整体有多高（细线连同留白 + 一个输入框），算菜单该开多高时用。 */
private val FooterHeight = FooterDividerPadding * 2 + 1.dp + 44.dp

/**
 * 分组菜单每组默认最多露出几项。输入栏在屏幕下半截，菜单往上开，两三组各露五项已经占掉大半屏，
 * 再多就放不下几组了。
 */
private const val DefaultMaxVisibleItems = 5

/**
 * 分组菜单最窄多宽。触发它的多半是个小按钮，跟着按钮的宽度走的话模型名一行放不下；
 * 260dp 放得下 `claude-sonnet-5-5`、`qwen2.5-72b-instruct` 这种长度。
 */
private val GroupedMenuMinWidth = 260.dp

/**
 * 组名那一行多高。比选项（[ItemHeight]）矮一截：它只是标题，不能点；但字是 16sp 的，
 * 再矮上下就贴着分隔线和第一项了。
 */
private val GroupHeaderHeight = 36.dp

/** 组和组之间那道细线上下的留白，以及整道分隔（留白 + 线）有多高，算菜单该开多高时用。 */
private val GroupDividerPadding = 4.dp
private val GroupDividerHeight = GroupDividerPadding * 2 + 1.dp

/** 能滚的组上下边缘最多淡出多高。小于半项：最边上那一项还看得出是什么字。 */
private val EdgeFade = 18.dp

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

/**
 * 菜单的折射量（折射带宽的倍数，卡片是 [DefaultRefraction]）。菜单底下压着一行行字和
 * 输入框，边缘把它们掰弯得明显，一眼就看得出这是一块浮着的玻璃。
 */
private const val MenuRefraction = 4f

/** 浮层的阴影比卡片的重：它是整页最上面的东西。 */
private val MenuShadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f))

/** 展开带一点过冲，像被弹出来的；收起不过冲，干脆利落。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 900f)

/** 槽按下时光雾的亮度，和按钮一样。 */
private const val GlowStrength = 0.25f
