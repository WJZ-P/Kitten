package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 最基础的玻璃表面：只负责把玻璃效果画出来，尺寸和内容都由调用方决定。
 *
 * [GlassSection] 是它之上的约定版本（占满宽度、带表头），设置页那种整齐的分组用它。
 * 消息气泡要随内容伸缩、输入栏要贴着底边，那些约定反而碍事，就直接用这个。
 *
 * 效果本身见 [glassEffects]。
 *
 * ## 光雾
 *
 * [mist] 打开时，玻璃里铺一层缓缓流动的光雾（见 [GlassMist]）。雾画在玻璃上面、内容下面。
 *
 * ## 按压
 *
 * [interactive] 打开时，按下玻璃本身（不是按在里面的按钮、开关、输入框上）会有反馈：
 *
 * - 整块微微放大到 [PressedScale]，松手时带一点回弹；
 * - 折射变强（[PressedRefractionBoost]），底色变深（[PressedTintExtra]）；
 * - 光雾变浓（[PressedMistBoost]），并从手指处被冲开（见 [GlassMist] 的"冲开"）。
 *
 * 它只是反馈，不触发任何动作。里面的控件按下时会消费按下事件（见 [detectGlassPress]），
 * 这里只接没被消费的，所以按在按钮上时玻璃不会跟着一起动。按住后开始滚动页面，反馈收回。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius.Medium,
    blurRadius: Dp = GlassBlur,
    tint: Color = KittenTheme.colors.glassTint,
    interactive: Boolean = false,
    mist: Boolean = false,
    content: @Composable () -> Unit,
) {
    val press = rememberGlassPressState()
    val mistPush = rememberGlassMistPush()
    val shape = remember(cornerRadius) { RoundedCornerShape(cornerRadius) }
    Box(
        modifier = modifier
            .then(
                if (interactive) {
                    Modifier.pointerInput(press, mistPush) {
                        detectGlassPress(press, onDown = mistPush::push, onClick = {})
                    }
                } else {
                    Modifier
                },
            )
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    glassEffects(cornerRadius, blurRadius, press = press.pressProgress.coerceIn(0f, 1f))
                },
                highlight = { GlassRim },
                layerBlock = if (interactive) {
                    {
                        // 松手时 pressProgress 会略冲过 0，这里正好轻轻弹一下。
                        val scale = lerp(1f, PressedScale, press.pressProgress)
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    null
                },
                onDrawSurface = {
                    drawRect(tint)
                    if (interactive) {
                        // 按下时再叠一层同样的底色：玻璃整体变深，色相不变。
                        drawRect(tint, alpha = press.pressProgress.coerceIn(0f, 1f) * PressedTintExtra)
                    }
                },
            ),
    ) {
        if (mist) {
            GlassMist(
                shape = shape,
                modifier = Modifier.matchParentSize(),
                strength = { 1f + PressedMistBoost * press.pressProgress.coerceIn(0f, 1f) },
                push = if (interactive) mistPush else null,
                hold = { press.pressProgress.coerceIn(0f, 1f) },
            )
        }
        content()
    }
}

/** 按下时放大到多少倍。卡片面积大，给一点点就看得出来，多了会挤到相邻的卡片。 */
private const val PressedScale = 1.02f

/** 按下时折射量额外加多少（相对平时的比例）。 */
private const val PressedRefractionBoost = 0.6f

/** 按下时再叠一层底色，这层的不透明度。1 就是底色整整加深一倍。 */
private const val PressedTintExtra = 0.8f

/** 按下时光雾的浓度额外加多少（相对平时的比例）。 */
private const val PressedMistBoost = 0.6f

/**
 * Kitten 的玻璃：提饱和 → 糊开 → 透镜折射。卡片、输入框、按钮、下拉菜单共用这一套，
 * 同一页上的玻璃才是同一种材质。
 *
 * ## 参数为什么这么给
 *
 * - 顺序照库作者的 catalog：先 [vibrancy] 提饱和，再 [blur] 糊开，最后 [lens] 折射。
 *   lens 放最后，边缘被掰弯的是已经糊过的背景，过渡才柔和。
 * - **模糊给得很轻。** 背景里的光晕和雾本来就是软的，折射把它们掰弯了也看不出来；
 *   真正能让人看出"边缘在折射"的是背景里那层锐利的点阵（见 [GlassBackground]）。
 *   模糊一重，点阵先没了，玻璃就退化成一块磨砂板。
 * - [lens] 是液体玻璃区别于普通毛玻璃的关键：边缘一圈像透镜一样把背后的内容往里拉扯、
 *   放大，再带一点色散的彩边。折射带最宽到圆角大小——再宽，圆角里面的等高线会出尖角，
 *   边缘出现断裂；也不超过 [MaxRefractionHeight]，否则大圆角的输入栏整块都在折射带里，
 *   看出去全是扭曲。折射量取带宽的两倍，和 tab 栏滑块按下时的强度相当。
 * - **lens 的两个参数都必须是正数。** 库里 `refractionAmount <= 0` 会直接 return，
 *   什么都不做（方向由着色器内部取反）。早先这里传的是 `-cornerRadius`，于是折射
 *   从来没生效过，也不报错。
 * - 这些参数都是 **Float 像素不是 Dp**，必须自己乘 density——直接传数字的话，不同
 *   dpi 的设备上效果强弱会差很多。effects 的接收者本身就是 Density，直接 `toPx()`。
 *
 * @param press 按压进度 0..1，按下时折射变强，见 [GlassSurface] 的按压说明。
 */
internal fun BackdropEffectScope.glassEffects(
    cornerRadius: Dp,
    blurRadius: Dp = GlassBlur,
    press: Float = 0f,
) {
    vibrancy()
    blur(blurRadius.toPx())
    val refractionHeight = min(cornerRadius, MaxRefractionHeight).toPx()
    lens(
        refractionHeight = refractionHeight,
        // 按下时只加折射量、不加折射带宽：带宽超过圆角，圆角里的等高线会出尖角。
        refractionAmount = refractionHeight * 2f * (1f + PressedRefractionBoost * press),
        depthEffect = true,
        chromaticAberration = true,
    )
}

/** 玻璃默认的模糊半径。 */
internal val GlassBlur = 2.dp

private val MaxRefractionHeight = 16.dp
