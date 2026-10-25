package me.wjz.kitten.ui.component

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.lerp as lerpOffset

/**
 * 看大图：图片按原始宽高比放到最大，双指缩放、拖动，双击放大 / 还原。
 *
 * 背后和模态窗一样整块糊开、压暗，压得更重（viewerScrim）：照片什么颜色都有，背后暗下去才
 * 看得清照片本身。点图片外面、按返回键、点右上角的关闭，都会调用 [onDismissRequest]。打开、
 * 关闭都是弹簧：图片从小一点长出来、背后慢慢糊开。
 *
 * ## 为什么不用 GlassModal
 *
 * 模态窗的窗体会跟着手指拖动形变，和图片的双指缩放、拖动抢手势；看图也不需要再套一层玻璃框，
 * 照片本身就是主角。只有右上角的关闭是玻璃圆，折射的是糊开、压暗后的背景。
 *
 * 画在 [GlassOverlayHost] 上，外面必须有一个 host。
 *
 * @param image 要看的图片，null 表示关着。关的时候收起动画还要用这张图，所以里面记着最后一张。
 * @param backdrop 背景的 backdrop，和页面上的玻璃用的是同一个。
 */
@Composable
fun ImageViewer(
    image: Uri?,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
) {
    val visible = image != null
    val currentVisible by rememberUpdatedState(visible)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    var lastImage by remember { mutableStateOf(image) }
    if (image != null && image != lastImage) lastImage = image

    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (visible) OpenSpec else CloseSpec,
        )
    }
    // 收起的动画放完之前还得挂着。
    val shown by remember { derivedStateOf { currentVisible || progress.value != 0f } }

    BackHandler(enabled = visible) { currentOnDismiss() }

    val target = lastImage
    GlassOverlay(visible = shown && target != null) { _, behind ->
        if (target != null) {
            ViewerLayer(
                image = target,
                surroundings = rememberCombinedBackdrop(backdrop, behind),
                progress = { progress.value },
                onDismiss = { currentOnDismiss() },
            )
        }
    }
}

@Composable
private fun ViewerLayer(
    image: Uri,
    surroundings: Backdrop,
    progress: () -> Float,
    onDismiss: () -> Unit,
) {
    val colors = KittenTheme.colors
    val density = LocalDensity.current
    // 糊开、压暗后的背景，录下来给关闭按钮折射：折射没糊过的背景的话，按钮里透出来的是清楚的
    // 界面，像在遮罩上挖了个洞。
    val scrimBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：糊开、压暗，吞掉所有触摸，抬手时关掉。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "关闭图片"
                    onClick {
                        onDismiss()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        onDismiss()
                    }
                }
                // 必须写在绘制的 modifier 左边，录的才是它们画出来的东西。
                .layerBackdrop(scrimBackdrop)
                .downsampledBlur(surroundings, ScrimBlur, progress = progress, downscale = ScrimDownscale)
                .drawBehind { drawRect(colors.viewerScrim, alpha = progress().coerceIn(0f, 1f)) },
        )

        // 图片：按原始宽高比放到最大，居中在扣掉系统栏的区域里。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(ViewerPadding),
            contentAlignment = Alignment.Center,
        ) {
            // 按屏幕的大小解码：放大了也够清楚，又不至于把几千万像素的原图整张读进来。
            val decodeSize = with(density) { (maxOf(maxWidth, maxHeight).toPx() * DecodeOversample).roundToInt() }
                .coerceAtMost(MaxDecodePx)
            val bitmap = rememberImageBitmap(image, decodeSize)
            if (bitmap != null) {
                val aspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                val fitsWidth = aspect > maxWidth / maxHeight
                val width = if (fitsWidth) maxWidth else maxHeight * aspect
                val height = if (fitsWidth) maxWidth / aspect else maxHeight
                ZoomableImage(
                    bitmap = bitmap,
                    modifier = Modifier
                        .size(width, height)
                        .graphicsLayer {
                            val p = progress()
                            alpha = p.coerceIn(0f, 1f)
                            val s = lerp(StartScale, 1f, p)
                            scaleX = s
                            scaleY = s
                        }
                        .clip(ViewerShape),
                )
            }
        }

        GlassIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "关闭图片",
            onClick = onDismiss,
            backdrop = scrimBackdrop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(8.dp)
                .graphicsLayer { alpha = progress().coerceIn(0f, 1f) },
        )
    }
}

/**
 * 能缩放、拖动的图片。缩放以手指（双击时是点的位置）为中心；放大后最多拖到图片的边贴住框的
 * 边，不会拖出一片空白。
 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var scale by remember(bitmap) { mutableFloatStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .pointerInput(bitmap) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, MaxZoom)
                    // 以手指为中心缩放：手指下面的那一点缩放前后不动。坐标从图片中心量起。
                    val focus = centroid - Offset(size.width / 2f, size.height / 2f)
                    val zoomed = focus - (focus - offset) * (newScale / scale)
                    scale = newScale
                    offset = clampOffset(zoomed + pan, newScale, size)
                }
            }
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val fromScale = scale
                        val fromOffset = offset
                        val toScale: Float
                        val toOffset: Offset
                        if (fromScale > 1.01f) {
                            toScale = 1f
                            toOffset = Offset.Zero
                        } else {
                            toScale = DoubleTapZoom
                            val focus = tap - Offset(size.width / 2f, size.height / 2f)
                            toOffset = clampOffset(-focus * (DoubleTapZoom - 1f), DoubleTapZoom, size)
                        }
                        scope.launch {
                            animate(0f, 1f, animationSpec = DoubleTapSpec) { t, _ ->
                                scale = lerp(fromScale, toScale, t)
                                offset = lerpOffset(fromOffset, toOffset, t)
                            }
                        }
                    },
                )
            },
    ) {
        Image(
            bitmap = bitmap,
            contentDescription = "图片",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** 放大到 [scale] 时最多能拖多远：图片比框大出来的那一半。 */
private fun clampOffset(offset: Offset, scale: Float, size: IntSize): Offset {
    val maxX = size.width * (scale - 1f) / 2f
    val maxY = size.height * (scale - 1f) / 2f
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** 图片离屏幕边（扣掉系统栏之后）留多少。 */
private val ViewerPadding = 16.dp

/** 图片的圆角，和模态窗同一档。 */
private val ViewerShape = RoundedCornerShape(GlassRadius.Large)

/** 最多放大到几倍。 */
private const val MaxZoom = 5f

/** 双击放大到几倍。 */
private const val DoubleTapZoom = 2.5f

/** 按屏幕长边的几倍解码：放大一些也还清楚。 */
private const val DecodeOversample = 1.5f

/** 解码的长边上限（像素）：再大内存吃不消，放大看也够了。 */
private const val MaxDecodePx = 3000

/** 打开时图片从多大长到原尺寸。 */
private const val StartScale = 0.85f

/** 背后的模糊和降采样，和模态窗一样，见 [downsampledBlur]。 */
private val ScrimBlur = 4.dp
private const val ScrimDownscale = 4f

/** 打开带一点过冲，像被弹出来的；关闭不过冲，干脆利落。和模态窗一样。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 800f)

/** 双击放大、还原的动画。 */
private val DoubleTapSpec = spring<Float>(dampingRatio = 0.85f, stiffness = 400f)
