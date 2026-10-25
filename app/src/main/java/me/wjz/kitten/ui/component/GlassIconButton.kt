package me.wjz.kitten.ui.component

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 圆形的玻璃图标按钮，比如模态窗右上角的关闭。
 *
 * 和 [GlassButton] 的透明款是同一种玻璃、同一套按压反馈（鼓起、磨砂退场变成透镜 + 跟着手指
 * 的光雾 + 按住拖动时跟手拉长），只是做成圆的、只放一个图标。看得见的圆默认
 * [DefaultIconButtonSize]，四周留出触摸区，整体至少 [MinTouchSize] 见方——和 Material 的 IconButton
 * 一样大，换进去不用改布局。
 *
 * @param contentDescription 给读屏用。图标按钮没有文字，这里一定要写清楚它是干什么的。
 * @param size 看得见的那个圆有多大。触摸区至少 [MinTouchSize]，圆比它大时触摸区跟着圆走。
 * @param iconSize 圆里图标的大小。默认跟着圆等比例缩放（36dp 的圆配 24dp 的图标）。
 * @param touchSize 触摸区多大。默认至少 [MinTouchSize]；只有放在挤不下的地方（比如输入框的槽里，
 *   槽只有 44dp 高）才往小里给，不能比 [size] 小。
 * @param iconModifier 加在图标上的，比如加载时让图标转起来。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    size: Dp = DefaultIconButtonSize,
    iconSize: Dp = size * IconSizeRatio,
    touchSize: Dp = max(MinTouchSize, size),
    iconModifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val morph = rememberPressMorph(press, MinMorphMillis, MorphInSpec, MorphOutSpec)
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }
    val colors = KittenTheme.colors

    Box(
        modifier = modifier
            .size(touchSize)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                this.onClick(label = null) {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press, followDrag = true) { currentOnClick() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = {
                        // 按下时磨砂退场、折射变强，变成一块透镜；松手再磨砂回去。和别的按钮一样。
                        val p = morph.value.coerceIn(0f, 1f)
                        glassEffects(GlassRadius.Small, blurRadius = GlassBlur * (1f - p), press = p)
                    },
                    highlight = { GlassRim },
                    shadow = { IconButtonShadow },
                    innerShadow = {
                        val p = morph.value.coerceIn(0f, 1f)
                        InnerShadow(radius = 4.dp * p, alpha = p)
                    },
                    layerBlock = {
                        // 收回时 morph 会略冲过 0，这里正好弹一下。
                        val s = lerp(1f, PressedScale, morph.value)
                        scaleX = s
                        scaleY = s
                        // 按住拖动时跟着手指拉长，松手弹回。按下的位置是外面触摸区的坐标，换算到圆里。
                        val inset = (touchSize - size).toPx() / 2f
                        val grab = press.downPosition
                        dragStretch(
                            drag = press.dragOffset,
                            grab = if (grab.isSpecified) grab - Offset(inset, inset) else grab,
                            style = IconButtonDragStyle,
                        )
                    },
                    onDrawSurface = {
                        // 磨砂的那层底色：按下时退场，把舞台交给折射。
                        drawRect(colors.controlTint, alpha = 1f - morph.value.coerceIn(0f, 1f))
                        // 手指位置是外面触摸区的坐标，换算到圆里。
                        val inset = (touchSize - size).toPx() / 2f
                        val position = press.position
                        drawGlassGlow(
                            glow = glow,
                            intensity = press.pressProgress.coerceIn(0f, 1f) * GlowStrength,
                            center = if (position.isSpecified) position - Offset(inset, inset) else position,
                            colors = colors.glowOnGlass,
                        )
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = iconModifier.size(iconSize),
            )
        }
    }
}

/** 触摸区至少这么大，和 Material 的 IconButton 一样。 */
private val MinTouchSize = 48.dp

/** 看得见的那个圆，默认多大。 */
val DefaultIconButtonSize = 36.dp

/** 图标占圆的比例：36dp 的圆配 24dp 的图标。 */
private const val IconSizeRatio = 24f / 36f

/** 按下鼓起多少。圆很小，给得比长条按钮多一点才看得出来。 */
private const val PressedScale = 1.25f

/** 快速点按时，在最鼓的状态至少停多久（毫秒）再收回，见 [rememberPressMorph]。 */
private const val MinMorphMillis = 100L

/** 鼓起：快，带一点过冲。 */
private val MorphInSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 收回：软一点，晃一下，像液体落回去。 */
private val MorphOutSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)

/**
 * 按住拖动时的形变，各项含义见 [GlassDragStyle]。圆很小，拉长的比例给得更大才看得出来；
 * 不歪：圆转了看不出来，只会把图标转歪。
 */
private val IconButtonDragStyle = GlassDragStyle(range = 60.dp, stretch = 0.15f, squeeze = 0.5f, tilt = 0f)

/** 光雾的最大亮度，和按钮一样。 */
private const val GlowStrength = 0.25f

private val IconButtonShadow = Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
