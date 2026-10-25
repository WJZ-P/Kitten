package me.wjz.kitten.ui.component

import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenColors
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
 * 主按钮是一块染了色的玻璃，不是实心色块：强调色上浅下深、只给到半透明，背后的东西透得
 * 过来、在边缘被折射得比别的玻璃更狠（[PrimaryRefraction]），上半截再叠一道很淡的亮光；
 * 里面流着一层光雾（和卡片一样），按下时从手指处冲开。透明款就是一块淡白玻璃，不带雾。
 *
 * 按下时按钮鼓起，磨砂和底色退场、折射变强，变成一块清透的透镜，松手再磨砂回去（和
 * [GlassToggleButton] 一样，快速点一下也能播完一轮，见 [rememberPressMorph]）；里面同时亮起
 * 一团跟着手指流动的光雾（[GlassGlow]）。按住拖动时按钮跟着手指被拉长、歪一点（[dragStretch]）。
 * 手指滑出按钮一段距离，光雾熄灭、形变弹回，只有在按钮范围内抬手才触发 [onClick]。见
 * [GlassPressState]。
 *
 * @param compact 紧凑尺寸，用在列表项右侧这种空间有限的地方。
 * @param textStyle 文字的样式（字号、字重等）。不传时普通按钮用 titleSmall，紧凑按钮用 labelLarge。
 *   颜色不看这里，由按钮的款式决定。
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
    textStyle: TextStyle? = null,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val morph = rememberPressMorph(press, MinMorphMillis, MorphInSpec, MorphOutSpec)
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }
    val mist = rememberGlassMistState()
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
            .pointerInput(press, mist, enabled, isPrimary) {
                if (enabled) {
                    detectGlassPress(
                        state = press,
                        onDown = { if (isPrimary) mist.push(it) },
                        followDrag = true,
                    ) { currentOnClick() }
                }
            }
            .alpha(if (enabled) 1f else DisabledAlpha)
            .drawBackdrop(
                backdrop = backdrop,
                // 按钮都嵌在卡片里，统一用小一档的圆角，和输入框对齐。
                shape = { SmallShape },
                effects = {
                    // 按下时磨砂退场、折射变强，变成一块透镜；松手再磨砂回去。和切换按钮一样。
                    val p = morph.value.coerceIn(0f, 1f)
                    glassEffects(
                        cornerRadius = GlassRadius.Small,
                        blurRadius = GlassBlur * (1f - p),
                        press = p,
                        refraction = if (isPrimary) PrimaryRefraction else DefaultRefraction,
                    )
                },
                highlight = { GlassRim },
                shadow = { ButtonShadow },
                innerShadow = {
                    val p = morph.value.coerceIn(0f, 1f)
                    InnerShadow(radius = 4.dp * p, alpha = p)
                },
                layerBlock = {
                    // 按下鼓起。普通按钮多半铺满一行，鼓得多会顶出卡片，给得克制；紧凑按钮小，
                    // 给多一点才看得出来。收回时 morph 会略冲过 0，这里正好弹一下。
                    val s = lerp(1f, if (compact) CompactPressedScale else PressedScale, morph.value)
                    scaleX = s
                    scaleY = s
                    // 按住拖动时跟着手指拉长、歪一点，松手弹回。
                    dragStretch(press.dragOffset, press.downPosition, ButtonDragStyle)
                },
                onDrawSurface = {
                    // 磨砂的那层底色：按下时退场，把舞台交给折射。
                    val frost = 1f - morph.value.coerceIn(0f, 1f)
                    if (isPrimary) {
                        drawPrimaryTint(colors, alpha = frost)
                    } else {
                        drawRect(colors.controlTint, alpha = frost)
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
            // 雾必须写在 drawBackdrop 后面，见 glassMist。
            .then(
                if (isPrimary) {
                    Modifier.glassMist(
                        shape = SmallShape,
                        state = mist,
                        strength = { 1f + PressedMistBoost * press.pressProgress.coerceIn(0f, 1f) },
                        hold = { press.pressProgress.coerceIn(0f, 1f) },
                        style = PrimaryMistStyle,
                    )
                } else {
                    Modifier
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
                style = textStyle ?: if (compact) {
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

/**
 * 主按钮那层染色：强调色上浅下深，上半截一道渐隐的亮光。[GlassButton] 和 [GlassIconButton] 的主
 * 款式共用，同一页上的主按钮才是同一种染色玻璃。
 *
 * @param alpha 整层的不透明度。按下变成透镜时它跟着磨砂一起退场。
 */
internal fun DrawScope.drawPrimaryTint(colors: KittenColors, alpha: Float) {
    // 上浅下深：一块有厚度的染色玻璃，而不是平涂。
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                colors.accent.copy(alpha = PrimaryTopAlpha),
                colors.accentDeep.copy(alpha = PrimaryBottomAlpha),
            ),
        ),
        alpha = alpha,
    )
    // 上半截一道渐隐的亮光：没有它，染色玻璃看起来就是一块平涂的色块。
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
            endY = size.height * 0.55f,
        ),
        alpha = alpha,
    )
}

private val RegularHeight = 48.dp
private val CompactHeight = 32.dp

/** 按下鼓起多少：普通按钮、紧凑按钮。 */
private const val PressedScale = 1.04f
private const val CompactPressedScale = 1.15f

/** 快速点按时，在最鼓的状态至少停多久（毫秒）再收回，见 [rememberPressMorph]。 */
private const val MinMorphMillis = 100L

/** 鼓起：快，带一点过冲。 */
private val MorphInSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 收回：软一点，晃一下，像液体落回去。 */
private val MorphOutSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)

/**
 * 按住拖动时的形变，各项含义见 [GlassDragStyle]。按钮比窗小得多，手指拖一小段就该看得出
 * 在拉，所以 range 给得短、拉长的比例给得大。
 */
private val ButtonDragStyle = GlassDragStyle(range = 80.dp, stretch = 0.08f, squeeze = 0.5f, tilt = 3f)

/** 主按钮染色的浓度：上沿（强调色）和下沿（深一档的强调色）。给低了字看不清，给高了又成了色块。 */
private const val PrimaryTopAlpha = 0.55f
private const val PrimaryBottomAlpha = 0.75f

/** 主按钮的折射量（折射带宽的倍数，透明款是 [DefaultRefraction]）。 */
private const val PrimaryRefraction = 4f

/** 主按钮里光雾的样子。按钮只有 48dp 高，雾团给小一些，和底部栏一样。 */
private val PrimaryMistStyle = GlassMistStyle(grain = 40.dp, hazeAlpha = 0.35f, lightAlpha = 0.5f)

/** 按下时主按钮里的雾额外浓多少（相对平时的比例），和卡片一样。 */
private const val PressedMistBoost = 0.6f

private const val DisabledAlpha = 0.4f

/** 光雾的最大亮度。按钮面积大，给满了会白成一片。 */
private const val GlowStrength = 0.25f

/** 按钮在分组卡片里，阴影要收着：卡片自己已经有一层了，叠太重会脏。 */
private val ButtonShadow = Shadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.12f))
