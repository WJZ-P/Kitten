package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.component.GlassBlur
import me.wjz.kitten.ui.component.GlassDragStyle
import me.wjz.kitten.ui.component.GlassGroupedMenu
import me.wjz.kitten.ui.component.GlassMenuGroup
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassRim
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.dragStretch
import me.wjz.kitten.ui.component.drawGlassGlow
import me.wjz.kitten.ui.component.glassEffects
import me.wjz.kitten.ui.component.rememberGlassGlow
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.component.rememberPressMorph
import me.wjz.kitten.ui.icon.Android
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 输入栏上的模型切换：发送按钮左边一个玻璃小圆，里面是现在用的模型是哪一家的图标（[ModelBrand]）；
 * 点开是按服务商分组的菜单（[GlassGroupedMenu]），每组最多露出 5 个模型，多的在组里滑。选一个就切过去。
 *
 * 切换改的是和设置页同一份配置：那一家设为当前，它选中的模型换成这个（见
 * [ChatViewModel.selectModel]）。回设置页看，也是切过之后的样子。
 *
 * 只列填好了 Key、有可选模型的服务商；一家都没有时整个不显示——输入框里已经提示去设置页配了。
 *
 * @param backdrop 按钮折射的东西：输入栏自己那层玻璃，和旁边的"+"、发送一样。
 * @param menuBackdrop 菜单背后的背景，见 [GlassGroupedMenu]。
 * @param enabled 回复期间不能切：这一轮用的配置在开始时就定了，中途切了也不会生效，按钮还能点
 *   反而误导。
 */
@Composable
fun ModelSwitcher(
    groups: List<ModelGroup>,
    activeProfileId: String?,
    activeModel: String?,
    onSelect: (profileId: String, model: String) -> Unit,
    enabled: Boolean,
    backdrop: Backdrop,
    menuBackdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) return

    val focusManager = LocalFocusManager.current
    var expanded by remember { mutableStateOf(false) }
    // 按钮在窗口里的位置。只在菜单挂着时才有人读，平时键盘起落、输入栏跟着动，更新它不会引起重组。
    var anchorBounds by remember { mutableStateOf(Rect.Zero) }
    val menuGroups = remember(groups) {
        groups.map { group ->
            GlassMenuGroup(title = group.name, items = group.models.map { ModelChoice(group.profileId, it) })
        }
    }
    // 不同服务商可能有同名的模型（比如中转和官方都有 gpt-4o），所以选中项按"哪家 + 哪个"认。
    val selected = if (activeProfileId != null && activeModel != null) {
        ModelChoice(activeProfileId, activeModel)
    } else {
        null
    }
    val activePreset = groups.firstOrNull { it.profileId == activeProfileId }?.preset
    val brand = remember(activePreset, activeModel) { modelBrandOf(activePreset, activeModel) }

    ModelSwitchButton(
        brand = brand,
        modelName = activeModel,
        expanded = expanded,
        enabled = enabled,
        onClick = {
            // 键盘开着时先收起来：菜单能多出半屏的地方。键盘收起时输入栏往下落，菜单跟着按钮走。
            if (!expanded) focusManager.clearFocus()
            expanded = !expanded
        },
        onBoundsChanged = { anchorBounds = it },
        backdrop = backdrop,
        modifier = modifier,
    )

    GlassGroupedMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
        anchorBounds = { anchorBounds },
        groups = menuGroups,
        selected = selected,
        onSelect = { onSelect(it.profileId, it.model) },
        itemLabel = { it.model },
        backdrop = menuBackdrop,
    )
}

/** 菜单里的一项：哪一家的哪个模型。 */
private data class ModelChoice(val profileId: String, val model: String)

/**
 * 画着模型图标的玻璃小圆，和旁边"+"、发送两个圆一样大，三个圆排成一排。以前是写着模型名的胶囊，
 * 名字一长就挤，换成图标：哪一家一眼看得出来，具体哪个模型点开菜单就有（读屏会念出模型名）。
 *
 * 玻璃和按压反馈和 [me.wjz.kitten.ui.component.GlassIconButton] 一样（按下鼓起、磨砂退场变成透镜，
 * 光雾跟着手指，按住拖动跟手拉长），没直接用它：它会把图标整个染成一种颜色，各家的 logo 要原样画。
 *
 * 看得见的圆只有 [ButtonSize]，四周各多出一截触摸区，凑够 [TouchSize]。手指的位置是外面触摸区的坐标，
 * 画光雾、拉长时换算到圆里。
 */
