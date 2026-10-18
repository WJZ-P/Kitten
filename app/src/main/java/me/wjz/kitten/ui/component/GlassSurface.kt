package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
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
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius.Medium,
    blurRadius: Dp = GlassBlur,
    tint: Color = KittenTheme.colors.glassTint,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = { glassEffects(cornerRadius, blurRadius) },
            highlight = { GlassRim },
            onDrawSurface = { drawRect(tint) },
        ),
    ) {
        content()
    }
}

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
 */
internal fun BackdropEffectScope.glassEffects(cornerRadius: Dp, blurRadius: Dp = GlassBlur) {
    vibrancy()
    blur(blurRadius.toPx())
    val refractionHeight = min(cornerRadius, MaxRefractionHeight).toPx()
    lens(
        refractionHeight = refractionHeight,
        refractionAmount = refractionHeight * 2f,
        depthEffect = true,
        chromaticAberration = true,
    )
}

/** 玻璃默认的模糊半径。 */
internal val GlassBlur = 2.dp

private val MaxRefractionHeight = 16.dp
