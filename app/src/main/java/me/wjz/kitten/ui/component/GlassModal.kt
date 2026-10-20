package me.wjz.kitten.ui.component

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 液态玻璃模态窗。
 *
 * 打开时整个界面糊开、压暗一点，中间浮出一块玻璃窗。点窗外面（抬手时）、按返回键、或者点
 * 右上角的关闭按钮，都会调用 [onDismissRequest]；关不关由调用方决定。窗开着时外面的界面
 * 不响应触摸。
 *
 * ## 尺寸
 *
 * - 宽度：可用宽度的 [ModalWidthFraction]，但不超过 [ModalMaxWidth]——平板上铺满九成会
 *   宽得没法读。
 * - 高度：跟着内容走，最高到可用高度的 [ModalHeightFraction]，再多就在窗里滚动。
 *
 * "可用"指的是扣掉系统栏和键盘之后的区域：键盘弹起时窗会缩到键盘上面，正在输入的框会被
 * 滚进视野。
 *
 * ## 层次
 *
 * ```
 * 遮罩     "背景 + 界面"整块高斯模糊（[ScrimBlur]，降采样做，见 [ScrimDownscale]），再压一层 scrim 色
 * 窗体     自己的底色 + 自己的光雾，雾在边缘被透镜掰弯（[ModalRefraction]）；录进一份 backdrop
 * 窗里的玻璃（输入框、按钮……）折射窗体，content 的参数就是它
 * ```
 *
 * ## 窗体不读窗外
 *
 * 窗底下的光雾一直在流动。窗体要是折射窗外，雾每推进一帧，窗体那一大块就得跟着重新模糊、
 * 折射一遍，窗里的玻璃再跟着重算。所以窗体只折射自己的雾：底色挡住窗外，雾和透镜给出
 * 玻璃的质感，窗外怎么动都和它无关。
 *
 * 窗画在 [GlassOverlayHost] 上，原因和下拉菜单一样，见那边。窗里再弹出的下拉菜单是后挂上
 * 去的浮层，看得到这个窗。
 *
 * @param backdrop 背景的 backdrop，和页面上的玻璃用的是同一个。
 * @param content 窗里的内容。参数是窗里的玻璃该折射的 backdrop，不要再用外面的 [backdrop]：
 *   那样输入框里透出来的会是没模糊过的背景，像在窗上挖了个洞。
 */
@Composable
fun GlassModal(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.(backdrop: Backdrop) -> Unit,
) {
    val currentVisible by rememberUpdatedState(visible)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (visible) OpenSpec else CloseSpec,
        )
    }
    // 收起的动画放完之前窗还得挂着。
    val shown by remember { derivedStateOf { currentVisible || progress.value > 0f } }

    BackHandler(enabled = visible) { currentOnDismiss() }

    GlassOverlay(visible = shown) { _, behind ->
        ModalLayer(
            surroundings = rememberCombinedBackdrop(backdrop, behind),
            progress = { progress.value },
            onDismiss = { currentOnDismiss() },
            title = title,
            icon = icon,
            modifier = modifier,
            content = content,
        )
    }
}

