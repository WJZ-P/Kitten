package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme

enum class GlassButtonStyle {
    /** 染了强调色的玻璃：页面上最主要的那个动作。一屏最好只有一个，多了就分不出主次。 */
    Primary,

    /** 透明玻璃：次要动作。 */
    Secondary,
}

/**
 * 玻璃按钮。
 *
 * 主按钮是一块染了色的玻璃，不是实心色块：强调色只给到八成多不透明，再在上半截叠一道
 * 很淡的亮光，背景还能隐约透过来。
 *
 * 按下时里面亮起一团跟着手指流动的光雾（[GlassGlow]），按钮同时轻轻鼓起；按住拖动时按钮
 * 跟着手指被拉长、歪一点（[dragStretch]）。手指滑出按钮一段距离，光雾熄灭、形变弹回，只有
 * 在按钮范围内抬手才触发 [onClick]。见 [GlassPressState]。
 *
 * @param compact 紧凑尺寸，用在列表项右侧这种空间有限的地方。
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    style: GlassButtonStyle = GlassButtonStyle.Primary,
    enabled: Boolean = true,
    compact: Boolean = false,
    icon: ImageVector? = null,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }
    val isPrimary = style == GlassButtonStyle.Primary
    val colors = KittenTheme.colors
    val contentColor = if (isPrimary) colors.onAccent else colors.textPrimary

    Box(
        modifier = modifier
            .semantics {
                role = Role.Button
                if (enabled) {
                    // 显式写 this.：外层参数也叫 onClick，免得读代码的人分不清调的是哪个。
                    this.onClick(label = null) {
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
            .drawBackdrop(
                backdrop = backdrop,
                // 按钮都嵌在卡片里，统一用小一档的圆角，和输入框对齐。
                shape = { SmallShape },
                effects = { glassEffects(GlassRadius.Small) },
                highlight = { GlassRim },
                shadow = { ButtonShadow },
                layerBlock = {
                    // 按下鼓起一点。给得很克制：按钮在列表里，鼓太多会挤到旁边的元素。
                    // 松手时 pressProgress 会略冲过 0，这里正好弹一下。
                    val s = lerp(1f, PressedScale, press.pressProgress)
                    scaleX = s
                    scaleY = s
                    // 按住拖动时跟着手指拉长、歪一点，松手弹回。
                    dragStretch(press.dragOffset, press.downPosition, ButtonDragStyle)
                },
                onDrawSurface = {
                    if (isPrimary) {
                        drawRect(colors.accent.copy(alpha = PrimaryFillAlpha))
                        // 上半截一道渐隐的亮光：没有它，染色玻璃看起来就是一块平涂的色块。
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                                endY = size.height * 0.55f,
                            ),
                        )
                    } else {
                        drawRect(colors.controlTint)
                    }
                    drawGlassGlow(
                        glow = glow,
                        intensity = press.pressProgress.coerceIn(0f, 1f) * GlowStrength,
                        center = press.position,
                        // 主按钮是天依蓝的填色，上面要用白雾；透明按钮在白玻璃上，用蓝雾。
                        colors = if (isPrimary) colors.glowOnAccent else colors.glowOnGlass,
                    )
                },
            )
            .height(if (compact) CompactHeight else RegularHeight)
            .padding(horizontal = if (compact) 12.dp else 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(if (compact) 16.dp else 18.dp),
                )
            }
            Text(
                text = text,
                style = if (compact) {
                    MaterialTheme.typography.labelLarge
                } else {
                    MaterialTheme.typography.titleSmall
                },
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val RegularHeight = 48.dp
private val CompactHeight = 32.dp

private const val PressedScale = 1.04f

/**
 * 按住拖动时的形变，各项含义见 [GlassDragStyle]。按钮比窗小得多，手指拖一小段就该看得出
 * 在拉，所以 range 给得短、拉长的比例给得大。
 */
private val ButtonDragStyle = GlassDragStyle(range = 80.dp, stretch = 0.08f, squeeze = 0.5f, tilt = 3f)
private const val PrimaryFillAlpha = 0.85f
private const val DisabledAlpha = 0.4f

/** 光雾的最大亮度。按钮面积大，给满了会白成一片。 */
private const val GlowStrength = 0.25f

/** 按钮在分组卡片里，阴影要收着：卡片自己已经有一层了，叠太重会脏。 */
private val ButtonShadow = Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.12f))
