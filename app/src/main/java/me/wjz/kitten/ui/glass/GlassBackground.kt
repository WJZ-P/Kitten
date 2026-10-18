package me.wjz.kitten.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop

/**
 * 玻璃卡片背后的背景。
 *
 * ## 背景必须是内容的兄弟，不能是父级
 *
 * 这是用这个库最容易踩的坑，踩过一次直接整个进程崩掉：
 *
 * ```
 * RenderNode::prepareTreeImpl → SkiaDisplayList::prepareListAndChildren → 递归 500+ 层
 * FORTIFY: vsnprintf: size 18446744073709551453 > SSIZE_MAX
 * ```
 *
 * 起因是把 `layerBackdrop()` 加在了包着卡片的那个 Box 上。于是：背景层要录制自己的
 * 全部内容 → 内容里含玻璃卡片 → 卡片又要读取背景层 → RenderNode 树成环 →
 * `prepareTree` 无限递归到栈溢出。
 *
 * 所以这里把两层拆开：被录制的那层**只画背景**，内容层是它的兄弟盖在上面。
 * 推论是——**玻璃卡片永远不会折射到另一块玻璃卡片**，它们都在内容层里，不在录制范围内。
 *
 * ## 为什么要专门画一层背景
 *
 * 液体玻璃的效果来自折射背后的内容：`lens()` 在边缘做透镜畸变，`blur()` 把背后糊开。
 * 如果背后是一整块纯色，折射和模糊之后还是同一块纯色，用户什么都看不到，只会觉得
 * "加了个库但没变化"。所以这里铺几团颜色明确、边界柔和的光晕，它们在卡片边缘被
 * 拉扯变形时，玻璃感才出得来。
 */
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // 只有这一层会被录进 backdrop。它不能包含任何读取 backdrop 的东西，否则成环。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawAurora() }
                .layerBackdrop(backdrop),
        )
        // 内容是上面那层的兄弟，盖在它上面，不参与录制。
        content()
    }
}

/**
 * 画几团弥散光晕。
 *
 * 用 `drawBehind` 而不是堆 Composable：这层是纯装饰，不需要参与布局和测量，
 * 直接画进画布最省。
 */
private fun DrawScope.drawAurora() {
    // 底色。比主题的 background 稍深，这样上面的玻璃卡片才有"浮起来"的对比。
    drawRect(Brush.verticalGradient(listOf(BaseTop, BaseBottom)))

    // 三团光晕，位置故意错开并超出边界——让它们被屏幕裁掉一部分，比三个完整的圆
    // 自然得多。半径给得大，边缘才够柔和，否则折射时会露出生硬的圆形轮廓。
    glow(GlowBlue, center = Offset(size.width * 0.15f, size.height * 0.12f), radius = 320.dp)
    glow(GlowCyan, center = Offset(size.width * 0.95f, size.height * 0.34f), radius = 280.dp)
    glow(GlowViolet, center = Offset(size.width * 0.3f, size.height * 0.82f), radius = 360.dp)
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Dp) {
    val r = radius.toPx()
    drawCircle(
        // 中心到边缘完全透明，拼接处才不会有可见的圆边。
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

private val BaseTop = Color(0xFF0B1620)
private val BaseBottom = Color(0xFF060C12)
private val GlowBlue = Color(0x5566CCFF)
private val GlowCyan = Color(0x3340E0D0)
private val GlowViolet = Color(0x447A5CFF)

/** 卡片圆角。玻璃的折射在圆角处最明显，给得大一些效果更足。 */
val GlassCornerRadius = 28.dp

/** 卡片内部留白。玻璃边缘有折射和高光，内容贴太近会糊在一起。 */
val GlassContentPadding = 20.dp