@Composable
private fun ModelSwitchButton(
    brand: ModelBrand?,
    modelName: String?,
    expanded: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val morph = rememberPressMorph(press, MinMorphMillis, MorphInSpec, MorphOutSpec)
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }

    Box(
        modifier = modifier
            .semantics {
                role = Role.DropdownList
                contentDescription = if (modelName != null) "切换模型，现在用的是 $modelName" else "选择模型"
                stateDescription = if (expanded) "已展开" else "已收起"
                if (enabled) {
                    this.onClick(label = "切换模型") {
                        currentOnClick()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .pointerInput(press, enabled) {
                if (enabled) detectGlassPress(press, followDrag = true) { currentOnClick() }
            }
            .alpha(if (enabled) 1f else DisabledAlpha)
            .padding(TouchInset),
    ) {
        Box(
            modifier = Modifier
                // 量的是看得见的那个圆，菜单离它 6dp 开，不是离触摸区。
                .onGloballyPositioned { onBoundsChanged(Rect(it.positionInWindow(), it.size.toSize())) }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        // 按下时磨砂退场、折射变强，变成一块透镜；松手再磨砂回去。和别的按钮一样。
                        val p = morph.value.coerceIn(0f, 1f)
                        glassEffects(GlassRadius.Small, blurRadius = GlassBlur * (1f - p), press = p)
                    },
                    highlight = { GlassRim },
                    shadow = { ButtonShadow },
                    innerShadow = {
                        val p = morph.value.coerceIn(0f, 1f)
                        InnerShadow(radius = 4.dp * p, alpha = p)
                    },
                    layerBlock = {
                        // 收回时 morph 会略冲过 0，这里正好弹一下。
                        val s = lerp(1f, PressedScale, morph.value)
                        scaleX = s
                        scaleY = s
                        val inset = TouchInset.toPx()
                        val grab = press.downPosition
                        dragStretch(
                            drag = press.dragOffset,
                            grab = if (grab.isSpecified) grab - Offset(inset, inset) else grab,
                            style = ButtonDragStyle,
                        )
                    },
                    onDrawSurface = {
                        // 磨砂的那层底色：按下时退场，把舞台交给折射。
                        drawRect(colors.controlTint, alpha = 1f - morph.value.coerceIn(0f, 1f))
                        val inset = TouchInset.toPx()
                        val position = press.position
                        drawGlassGlow(
                            glow = glow,
                            intensity = press.pressProgress.coerceIn(0f, 1f) * GlowStrength,
                            center = if (position.isSpecified) position - Offset(inset, inset) else position,
                            colors = colors.glowOnGlass,
                        )
                    },
                )
                .size(ButtonSize),
            contentAlignment = Alignment.Center,
        ) {
            // 换到了另一家：旧图标边缩小边往上滑出去，新图标从下面边长大边滑进来（见 brandIconTransition）。
            // 同一家里换模型，图标没变，不动。
            AnimatedContent(
                targetState = brand,
                transitionSpec = { brandIconTransition() },
                contentAlignment = Alignment.Center,
                label = "modelBrandIcon",
                // 铺满整个圆再裁成圆：滑动的距离按圆的高度算，滑出去的图标正好整个离开玻璃，露在外面的
                // 那一截被裁掉。和发送换成停止时一样。
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape),
            ) { target ->
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    ModelBrandIcon(brand = target, modifier = Modifier.size(IconSize))
                }
            }
        }
    }
}

/**
 * 换一家时图标怎么换：旧的边缩小边往上滑出圆，新的从圆底下边长大边滑进来，像换上来一张牌。
 *
 * 滑动的距离是整个圆的高度，正好整个进出玻璃；缩放到 [IconHiddenScale]，滑到圆边时已经小了一圈；
 * 顺带淡一点，碰到圆边被裁掉的那一下不那么硬。滑动和缩放用同一个略带回弹的弹簧，新图标落定时会
 * 微微冲大一点再回来。
 */
