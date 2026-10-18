package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 最基础的玻璃表面：只负责把玻璃效果画出来，尺寸和内容都由调用方决定。
 *
 * [GlassSection] 是它之上的约定版本（占满宽度、带标题），设置页那种整齐的分组用它。
 * 消息气泡要随内容伸缩、输入栏要贴着底边，那些约定反而碍事，就直接用这个。
 *
 * ## 效果参数为什么这么给
 *
 * - 顺序照库作者的 catalog：先 [vibrancy] 提饱和，再 [blur] 糊开，最后 [lens] 折射。
 *   lens 放最后，边缘被掰弯的是已经糊过的背景，过渡才柔和。
 * - [lens] 是液体玻璃区别于普通毛玻璃的关键：它做**折射**，背后的内容在边缘被透镜
 *   拉扯变形，而不只是糊成一片。折射带宽度取圆角的 3/4：超过圆角时边缘会出现明显的
 *   断裂感。
 * - **lens 的两个参数都必须是正数。** 库里 `refractionAmount <= 0` 会直接 return，
 *   什么都不做（方向由着色器内部取反）。早先这里传的是 `-cornerRadius`，于是折射
 *   从来没生效过，也不报错。
 * - 这些参数都是 **Float 像素不是 Dp**，必须自己乘 density——直接传数字的话，不同
 *   dpi 的设备上效果强弱会差很多。effects 的接收者本身就是 Density，直接 `toPx()`。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassRadius.Large,
    blurRadius: Dp = 8.dp,
    tint: Color = KittenTheme.colors.glassTint,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                vibrancy()
                blur(blurRadius.toPx())
                val radiusPx = cornerRadius.toPx()
                lens(
                    refractionHeight = radiusPx * 0.75f,
                    refractionAmount = radiusPx,
                    depthEffect = true,
                )
            },
            highlight = { GlassRim },
            onDrawSurface = { drawRect(tint) },
        ),
    ) {
        content()
    }
}
