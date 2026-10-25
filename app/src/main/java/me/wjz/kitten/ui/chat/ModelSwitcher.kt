package me.wjz.kitten.ui.chat

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
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
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 输入栏上的模型切换：发送按钮左边一个玻璃小按钮，写着现在用的模型；点开是按服务商分组的
 * 菜单（[GlassGroupedMenu]），每组最多露出 5 个模型，多的在组里滑。选一个就切过去。
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

    ModelSwitchButton(
        label = activeModel ?: "选择模型",
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
 * 写着模型名的玻璃小胶囊，两头半圆，和旁边"+"、发送两个圆是一家。不带下拉的尖角：输入栏上
 * 的玻璃一看就是能点的，多一个尖角只是占地方。
 *
 * 玻璃和按压反馈和 [me.wjz.kitten.ui.component.GlassButton] 的透明款一样（按下鼓起、磨砂退场
 * 变成透镜，光雾跟着手指，按住拖动跟手拉长），没直接用它：GlassButton 是卡片里那一档的小圆角，
 * 这里要胶囊。
 *
 * 看得见的部分只有 [ButtonHeight] 高，上下各多出一截触摸区，凑够 [TouchHeight]，和旁边两个圆
 * 按钮一样好按。手指的位置是外面触摸区的坐标，画光雾、拉长时换算到里面。
 */
@Composable
private fun ModelSwitchButton(
    label: String,
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
            .semantics(mergeDescendants = true) {
                role = Role.DropdownList
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
            .padding(vertical = TouchInset),
    ) {
        Box(
            modifier = Modifier
                // 量的是看得见的那块，菜单离它 6dp 开，不是离触摸区。
                .onGloballyPositioned { onBoundsChanged(Rect(it.positionInWindow(), it.size.toSize())) }
                .drawBackdrop(
                    backdrop = backdrop,
                    // 胶囊：CircleShape 是 50% 圆角，放在扁长条上两头就是半圆。它是 CornerBasedShape，
                    // 高光那圈边认得（见 GlassTokens）。
                    shape = { CircleShape },
                    effects = {
                        // 按下时磨砂退场、折射变强，变成一块透镜；松手再磨砂回去。和别的按钮一样。
                        // 折射带宽按小一档的圆角给，和旁边的圆按钮一样：给到胶囊的半高，整块都在
                        // 折射带里，透出来全是扭曲。
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
                        val grab = press.downPosition
                        dragStretch(
                            drag = press.dragOffset,
                            grab = if (grab.isSpecified) grab - Offset(0f, TouchInset.toPx()) else grab,
                            style = ButtonDragStyle,
                        )
                    },
                    onDrawSurface = {
                        // 磨砂的那层底色：按下时退场，把舞台交给折射。
                        drawRect(colors.controlTint, alpha = 1f - morph.value.coerceIn(0f, 1f))
                        val position = press.position
                        drawGlassGlow(
                            glow = glow,
                            intensity = press.pressProgress.coerceIn(0f, 1f) * GlowStrength,
                            center = if (position.isSpecified) position - Offset(0f, TouchInset.toPx()) else position,
                            colors = colors.glowOnGlass,
                        )
                    },
                )
                .height(ButtonHeight)
                .widthIn(max = ButtonMaxWidth)
                .padding(horizontal = ButtonPaddingHorizontal),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 按钮看得见的部分多高。比旁边的圆（40dp）矮一截：它是个次要的开关，不该和发送抢眼；
 * 但也不能太矮，字是 14sp 的，再矮上下就贴边了。
 */
private val ButtonHeight = 34.dp

/** 触摸区多高，和旁边两个圆按钮的触摸区一样（48dp）。 */
private val TouchHeight = 48.dp

/** 看得见的部分上下各多出来的那截触摸区。 */
private val TouchInset = (TouchHeight - ButtonHeight) / 2

/**
 * 按钮最宽多宽，模型名再长就出省略号。它只是提示"现在用的是哪个"，看全名点开菜单就有；
 * 给宽了在输入栏上太抢眼（试过 180dp，用户嫌宽，减到一半）。
 */
private val ButtonMaxWidth = 90.dp

/** 字到胶囊两头的距离。两头是半圆，留白比方角按钮多一点，字才不会顶到弧上。 */
private val ButtonPaddingHorizontal = 14.dp

/** 按下鼓起多少。按钮小，和紧凑款的 GlassButton 一样给多一点才看得出来。 */
private const val PressedScale = 1.15f

/** 快速点按时，在最鼓的状态至少停多久（毫秒）再收回，见 [rememberPressMorph]。 */
private const val MinMorphMillis = 100L

/** 鼓起：快，带一点过冲。和别的按钮一样。 */
private val MorphInSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 收回：软一点，晃一下，像液体落回去。和别的按钮一样。 */
private val MorphOutSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)

/** 按住拖动时的形变，和 GlassButton 一样，各项含义见 [GlassDragStyle]。 */
private val ButtonDragStyle = GlassDragStyle(range = 80.dp, stretch = 0.08f, squeeze = 0.5f, tilt = 3f)

/** 光雾的最大亮度，和按钮一样。 */
private const val GlowStrength = 0.25f

/** 不能点时淡到多少，和按钮一样。 */
private const val DisabledAlpha = 0.4f

private val ButtonShadow = Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