private fun brandIconTransition(): ContentTransform {
    val enter = slideInVertically(IconSlideSpec) { height -> height } +
        scaleIn(IconScaleSpec, initialScale = IconHiddenScale) +
        fadeIn(IconFadeSpec)
    val exit = slideOutVertically(IconSlideSpec) { height -> -height } +
        scaleOut(IconScaleSpec, targetScale = IconHiddenScale) +
        fadeOut(IconFadeSpec)
    return enter togetherWith exit
}

/**
 * 一家的图标。认得出是谁家的，画那一家的 logo，原样的颜色；认不出画安卓小机器人。设置页的 Token 看板
 * 按模型列用量时也用它。
 */
@Composable
internal fun ModelBrandIcon(brand: ModelBrand?, modifier: Modifier = Modifier) {
    if (brand != null) {
        Icon(
            painter = painterResource(brand.icon),
            contentDescription = null,
            // Icon 默认会按内容色把图标整个染成一种颜色，彩色的 logo 就成了一块色块。
            tint = Color.Unspecified,
            modifier = modifier,
        )
    } else {
        Icon(
            imageVector = KittenIcons.Android,
            contentDescription = null,
            // 安卓的绿。别家的 logo 都带着自己的颜色，它也得有颜色：灰的摆在旁边像是不能点。
            // 想换颜色改这里，从 KittenColors 里挑。
            tint = KittenTheme.colors.iconGreen,
            modifier = modifier,
        )
    }
}

/** 看得见的圆多大，和旁边"+"、发送一样（输入栏的 ActionButtonSize）。 */
private val ButtonSize = 40.dp

/** 触摸区多大，和旁边两个圆按钮的触摸区一样（48dp）。 */
private val TouchSize = 48.dp

/** 看得见的圆四周各多出来的那截触摸区。 */
private val TouchInset = (TouchSize - ButtonSize) / 2

/**
 * 圆里的图标多大。比"+"、发送的图标小一点：各家 logo 大多是铺满整个画布的实心图形，一样大的话
 * 看起来比旁边的线条图标重。
 */
private val IconSize = 22.dp

/**
 * 换图标时滑动的弹簧：略冲过头一点再落定。和发送换成停止时的一样（ChatInputBar 的 IconSlideSpec）。
 * dampingRatio 越小晃得越多，stiffness 越大越快。
 */
private val IconSlideSpec = spring(
    dampingRatio = 0.75f,
    stiffness = 500f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** 换图标时缩放的弹簧，和滑动同一个手感：变大、变小和滑动一起到位。 */
private val IconScaleSpec = spring<Float>(dampingRatio = 0.75f, stiffness = 500f)

/** 换图标时淡入淡出：比滑动快，滑到一半就已经看得清了。和发送换成停止时一样。 */
private val IconFadeSpec = spring<Float>(stiffness = 900f)

/** 换图标时，旧图标缩到多小离开、新图标从多小开始长：1 就是不缩放，只滑动。 */
private const val IconHiddenScale = 0.4f

/** 按下鼓起多少，和旁边的圆按钮（GlassIconButton）一样。 */
private const val PressedScale = 1.25f

/** 快速点按时，在最鼓的状态至少停多久（毫秒）再收回，见 [rememberPressMorph]。 */
private const val MinMorphMillis = 100L

/** 鼓起：快，带一点过冲。和别的按钮一样。 */
private val MorphInSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 收回：软一点，晃一下，像液体落回去。和别的按钮一样。 */
private val MorphOutSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)

/**
 * 按住拖动时的形变，和旁边的圆按钮一样，各项含义见 [GlassDragStyle]。圆很小，拉长的比例给得大；
 * 不歪：圆转了看不出来，只会把图标转歪。
 */
private val ButtonDragStyle = GlassDragStyle(range = 60.dp, stretch = 0.15f, squeeze = 0.5f, tilt = 0f)

/** 光雾的最大亮度，和按钮一样。 */
private const val GlowStrength = 0.25f

/** 不能点时淡到多少，和按钮一样。 */
private const val DisabledAlpha = 0.4f

private val ButtonShadow = Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
