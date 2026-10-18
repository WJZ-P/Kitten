package me.wjz.kitten.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * 最基础的玻璃表面：只负责把玻璃效果画出来，尺寸和内容都由调用方决定。
 *
 * [GlassCard] 是它之上的约定版本（占满宽度、固定内边距），适合权限卡片那种整齐的列表。
 * 但消息气泡要随内容伸缩、输入栏要贴着底边，那些约定反而碍事，所以把效果本身
 * 单独抽出来。
 *
 * ## 效果参数为什么这么给
 *
 * - [lens] 是液体玻璃区别于普通毛玻璃的关键：它做**折射**，背后的内容在边缘被透镜
 *   拉扯变形，而不只是糊成一片。
 * - 折射层厚度取圆角的一半：厚度超过圆角时边缘会出现明显的断裂感。
 * - 这些参数都是 **Float 像素不是 Dp**，必须自己乘 density——直接传数字的话，
 *   不同 dpi 的设备上效果强弱会差很多。
 */
@Composable
fun GlassSurface(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GlassCornerRadius,
    blurRadius: Dp = 6.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    val blurPx = with(density) { blurRadius.toPx() }

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { RoundedCornerShape(cornerRadius) },
            effects = {
                lens(
                    refractionHeight = cornerRadiusPx * 0.5f,
                    refractionAmount = -cornerRadiusPx,
                    depthEffect = true,
                )
                blur(radius = blurPx)
                // 真实玻璃会让透过的颜色更浓，少了这步整块会发灰。
                vibrancy()
            },
        ),
    ) {
        content()
    }
}
