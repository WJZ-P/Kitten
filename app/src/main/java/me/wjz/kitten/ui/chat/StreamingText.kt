package me.wjz.kitten.ui.chat

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.flow.first
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 会一个字一个字冒出来的文字，流式回复用。
 *
 * ## 两条线
 *
 * - **显现线**：追着已经收到的字往后走。网络是一阵一阵推过来的，一次几个到几十个字，收到就画
 *   出来会一顿一顿地跳；这里让它自己匀速往前追，落下得越多追得越快（[CatchUpSeconds] 内追平），
 *   但不慢于 [MinCharsPerSecond]。
 * - **定型线**：跟在显现线后面，落后大约 [SettleSeconds] 的路程。
 *
 * 定型线之前的字就是正常的样子；两条线之间是刚冒出来的字，越靠近显现线越透明、越偏天依蓝；
 * 显现线之后的还没轮到，看不见。于是每个新字都是从一抹淡蓝里浮出来，再沉成正常的颜色。网络
 * 卡住时显现线停下，定型线照样追上来，最后几个字不会一直半透明地挂着。
 *
 * ## 不重组、不重排
 *
 * 文字每收到一段才重新排版一次，排的是已经收到的全部文字：还没显现的字其实已经排好了，只是
 * 被盖住，显现出来时不会把后面的字挤得跳来跳去。两条线每帧都在动，但不触发重组，也不重新
 * 排版。动画期间文字单独画在一个离屏图层里（擦字的时候不会把气泡底色一起擦掉），动画完就撤掉。
 *
 * ## 尺寸跟着显现线走
 *
 * 排版用的是全部文字，可往外报的尺寸只算到显现线：宽度到显现线为止，高度到显现线所在那一行
 * 的底边。不然字还落在网络后面时，气泡已经按收到的全部文字撑开，底下空出好几行。换到新的一行
 * 时，高度在 [HeightSmoothSeconds] 里平滑地长上去，不是一下子跳一行。只是报给父布局的尺寸在变，
 * 文字本身不用重新排版。
 *
 * @param streaming 第一次出现时是不是正在生成：是的话从第一个字开始冒，不是（历史消息）就直接
 *   整段显示。之后再变不影响：回复生成完、变成正式消息时，还没冒完的字接着冒完。
 */
@Composable
fun StreamingText(
    text: String,
    streaming: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val tint = KittenTheme.colors.accentStrong
    val revealed = remember { mutableFloatStateOf(if (streaming) 0f else text.length.toFloat()) }
    val settled = remember { mutableFloatStateOf(revealed.floatValue) }
    // 往外报的高度，平滑地追着显现线所在那一行的底边。从 0 开始：第一行是从无到有长出来的。
    val shownHeight = remember { mutableFloatStateOf(0f) }
    val length by rememberUpdatedState(text.length)
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            if (settled.floatValue >= length) {
                // 全都显示完了：歇着，等有新字进来。
                revealed.floatValue = length.toFloat()
                settled.floatValue = length.toFloat()
                snapshotFlow { length }.first { it > settled.floatValue }
            }
            var lastNanos = 0L
            while (settled.floatValue < length) {
                withFrameNanos { now ->
                    // 夹住单帧步长：卡了一下之后不该一口气冒出一大截。
                    val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1_000_000_000f).coerceAtMost(MaxFrameSeconds)
                    lastNanos = now
                    val target = length.toFloat()
                    val head = revealed.floatValue
                    val nextHead = min(target, head + max(MinCharsPerSecond, (target - head) / CatchUpSeconds) * dt)
                    val tail = settled.floatValue
                    val nextTail = min(nextHead, tail + max(MinSettleSpeed, (nextHead - tail) / SettleSeconds) * dt)
                    revealed.floatValue = nextHead
                    settled.floatValue = nextTail
                    textLayout?.let {
                        val target = revealedHeight(it, nextHead)
                        val current = shownHeight.floatValue
                        // 新的一行在很短的时间里长上去；变矮（不该发生）就直接跟上。
                        shownHeight.floatValue = if (target <= current) {
                            target
                        } else {
                            current + (target - current) * (1f - exp(-dt / HeightSmoothSeconds))
                        }
                    }
                }
            }
        }
    }

    Text(
        text = text,
        style = style,
        color = color,
        onTextLayout = { textLayout = it },
        modifier = modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val current = textLayout
                val head = revealed.floatValue
                if (current == null || head >= current.layoutInput.text.length) {
                    // 全都显现了（历史消息一开始就是）：照常报整段文字的尺寸。
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                } else {
                    val width = revealedWidth(current, head).roundToInt()
                        .coerceIn(constraints.minWidth, placeable.width)
                    val height = shownHeight.floatValue.roundToInt()
                        .coerceIn(constraints.minHeight, placeable.height)
                    layout(width, height) { placeable.place(0, 0) }
                }
            }
            .graphicsLayer {
                compositingStrategy = if (settled.floatValue < length) {
                    CompositingStrategy.Offscreen
                } else {
                    CompositingStrategy.Auto
                }
            }
            .drawWithContent {
                drawContent()
                val current = textLayout ?: return@drawWithContent
                if (settled.floatValue < current.layoutInput.text.length) {
                    drawReveal(current, settled.floatValue, revealed.floatValue, tint)
                }
            },
    )
}

