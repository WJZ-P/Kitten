package me.wjz.kitten.ui.component

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 圆形的玻璃图标按钮，比如模态窗右上角的关闭。
 *
 * 和 [GlassButton] 的透明款是同一种玻璃、同一套按压反馈（鼓起 + 跟着手指的光雾 + 按住拖动
 * 时跟手拉长），只是
 * 做成圆的、只放一个图标。看得见的圆是 [IconButtonSize]，四周留出触摸区，整体
 * [TouchSize] 见方，和 Material 的 IconButton 一样大，换进去不用改布局。
 *
 * @param contentDescription 给读屏用。图标按钮没有文字，这里一定要写清楚它是干什么的。
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }
    val colors = KittenTheme.colors

    Box(
        modifier = modifier
            .size(TouchSize)
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
                .size(IconButtonSize)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { CircleShape },
                    effects = { glassEffects(GlassRadius.Small) },
                    highlight = { GlassRim },
                    shadow = { IconButtonShadow },
                    layerBlock = {
                        // 松手时 pressProgress 会略冲过 0，这里正好弹一下。
                        val s = lerp(1f, PressedScale, press.pressProgress)
                        scaleX = s
                        scaleY = s
                        // 按住拖动时跟着手指拉长，松手弹回。按下的位置是外面触摸区的坐标，换算到圆里。
                        val inset = (TouchSize - IconButtonSize).toPx() / 2f
                        val grab = press.downPosition
                        dragStretch(
                            drag = press.dragOffset,
                            grab = if (grab.isSpecified) grab - Offset(inset, inset) else grab,
                            style = IconButtonDragStyle,
                        )
                    },
                    onDrawSurface = {
                        drawRect(colors.controlTint)
                        // 手指位置是外面触摸区的坐标，换算到圆里。
                        val inset = (TouchSize - IconButtonSize).toPx() / 2f
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
                modifier = Modifier.size(IconSize),
            )
        }
    }
}

/** 触摸区，和 Material 的 IconButton 一样。 */
private val TouchSize = 48.dp

/** 看得见的那个圆。 */
private val IconButtonSize = 36.dp

private val IconSize = 24.dp

/** 按下鼓起多少。圆很小，给得比长条按钮多一点才看得出来。 */
private const val PressedScale = 1.25f

/**
 * 按住拖动时的形变，各项含义见 [GlassDragStyle]。圆很小，拉长的比例给得更大才看得出来；
 * 不歪：圆转了看不出来，只会把图标转歪。
 */
private val IconButtonDragStyle = GlassDragStyle(range = 60.dp, stretch = 0.15f, squeeze = 0.5f, tilt = 0f)

/** 光雾的最大亮度，和按钮一样。 */
private const val GlowStrength = 0.25f

private val IconButtonShadow = Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.12f))
