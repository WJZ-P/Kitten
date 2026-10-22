package me.wjz.kitten.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 带文字的玻璃开关按钮，比如输入框尾部的"显示 / 隐藏"。
 *
 * 照着 [GlassSwitch] 拨钮的样子做：
 *
 * ```
 * 平时   一块磨砂玻璃；选中时染上强调色
 * 按下   鼓起来，磨砂和底色退场、换成透镜：变成一块清透的、看得见背后被掰弯的玻璃
 * 松手   状态切换，再磨砂回去
 * ```
 *
 * 底色是在"清透"那一段换的：按下时旧的底色淡出，松手后新的底色随着磨砂一起回来，看起来是
 * 玻璃本身变了颜色，而不是两块色块直接切换。
 *
 * 快速点一下时按压态一闪就过，这一套来不及播完。所以变形单独用一条动画（[rememberPressMorph]）：
 * 按下就鼓起，松手时先把鼓起放完、在最鼓处停够 [MinMorphMillis]，再收回去。
 *
 * 按住拖动时跟着手指拉长（[dragStretch]），和按钮一样。
 *
 * @param text 按钮上的字。一般随 [checked] 变，比如"显示"和"隐藏"。
 * @param backdrop 它该折射的东西。放在输入框里时用输入框给的那份，见 [GlassTextField] 的 trailing。
 * @param pressedScale 按下时鼓到多大。默认 [DefaultPressedScale]，按钮宽的时候（比如铺满半行）
 *   同样的比例会鼓得很大，传小一点。
 */
@Composable
fun GlassToggleButton(
    text: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    pressedScale: Float = DefaultPressedScale,
) {
    val currentChecked by rememberUpdatedState(checked)
    val currentOnCheckedChange by rememberUpdatedState(onCheckedChange)
    val press = rememberGlassPressState()
    val colors = KittenTheme.colors
    val tint by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = TintSpec,
        label = "toggleTint",
    )

    val morph = rememberPressMorph(press, MinMorphMillis, MorphInSpec, MorphOutSpec)

    Box(
        modifier = modifier
            .semantics {
                role = Role.Switch
                toggleableState = ToggleableState(currentChecked)
                onClick {
                    currentOnCheckedChange(!currentChecked)
                    true
                }
            }
            .pointerInput(press) {
                detectGlassPress(press, followDrag = true) { currentOnCheckedChange(!currentChecked) }
            }
            .drawBackdrop(
                backdrop = backdrop,
                // 和按钮、输入框同一档的小圆角：它是槽里的一个按钮，不是能拖的拨钮，不用胶囊。
                shape = { SmallShape },
                effects = {
                    val p = morph.value.coerceIn(0f, 1f)
                    vibrancy()
                    // 模糊和折射此消彼长：平时是磨砂，按下换成透镜，看得清背后被掰弯。
                    blur(FrostBlur.toPx() * (1f - p))
                    lens(
                        refractionHeight = lerp(RestLensHeight, PressedLensHeight, p).toPx(),
                        refractionAmount = lerp(RestLensAmount, PressedLensAmount, p).toPx(),
                        depthEffect = true,
                        chromaticAberration = true,
                    )
                },
                highlight = { GlassRim },
                shadow = { ToggleShadow },
                innerShadow = {
                    val p = morph.value.coerceIn(0f, 1f)
                    InnerShadow(radius = 4.dp * p, alpha = p)
                },
                layerBlock = {
                    // 收回时 morph 会略冲过 0，这里正好轻轻弹一下。
                    val s = lerp(1f, pressedScale, morph.value)
                    scaleX = s
                    scaleY = s
                    dragStretch(press.dragOffset, press.downPosition, ToggleDragStyle)
                },
                onDrawSurface = {
                    // 磨砂的那层底色：按下时退场，把舞台交给折射。
                    val frost = 1f - morph.value.coerceIn(0f, 1f)
                    drawRect(colors.controlTint, alpha = frost)
                    drawRect(colors.accent, alpha = tint * AccentAlpha * frost)
                },
            )
            .height(Height)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = lerp(colors.textPrimary, colors.onAccent, tint),
            maxLines = 1,
        )
    }
}

private val Height = 32.dp

/** 按下默认鼓起多少。放在输入框里，鼓到 1.2 刚好不顶出槽。 */
const val DefaultPressedScale = 1.2f

/** 平时磨砂的模糊半径。 */
private val FrostBlur = 4.dp

/**
 * 透镜折射带的宽度：平时一点点，按下时加宽。不能超过圆角（[GlassRadius.Small]，8dp），
 * 再宽圆角里的等高线会出尖角，见 [glassEffects]。
 */
private val RestLensHeight = 6.dp
private val PressedLensHeight = 8.dp

/** 透镜的折射量：平时一点点，按下时拉满。 */
private val RestLensAmount = 12.dp
private val PressedLensAmount = 28.dp

/** 选中时强调色的浓度。 */
private const val AccentAlpha = 0.85f

/** 快速点按时，在最鼓的状态至少停多久（毫秒）再收回。 */
private const val MinMorphMillis = 100L

/** 鼓起：快，带一点过冲。 */
private val MorphInSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 收回：软一点，晃一下，像液体落回去。 */
private val MorphOutSpec = spring<Float>(dampingRatio = 0.6f, stiffness = 300f)

/** 选中底色的切换：要快，在磨砂回来之前基本换完。 */
private val TintSpec = spring<Float>(dampingRatio = 1f, stiffness = 600f)

/** 按住拖动时的形变，各项含义见 [GlassDragStyle]。 */
private val ToggleDragStyle = GlassDragStyle(range = 60.dp, stretch = 0.12f, squeeze = 0.5f, tilt = 3f)

private val ToggleShadow = Shadow(radius = 4.dp, color = Color.Black.copy(alpha = 0.08f))
