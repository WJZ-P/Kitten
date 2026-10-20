package me.wjz.kitten.ui.component

import android.annotation.SuppressLint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil

/**
 * 把 [backdrop] 糊开画在组件里，画在组件内容的下面。用在模态窗的遮罩这种整屏的大模糊上。
 *
 * ## 为什么降采样
 *
 * 模糊的贵处在于像素多：遮罩是整屏的，底下的雾每推进一帧，整个页面都要重新合成一遍、再整屏
 * 模糊一遍。这里先按 1/[downscale] 的宽高把背后录进一张小纹理，在小纹理上模糊，再拉伸回原
 * 尺寸（做法同光雾，见 [glassMist]）。像素只剩 1/[downscale]²，模糊半径也跟着缩小。
 *
 * 糊开的东西本来就没有细节，拉伸看不出颗粒。代价是效果比同样半径的整屏模糊略软一点：小纹理
 * 拉伸时本身也会糊一点；小字按 1/4 大小画进小纹理，糊开后各个字的深浅会有点不匀，放大截图
 * 才看得出来。
 *
 * 实测（设置页，模态窗开着静置，和关着比）：整屏模糊时每帧 GPU 多 6ms 左右，降采样后多 0.5ms
 * 左右。RenderThread 没变（连窗体一起多 2.5ms 左右）：遮罩那份花在把页面的绘制指令再发一遍上，
 * 和像素多少无关。
 *
 * ## 为什么模糊和不透明度一起涨
 *
 * 降采样的副本在模糊半径还很小的时候也不是清晰的（它是缩小再放大的）。打开时要是直接盖上
 * 去、再慢慢加大模糊，第一帧界面就会一下子变糊。所以跟着 [progress]，模糊半径和这一层的
 * 不透明度一起从 0 涨上去：一开始几乎透明，看到的还是底下清晰的界面。
 *
 * @param radius 模糊半径（[progress] 为 1 时），按原尺寸算。
 * @param progress 0..1，模糊半径和不透明度都乘它。在绘制阶段读。
 * @param downscale 每个方向缩小的倍数。越大越省，也越软。
 */
internal fun Modifier.downsampledBlur(
    backdrop: Backdrop,
    radius: Dp,
    progress: () -> Float = { 1f },
    downscale: Float = 4f,
): Modifier = this then DownsampledBlurElement(backdrop, radius, progress, downscale)

@SuppressLint("ModifierNodeInspectableProperties")
private data class DownsampledBlurElement(
    val backdrop: Backdrop,
    val radius: Dp,
    val progress: () -> Float,
    val downscale: Float,
) : ModifierNodeElement<DownsampledBlurNode>() {

    override fun create() = DownsampledBlurNode(backdrop, radius, progress, downscale)

    override fun update(node: DownsampledBlurNode) {
        node.backdrop = backdrop
        node.radius = radius
        node.progress = progress
        node.downscale = downscale
        node.invalidateDraw()
    }
}

private class DownsampledBlurNode(
    var backdrop: Backdrop,
    var radius: Dp,
    var progress: () -> Float,
    var downscale: Float,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    /** 小纹理。 */
    private var layer: GraphicsLayer? = null

    /**
     * 组件的位置：backdrop 靠它算出自己相对组件的偏移。和 backdrop 库的 drawBackdrop 一样
     * 存成状态，位置一变绘制就失效重来。
     */
    private var coordinates: LayoutCoordinates? by mutableStateOf(null, neverEqualPolicy())

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer().apply {
            // 必须离屏：不离屏的话，录制时的缩小和绘制时的放大只是坐标变换，两者一抵消，背后的
            // 内容还是按原尺寸画，等于没省（同光雾，见 GlassMistNode）。
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
        coordinates = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) this.coordinates = coordinates
    }

    override fun ContentDrawScope.draw() {
        val layer = layer
        val coordinates = coordinates
        val p = progress().coerceIn(0f, 1f)
        if (layer != null && coordinates != null && p > 0f && size.width >= 1f && size.height >= 1f) {
            val smallRadius = radius.toPx() * p / downscale
            // BlurEffect 按值比较，半径没变时这次赋值是空操作。
            layer.renderEffect = if (smallRadius > 0f) BlurEffect(smallRadius, smallRadius, TileMode.Clamp) else null
            layer.alpha = p
            val small = IntSize(ceil(size.width / downscale).toInt(), ceil(size.height / downscale).toInt())
            val density = this
            layer.record(small) {
                scale(1f / downscale, pivot = Offset.Zero) {
                    with(backdrop) { drawBackdrop(density, coordinates) }
                }
            }
            // 小纹理按整像素向上取整，放大回来可能比组件多出几个像素，裁掉。
            clipRect {
                scale(downscale, pivot = Offset.Zero) { drawLayer(layer) }
            }
        }
        drawContent()
    }
}
