package me.wjz.kitten.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassRim
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.max
import kotlin.math.min

/** 气泡的小尾巴长在哪一侧。 */
enum class BubbleSide {
    /** 起始那一侧（从左往右的语言里是左边）：模型的回复。 */
    Start,

    /** 结尾那一侧（右边）：用户自己的消息。 */
    End,
}

/**
 * 气泡外壳：一块带小尾巴的玻璃，里面放什么由调用方决定，从上往下排。
 *
 * ## 内边距由里面每一段自己给
 *
 * 外壳本身不加内边距，每一段自己留：文字段落用 [BubbleTextPadding]；图片四周留一小圈、自己带
 * 圆角（见 MessageBubble 的 BubbleImages）。一条消息里有图有字时，图和字各是一段，上下排就行。
 * 尾巴那一侧外壳会自己让出尾巴的宽度，里面的东西不会伸进尾巴里。
 *
 * ## 只模糊，不做液态玻璃
 *
 * 背后只是模糊一下（[BubbleBlur]），不提饱和、不做边缘折射。气泡一屏好几块，滚动时每一帧
 * 都要跟着背景重算一遍；折射是逐像素跑的着色器，省掉它滚动才顺。边缘靠一圈边光和阴影分出来，
 * 这两样只在气泡尺寸变的时候重画，滚动时不用重算。
 *
 * - 模型的回复：薄薄一层 glassTint。
 * - 用户的消息（[tinted]）：天依蓝上浅下深、半透明，上半截一道渐隐的亮光，背后的光晕照样
 *   透得过来。比主按钮浅一档：气泡一屏好几个，染深了满屏都是蓝块，压过模型的回复。
 *
 * ## 长按
 *
 * 传了 [onLongPress] 的话，长按气泡弹出菜单，参数是手指的窗口坐标（菜单从那里长出来）。按下
 * 就微微鼓起，滑走或者页面开始滚就收回；菜单开着时（[lifted]）一直鼓着，看得出菜单是对哪
 * 一条的。点一下什么都不做。
 */
@Composable
fun ChatBubble(
    side: BubbleSide,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    tinted: Boolean = false,
    lifted: Boolean = false,
    onLongPress: ((positionInWindow: Offset) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = KittenTheme.colors
    val shape = remember(side) {
        BubbleShape(
            radius = BubbleRadius,
            tailAtEnd = side == BubbleSide.End,
            tailWidthRatio = TailWidth / BubbleRadius,
            tailHeightRatio = TailHeight / BubbleRadius,
        )
    }
    val press = rememberGlassPressState()
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // 气泡在窗口里的位置，把长按时手指的位置（气泡自己的坐标）换成窗口坐标。只在长按时读，
    // 滚动时更新它不会引起重组。
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = LiftSpec,
        label = "bubbleLift",
    )

    Column(
        modifier = modifier
            .then(
                if (onLongPress != null) {
                    Modifier
                        .onGloballyPositioned { bounds = Rect(it.positionInWindow(), it.size.toSize()) }
                        .semantics {
                            // 读屏用户没法长按到某个点，菜单从气泡正中间弹出来。
                            onLongClick(label = "更多操作") {
                                currentOnLongPress?.invoke(bounds.center)
                                true
                            }
                        }
                        .pointerInput(press) {
                            detectGlassPress(
                                state = press,
                                onLongPress = { currentOnLongPress?.invoke(bounds.topLeft + it) },
                                onClick = {},
                            )
                        }
                } else {
                    Modifier
                },
            )
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = { blur(BubbleBlur.toPx()) },
                highlight = { GlassRim },
                layerBlock = {
                    val s = lerp(1f, PressedScale, max(press.pressProgress, lift))
                    scaleX = s
                    scaleY = s
                },
                onDrawSurface = {
                    if (tinted) {
                        // 上浅下深：一块有厚度的染色玻璃，而不是平涂。
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    colors.accentLight.copy(alpha = TintTopAlpha),
                                    colors.accent.copy(alpha = TintBottomAlpha),
                                ),
                            ),
                        )
                        // 上半截一道渐隐的亮光：没有它，染色玻璃看起来还是一块色块。
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.White.copy(alpha = SheenAlpha), Color.Transparent),
                                endY = size.height * 0.55f,
                            ),
                        )
                    } else {
                        drawRect(colors.glassTint)
                    }
                },
            )
            // 让出尾巴：尾巴是外壳的一部分，但不放内容。
            .padding(
                start = if (side == BubbleSide.Start) TailWidth else 0.dp,
                end = if (side == BubbleSide.End) TailWidth else 0.dp,
            ),
        content = content,
    )
}

/** 气泡里文字段落的内边距。 */
val BubbleTextPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

