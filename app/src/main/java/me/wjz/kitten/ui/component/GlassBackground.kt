package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 玻璃背后的背景。
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
 * ## layerBackdrop 必须写在绘制 modifier 的左边
 *
 * 这是第二个坑，而且不报错、不崩溃，只是效果悄悄没了：
 *
 * `layerBackdrop` 录的是它的 `drawContent()`，也就是**它右边**的 modifier 和子节点画的
 * 东西。早先写成了 `.drawBehind { 光晕 }.layerBackdrop(backdrop)`——光晕在它左边，
 * 画在了录制范围外面，录下来的是一张**透明的空图**。后果：
 *
 * - 所有玻璃都在折射"什么都没有"，模糊、折射、vibrancy 全部白做；肉眼看到的光晕
 *   其实是直接画在屏幕上的，透过透明的玻璃看过去而已；
 * - tab 栏的滑块本应不透明地盖住底下那排白色图标，结果是透明的。按下时高亮层放大
 *   1.2 倍，和底下没放大的白图标错开，白色就漏出来了。
 *
 * ## 为什么要专门画一层背景
 *
 * 液体玻璃的效果来自折射背后的内容：`lens()` 在边缘做透镜畸变，`blur()` 把背后糊开。
 * 背后是一整块纯色的话，折射和模糊之后还是同一块纯色，用户什么都看不到。所以这里铺
 * 几团颜色明确、边界柔和的光晕。
 *
 * 光有光晕还不够：光晕本身就是糊的，透镜把它掰弯了也看不出来。所以再叠一层很淡的
 * **点阵**——点是锐利的，玻璃边缘把它们拉弯、放大时一眼就能看出"这里有块玻璃"。
 * 这也是 Kitten 区别于 iOS 的地方之一：iOS 靠壁纸撑效果，Kitten 的背景自己带刻度。
 */
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = KittenTheme.colors
    Box(modifier = modifier.fillMaxSize()) {
        // 只有这一层会被录进 backdrop。它不能包含任何读取 backdrop 的东西，否则成环。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 顺序不能换，见上面"layerBackdrop 必须写在绘制 modifier 的左边"。
                .layerBackdrop(backdrop)
                .drawWithCache {
                    // 点阵坐标只跟尺寸有关，缓存住，别每次重画都重新算几百个点。
                    val dots = dotGrid(size, DotSpacing.toPx())
                    val dotSize = DotSize.toPx()
                    onDrawBehind {
                        drawAurora(colors)
                        drawPoints(
                            points = dots,
                            pointMode = PointMode.Points,
                            color = colors.backgroundDots,
                            strokeWidth = dotSize,
                            cap = StrokeCap.Round,
                        )
                    }
                },
        )
        // 内容是上面那层的兄弟，盖在它上面，不参与录制。
        content()
    }
}

/**
 * 画几团弥散光晕。
 *
 * 用绘制而不是堆 Composable：这层是纯装饰，不需要参与布局和测量，直接画进画布最省。
 */
private fun DrawScope.drawAurora(colors: KittenColors) {
    // 底色：极浅的蓝白。上面的玻璃比它更白一点，才有"浮起来"的对比。
    drawRect(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))

    // 三团光晕，位置故意错开并超出边界——让它们被屏幕裁掉一部分，比三个完整的圆
    // 自然得多。半径给得大，边缘才够柔和，否则折射时会露出生硬的圆形轮廓。
    glow(colors.backgroundGlowPrimary, center = Offset(size.width * 0.15f, size.height * 0.12f), radius = 320.dp)
    glow(colors.backgroundGlowSecondary, center = Offset(size.width * 0.95f, size.height * 0.34f), radius = 280.dp)
    glow(colors.backgroundGlowTertiary, center = Offset(size.width * 0.3f, size.height * 0.82f), radius = 360.dp)
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

/** 铺满 [size] 的等距点阵，整体居中，左右留白对称。 */
private fun dotGrid(size: Size, spacing: Float): List<Offset> {
    if (spacing <= 0f || size.width <= 0f || size.height <= 0f) return emptyList()
    val cols = (size.width / spacing).toInt() + 1
    val rows = (size.height / spacing).toInt() + 1
    val originX = (size.width - (cols - 1) * spacing) / 2f
    val originY = (size.height - (rows - 1) * spacing) / 2f
    return buildList(cols * rows) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                add(Offset(originX + col * spacing, originY + row * spacing))
            }
        }
    }
}

private val DotSpacing = 22.dp
private val DotSize = 1.6.dp