/** 显现线走到 [head] 时，已经显现的字有多宽：前面整行的最宽处，和显现线在它那一行的位置。 */
private fun revealedWidth(layout: TextLayoutResult, head: Float): Float {
    val headLine = layout.getLineForOffset(head.toInt().coerceIn(0, layout.layoutInput.text.length))
    var width = xAt(layout, headLine, head)
    for (line in 0 until headLine) width = max(width, layout.getLineRight(line))
    return width
}

/** 显现线走到 [head] 时，已经显现的字有多高：到显现线所在那一行的底边。 */
private fun revealedHeight(layout: TextLayoutResult, head: Float): Float {
    val headLine = layout.getLineForOffset(head.toInt().coerceIn(0, layout.layoutInput.text.length))
    return layout.getLineBottom(headLine)
}

/**
 * 把还没定型的字盖掉：[settled] 之前原样留着；[settled] 到 [revealed] 之间越往后越透明、越偏
 * [tint]；[revealed] 之后整个擦掉。一行一行地画，字的位置从排版结果里取。
 */
private fun DrawScope.drawReveal(layout: TextLayoutResult, settled: Float, revealed: Float, tint: Color) {
    val length = layout.layoutInput.text.length
    val span = max(revealed - settled, 0.001f)
    // 离定型线多远：0 是已经定型，1 是刚冒头。
    fun freshness(index: Float) = ((index - settled) / span).coerceIn(0f, 1f)

    val firstLine = layout.getLineForOffset(settled.toInt().coerceIn(0, length))
    for (line in firstLine until layout.lineCount) {
        val start = layout.getLineStart(line)
        val end = layout.getLineEnd(line)
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        if (start >= revealed) {
            // 这一行整行都还没轮到，后面的行也一样：一次擦到底。
            drawRect(Color.Black, Offset(0f, top), Size(size.width, size.height - top), blendMode = BlendMode.DstOut)
            break
        }
        val from = max(settled, start.toFloat())
        val to = min(revealed, end.toFloat())
        val left = xAt(layout, line, from)
        val right = xAt(layout, line, to)
        if (right > left) {
            val fromFresh = freshness(from)
            val toFresh = freshness(to)
            // 先往刚冒出来的字上染一层天依蓝（只染在字上），再按新旧把它们擦淡。
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(tint.copy(alpha = fromFresh * FreshTint), tint.copy(alpha = toFresh * FreshTint)),
                    startX = left,
                    endX = right,
                ),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                blendMode = BlendMode.SrcAtop,
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black.copy(alpha = fromFresh), Color.Black.copy(alpha = toFresh)),
                    startX = left,
                    endX = right,
                ),
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                blendMode = BlendMode.DstOut,
            )
        }
        if (revealed < end) {
            // 这一行的后半截还没轮到。
            val cut = xAt(layout, line, revealed)
            drawRect(Color.Black, Offset(cut, top), Size(size.width - cut, bottom - top), blendMode = BlendMode.DstOut)
        }
    }
}

/** 第 [index] 个字（可以带小数，落在两个字中间）在这一行里的横坐标。 */
private fun xAt(layout: TextLayoutResult, line: Int, index: Float): Float {
    val start = layout.getLineStart(line)
    val end = layout.getLineEnd(line)
    val i = index.toInt().coerceIn(start, end)
    val x = edgeAt(layout, line, i)
    if (i >= end) return x
    return lerp(x, edgeAt(layout, line, i + 1), (index - i).coerceIn(0f, 1f))
}

/** 第 [offset] 个字左边缘的横坐标。行尾那个位置算在这一行的最右边，不是下一行的开头。 */
private fun edgeAt(layout: TextLayoutResult, line: Int, offset: Int): Float =
    if (offset >= layout.getLineEnd(line)) layout.getLineRight(line) else layout.getHorizontalPosition(offset, true)

/** 显现线最慢每秒走几个字。收到的字不多时就按这个速度往外冒。 */
private const val MinCharsPerSecond = 24f

/** 落下的字在多少秒内追平。网络一下子推来一大段时，靠它加速，不会越落越远。 */
private const val CatchUpSeconds = 0.6f

/** 定型线落后显现线大约多少秒的路程：一个字从冒头到完全显出来大约这么久。 */
private const val SettleSeconds = 0.3f

/** 定型线最慢每秒走几个字：网络停住时，最后几个字靠它在一小会儿里定型。 */
private const val MinSettleSpeed = 12f

/** 刚冒头的字染多少天依蓝。0 就是只淡入、不变色。 */
private const val FreshTint = 0.6f

/** 单帧步长的上限（秒）。 */
private const val MaxFrameSeconds = 0.05f

/**
 * 换到新一行时，气泡长高的快慢（指数逼近的时间常数，秒）：大约这么久长出六成，三倍这么久
 * 基本长满。给大了气泡跟不上字，新一行的字会冒在气泡外面被裁掉。
 */
private const val HeightSmoothSeconds = 0.06f