/**
 * 带小尾巴的气泡形状：圆角矩形，底下靠尾巴那一侧的角是直角，从那里伸出一条尾巴，尾巴的下沿
 * 和气泡底边连成一条直线，尖儿落在外框的下角。
 *
 * ## 为什么继承 CornerBasedShape
 *
 * backdrop 库里按形状算效果的地方只认圆角矩形，要从形状上读四个角的圆角：边光（GlassRim）
 * 按圆角算哪一段亮哪一段暗；边缘折射（lens）更严格，随便一个 GenericShape 交给它会直接抛异常。
 * 所以这里报给它们的是"整个外框（含尾巴）+ 尾巴那一角是直角"，实际裁出来、描边、投影用的是
 * 带尾巴的路径。气泡现在不做折射（见 [ChatBubble]），以后要打开也不用改形状。
 *
 * ## 尾巴的尺寸按圆角的比例给
 *
 * CornerBasedShape 交给子类的只有换算成像素的四个圆角，拿不到 density，没法直接用 dp。尾巴的
 * 宽、高写成圆角的倍数，在像素下照样按比例算。
 */
private class BubbleShape(
    private val tailAtEnd: Boolean,
    private val tailWidthRatio: Float,
    private val tailHeightRatio: Float,
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
) : CornerBasedShape(topStart, topEnd, bottomEnd, bottomStart) {

    constructor(
        radius: Dp,
        tailAtEnd: Boolean,
        tailWidthRatio: Float,
        tailHeightRatio: Float,
    ) : this(
        tailAtEnd = tailAtEnd,
        tailWidthRatio = tailWidthRatio,
        tailHeightRatio = tailHeightRatio,
        topStart = CornerSize(radius),
        topEnd = CornerSize(radius),
        // 尾巴那一角报直角：透镜按这个算，尾巴的尖儿正好在这个角上。
        bottomEnd = if (tailAtEnd) ZeroCornerSize else CornerSize(radius),
        bottomStart = if (tailAtEnd) CornerSize(radius) else ZeroCornerSize,
    )

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize,
    ): CornerBasedShape = BubbleShape(tailAtEnd, tailWidthRatio, tailHeightRatio, topStart, topEnd, bottomEnd, bottomStart)

    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection,
    ): Outline {
        // 两个上角就是气泡的圆角（矮的气泡里会被父类按高度缩小）。尾巴那一角报的 0 是给透镜看的，不用。
        val radius = topStart
        val width = size.width
        val height = size.height
        val tailWidth = radius * tailWidthRatio
        // 结尾那一侧在从右往左的语言里是左边。
        val onRight = tailAtEnd == (layoutDirection == LayoutDirection.Ltr)
        val bodyLeft = if (onRight) 0f else tailWidth
        val bodyRight = if (onRight) width - tailWidth else width
        val corner = CornerRadius(radius)

        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    left = bodyLeft,
                    top = 0f,
                    right = bodyRight,
                    bottom = height,
                    topLeftCornerRadius = corner,
                    topRightCornerRadius = corner,
                    bottomRightCornerRadius = if (onRight) CornerRadius.Zero else corner,
                    bottomLeftCornerRadius = if (onRight) corner else CornerRadius.Zero,
                ),
            )
        }

        // 尾巴：从气泡侧边往下、往外弯出去，尖儿落在外框的下角。起点不高过上面那个圆角。
        val edge = if (onRight) bodyRight else bodyLeft
        val tip = if (onRight) width else 0f
        val tailTop = (height - radius * tailHeightRatio).coerceAtLeast(radius)
        // 往气泡里多伸一截，和气泡叠在一起，合并成一条轮廓时接缝处不会漏出细线。
        val overlap = min(radius, bodyRight - bodyLeft) / 2f * if (onRight) -1f else 1f
        val tail = Path().apply {
            moveTo(edge, tailTop)
            // 控制点在直角上：起笔顺着气泡侧边往下，收笔顺着底边往外，尖儿和底边相切。
            quadraticTo(edge, height, tip, height)
            lineTo(edge + overlap, height)
            lineTo(edge + overlap, tailTop)
            close()
        }

        // 合成一条轮廓，而不是两段叠着的路径：边光是沿轮廓描边的，叠着画会在接缝处多描一道线。
        return Outline.Generic(Path().apply { op(body, tail, PathOperation.Union) })
    }
}

/** 气泡的圆角，和卡片同一档。 */
private val BubbleRadius = GlassRadius.Large

/**
 * 气泡背后的模糊。和输入栏一样：背景里那层点阵是锐利的，糊开一点，压在上面的字才不花。
 * 给大了背景就只剩一片颜色，看不出是透的。
 */
private val BubbleBlur = 4.dp

/** 尾巴往外伸多宽。 */
private val TailWidth = 6.dp

/** 尾巴在气泡侧边上从多高开始弯出去（从底边往上量）。越大尾巴越长、弯得越缓。 */
private val TailHeight = 16.dp

/**
 * 用户气泡染色的浓度：上沿是浅一档的天依蓝（accentLight），下沿是天依蓝本色（accent）。
 * 想再浅就调小这两个；想深回去，把上面的颜色换回 accent → accentDeep（主按钮那一套）。
 */
private const val TintTopAlpha = 0.6f
private const val TintBottomAlpha = 0.55f

/** 用户气泡上半截那道亮光最亮处的不透明度。 */
private const val SheenAlpha = 0.22f

/** 按下、菜单开着时气泡鼓到多大。气泡面积大，给一点点就看得出来，多了会顶到上下的气泡。 */
private const val PressedScale = 1.02f

/** 菜单开、关时气泡鼓起、收回的弹簧。 */
private val LiftSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 500f)