@Composable
private fun ModalLayer(
    surroundings: Backdrop,
    progress: () -> Float,
    onDismiss: () -> Unit,
    title: String?,
    icon: ImageVector?,
    modifier: Modifier,
    content: @Composable ColumnScope.(backdrop: Backdrop) -> Unit,
) {
    val colors = KittenTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：把整个界面糊开、压暗。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "关闭"
                    onClick {
                        onDismiss()
                        true
                    }
                }
                // 吞掉所有触摸，抬手时才关。
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
                // 模糊跟着打开的进度从 0 涨到 ScrimBlur，界面是慢慢糊开的，而不是一下子变糊。
                .downsampledBlur(surroundings, ScrimBlur, progress = progress, downscale = ScrimDownscale)
                .drawBehind { drawRect(colors.scrim, alpha = progress().coerceIn(0f, 1f)) },
        )

        // 窗体：居中在扣掉系统栏和键盘之后的区域里。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
            contentAlignment = Alignment.Center,
        ) {
            val mistBackdrop = rememberLayerBackdrop()
            val surfaceBackdrop = rememberLayerBackdrop()
            Box(
                modifier = modifier
                    .width(min(maxWidth * ModalWidthFraction, ModalMaxWidth))
                    .heightIn(max = maxHeight * ModalHeightFraction)
                    .semantics { if (title != null) paneTitle = title }
                    // 窗自己的留白处也要接住触摸，不然会落到下面的遮罩上，把窗关掉。
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                    // 窗体、雾、内容一起淡入、长大。窗体不读窗外，三层套同一个变换就行。
                    .graphicsLayer { modalTransform(progress()) },
            ) {
                // 窗里的雾：只录下来给窗体折射，自己不上屏（alpha = 0）。直接画在屏幕上的话，
                // 底色不是完全不透明，会透出一份没被掰弯的雾，边缘出重影。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = 0f }
                        .layerBackdrop(mistBackdrop)
                        .glassMist(ModalShape, style = ModalMistStyle),
                )
                // 窗体单独画在一层，录下来给窗里的玻璃折射：输入框里透出来的是窗体的底色和雾，
                // 不是窗外界面上的字。不能和内容画在同一个节点上——layerBackdrop 会把内容也
                // 录进去，内容又在读它，成环。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .layerBackdrop(surfaceBackdrop)
                        .drawBackdrop(
                            backdrop = mistBackdrop,
                            shape = { ModalShape },
                            // 不模糊：窗体折射的是自己的雾，雾本来就是软的，糊了看不出区别，白算一遍。
                            effects = { glassEffects(ModalRadius, blurRadius = 0.dp, refraction = ModalRefraction) },
                            highlight = { GlassRim },
                            shadow = { ModalShadow },
                            // 底色和天依蓝都垫在雾下面，雾浮在最上面，和卡片里的雾一个样子。
                            onDrawBehind = {
                                drawRect(colors.modalSurface)
                                drawRect(colors.glassTint)
                            },
                        ),
                )
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 6.dp),
                ) {
                    if (title != null) {
                        GlassHeader(
                            title = title,
                            icon = icon,
                            trailing = {
                                IconButton(onClick = onDismiss) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "关闭",
                                        tint = colors.textSecondary,
                                    )
                                }
                            },
                        )
                    }
                    content(surfaceBackdrop)
                }
            }
        }
    }
}

/** 打开和关闭时窗体、雾和内容一起淡入、长大。 */
private fun GraphicsLayerScope.modalTransform(progress: Float) {
    alpha = progress.coerceIn(0f, 1f)
    val scale = lerp(ModalStartScale, 1f, progress)
    scaleX = scale
    scaleY = scale
}

/** 窗宽占可用宽度的比例。 */
private const val ModalWidthFraction = 0.9f

/** 窗宽的上限：平板上按比例铺开会宽得没法读，到这里就停。 */
private val ModalMaxWidth = 480.dp

/** 窗高最多占可用高度的比例，内容再多就在窗里滚动。 */
private const val ModalHeightFraction = 0.9f

/** 窗背后界面的模糊半径。 */
private val ScrimBlur = 4.dp

/**
 * 遮罩的模糊按 1/它 的宽高做，再拉伸回整屏，见 [downsampledBlur]。越大越省，也越软：
 * 拉伸本身也会糊一点，调大它之后可以把 [ScrimBlur] 调小一点补回来。
 */
private const val ScrimDownscale = 4f

/** 窗的圆角。和 tab 栏同一档：它是比卡片大一号的容器。 */
private val ModalRadius = GlassRadius.Large
private val ModalShape = RoundedCornerShape(ModalRadius)

/**
 * 窗体的折射量（折射带宽的倍数，卡片是 [DefaultRefraction]）。雾是软的，掰得轻了看不出边缘
 * 在折射；窗又大，边缘那一圈拉扯得明显一点才压得住这么大一块面。
 *
 * 折射带的宽度跟着圆角走（见 [glassEffects]），想让那一圈更宽得先把 [ModalRadius] 调大。
 */
private const val ModalRefraction = 4f

/** 窗里光雾的样子（颗粒、流速、浓度），见 [GlassMistStyle]。默认是卡片那套。 */
private val ModalMistStyle = GlassMistStyle()

/** 窗浮在整页最上面，阴影比卡片和下拉菜单都重。 */
private val ModalShadow = Shadow(radius = 32.dp, color = Color.Black.copy(alpha = 0.18f))

/** 打开时窗从这个大小长到原尺寸。 */
private const val ModalStartScale = 0.92f

/** 打开带一点过冲，像被弹出来的；关闭不过冲，干脆利落。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 800f)
