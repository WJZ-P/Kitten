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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.RectangleShape
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
import com.kyant.backdrop.effects.blur
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
 * 遮罩     "背景 + 界面"整块高斯模糊（[ScrimBlur]），再压一层 scrim 色，录进一份 backdrop
 * 窗体     折射遮罩，再糊重一点（[ModalGlassBlur]），边缘被掰弯；也录进一份 backdrop
 * 窗里的玻璃（输入框、按钮……）折射窗体，content 的参数就是它
 * ```
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
    val scrimBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：把整个界面糊开、压暗，再录下来给窗体和窗里的玻璃折射。
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
                // 顺序不能换：layerBackdrop 录的是它右边画出来的东西，也就是糊开之后的界面。
                .layerBackdrop(scrimBackdrop)
                .drawBackdrop(
                    backdrop = surroundings,
                    shape = { RectangleShape },
                    // 模糊跟着打开的进度从 0 涨到 ScrimBlur，界面是慢慢糊开的，而不是一下子变糊。
                    effects = { blur(ScrimBlur.toPx() * progress().coerceIn(0f, 1f)) },
                    highlight = null,
                    shadow = null,
                    onDrawSurface = { drawRect(colors.scrim, alpha = progress().coerceIn(0f, 1f)) },
                ),
        )

        // 窗体：居中在扣掉系统栏和键盘之后的区域里。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.ime)),
            contentAlignment = Alignment.Center,
        ) {
            val surfaceBackdrop = rememberLayerBackdrop()
            Box(
                modifier = modifier
                    .width(min(maxWidth * ModalWidthFraction, ModalMaxWidth))
                    .heightIn(max = maxHeight * ModalHeightFraction)
                    .semantics { if (title != null) paneTitle = title }
                    // 窗自己的留白处也要接住触摸，不然会落到下面的遮罩上，把窗关掉。
                    .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
            ) {
                // 窗体的玻璃单独画在底下一层，录下来给窗里的玻璃折射：输入框里透出来的是窗体那层
                // 干净的颜色，而不是窗外界面上的字。不能和内容画在同一个节点上——layerBackdrop
                // 会把内容也录进去，内容又在读它，成环。
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .layerBackdrop(surfaceBackdrop)
                        .drawBackdrop(
                            backdrop = scrimBackdrop,
                            shape = { ModalShape },
                            effects = { glassEffects(ModalRadius, ModalGlassBlur) },
                            highlight = { GlassRim },
                            shadow = { ModalShadow },
                            layerBlock = { modalTransform(progress()) },
                            onDrawSurface = { drawRect(colors.glassTint) },
                        ),
                )
                Column(
                    modifier = Modifier
                        .graphicsLayer { modalTransform(progress()) }
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

/** 打开和关闭时窗体和内容一起淡入、长大。两层各自套这一份，动起来才是一个整体。 */
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
 * 窗体自己的模糊，叠在 [ScrimBlur] 上面。比卡片重得多：窗外的字只糊了 4dp 还认得出来，
 * 透进窗里会和表单叠在一起抢着被读（试过 2dp）。糊到只剩颜色，窗里才干净。
 */
private val ModalGlassBlur = 12.dp

/** 窗的圆角。和 tab 栏同一档：它是比卡片大一号的容器。 */
private val ModalRadius = GlassRadius.Large
private val ModalShape = RoundedCornerShape(ModalRadius)

/** 窗浮在整页最上面，阴影比卡片和下拉菜单都重。 */
private val ModalShadow = Shadow(radius = 32.dp, color = Color.Black.copy(alpha = 0.18f))

/** 打开时窗从这个大小长到原尺寸。 */
private const val ModalStartScale = 0.92f

/** 打开带一点过冲，像被弹出来的；关闭不过冲，干脆利落。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 800f)
