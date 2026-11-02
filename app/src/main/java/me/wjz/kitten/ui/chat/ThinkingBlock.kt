package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 回复上面那块「思考过程」：模型说这段话之前想了些什么（[me.wjz.kitten.llm.ChatMessage.Assistant.thinking]）。
 *
 * - 还在想（[live]）：一行"正在思考…"，字上扫着流光（和清单里正在执行的那一行一样，见 [shimmer]）；下面露出
 *   最新想到的那两行浅色小字，顶上那行淡出去。看得出它在往下想，又不会把一屏占满。
 * - 想完了：收成一行"想了 12 秒 ›"，和工具清单的行一个样子。点一下展开整段，尖角转向下；再点收回去。
 *
 * 默认收着：用户多半只看结论。想的时候点开了的，想完也不替用户收回去。
 *
 * 展开的整段是纯文字，不按 Markdown 排：想的过程里的星号、井号多半不是格式，硬排反而乱。左边一道竖线，
 * 和下面的正文一眼分得开。
 *
 * @param millis 想了多久，见 [thoughtLabel]。
 */
@Composable
internal fun ThinkingBlock(
    thinking: String,
    millis: Long,
    live: Boolean,
    modifier: Modifier = Modifier,
) {
    // 列表项滚出屏幕再滚回来也记得展没展开；正在生成的那条和生成完的正式消息是同一项（见 MessageList），
    // 想的时候点开的，想完照样开着。
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        ThinkingHeader(
            label = if (live) LIVE_LABEL else thoughtLabel(millis),
            live = live,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        // 还在想、又没展开：露出最新的那两行。想完了缩回去，只剩标题那一行。
        AnimatedVisibility(
            visible = live && !expanded,
            enter = expandVertically(BodySizeSpec) + fadeIn(BodyFadeSpec),
            exit = shrinkVertically(BodySizeSpec) + fadeOut(BodyFadeSpec),
        ) {
            LiveTail(thinking)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(BodySizeSpec) + fadeIn(BodyFadeSpec),
            exit = shrinkVertically(BodySizeSpec) + fadeOut(BodyFadeSpec),
        ) {
            FullThinking(thinking)
        }
    }
}

/**
 * 标题那一行：字、后面一个小尖角。按下垫一层淡天依蓝，松手才展开 / 收起（和工具清单的行一样）。
 * 不占满整行：底色只垫在字和尖角底下，看着是一个能点的小块，不像一条分隔栏。
 */
@Composable
private fun ThinkingHeader(label: String, live: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = ArrowSpec,
        label = "thinkingArrow",
    )
    val shimmerProgress = rememberShimmerProgress(active = live)
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (expanded) "已展开" else "已收起"
                onClick(label = if (expanded) "收起思考过程" else "展开思考过程") {
                    onToggle()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { onToggle() } }
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) {
                    drawRoundRect(colors.sliderIdle, cornerRadius = CornerRadius(HeaderRadius.toPx()), alpha = pressed)
                }
            }
            .heightIn(min = HeaderMinHeight)
            .padding(horizontal = BlockInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textTertiary,
            maxLines = 1,
            modifier = if (shimmerProgress != null) {
                Modifier.shimmer(progress = { shimmerProgress.value }, color = colors.accent)
            } else {
                Modifier
            },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier
                .size(ArrowSize)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * 还在想时露出来的最新那几行：字一直往上顶，只看得见最底下 [LiveTailLines] 行，顶上那截淡出去。
 *
 * 只排最后 [LiveTailChars] 个字：想的字可能有好几千，每冒一段就整段重新排版没必要，反正只露最底下两行。
 * 截出来的开头可能是半句话，正好落在淡出去的那截里。
 */
@Composable
private fun LiveTail(thinking: String) {
    val colors = KittenTheme.colors
    val style = MaterialTheme.typography.bodySmall
    val lineHeight = style.lineHeight.takeOrElse { style.fontSize * FallbackLineSpacing }
    val maxHeight = with(LocalDensity.current) { lineHeight.toDp() } * LiveTailLines
    // 超过两行才淡顶上那截：只有一行时淡掉的就是唯一那行字。在排版时定下，只在绘制时读，不重组。
    var overflowing by remember { mutableStateOf(false) }
    Text(
        text = thinking.trimEnd().takeLast(LiveTailChars),
        style = style,
        color = colors.textTertiary,
        onTextLayout = { overflowing = it.lineCount > LiveTailLines },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = BlockInset, end = BlockInset, bottom = BodyPaddingBottom)
            .clipToBounds()
            // 单独一层离屏画，DstIn 才只抠这几行字，不连背后的东西一起抠掉。
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (overflowing) {
                    drawRect(
                        brush = Brush.verticalGradient(0f to Color.Transparent, LiveTailFade to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
            .heightIn(max = maxHeight)
            // 量的时候不限高，摆的时候贴底：多出来的行顶到框外面，被上面的 clipToBounds 裁掉。
            .wrapContentHeight(align = Alignment.Bottom, unbounded = true),
    )
}

/** 展开后的整段：左边一道竖线，字往里缩一截。 */
@Composable
private fun FullThinking(thinking: String) {
    val colors = KittenTheme.colors
    Text(
        text = thinking.trim(),
        style = MaterialTheme.typography.bodySmall,
        color = colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = BlockInset, end = BlockInset, top = 2.dp, bottom = BodyPaddingBottom)
            .drawBehind {
                val x = BarWidth.toPx() / 2f
                drawLine(
                    color = colors.hairline,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = BarWidth.toPx(),
                )
            }
            .padding(start = BarWidth + BarGap),
    )
}

/**
 * 想完以后标题写什么："想了 12 秒""想了 1 分 5 秒"。不知道想了多久（[millis] 是 0：服务端一下子回了一整个、
 * 没有流式）就只写"思考过程"。不到半秒也算 1 秒，写"想了 0 秒"像没想。
 */
private fun thoughtLabel(millis: Long): String {
    if (millis <= 0L) return "思考过程"
    val seconds = ((millis + 500) / 1000).coerceAtLeast(1)
    return when {
        seconds < 60 -> "想了 $seconds 秒"
        seconds % 60 == 0L -> "想了 ${seconds / 60} 分钟"
        else -> "想了 ${seconds / 60} 分 ${seconds % 60} 秒"
    }
}

/** 还在想时标题写什么。和对话里还没冒字时那行"正在思考…"（TypingIndicator）一样，交接时字不变。 */
private const val LIVE_LABEL = "正在思考…"

/**
 * 标题、正文的字往里缩多少：和回复的正文（MessageBubble 的 ReplyInset）、工具清单那一行的字左边对齐，
 * 一路看下来是一条线。
 */
private val BlockInset = 8.dp

/** 标题那一行至少多高、按下时那层底色的圆角，和工具清单的行一样。 */
private val HeaderMinHeight = 30.dp
private val HeaderRadius = GlassRadius.Small

/** 尖角多大、转过去的弹簧，和工具清单的一样。 */
private val ArrowSize = 16.dp
private val ArrowSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)

/**
 * 还在想时露出最底下几行、排最后多少个字。两行：一行看不出在往下走，三行就开始挤正文了。200 个字够排
 * 满两行有余（一行二三十个汉字），宁多勿少：不够两行的话框会矮一截，冒字时一跳一跳的。
 */
private const val LiveTailLines = 2
private const val LiveTailChars = 200

/** 顶上淡出的那截占多高（框高的比例）：到这里才完全不透明。 */
private const val LiveTailFade = 0.6f

/** 主题里没给行高时按字号的多少倍算。Material 的 bodySmall 是 12sp 字、16sp 行高，差不多这个比例。 */
private const val FallbackLineSpacing = 1.35f

/** 正文和下面回复的字之间留多少。回复自己上面还有 6dp，加起来不贴着。 */
private val BodyPaddingBottom = 4.dp

/** 展开后左边那道竖线多粗、和字隔多远。 */
private val BarWidth = 2.dp
private val BarGap = 8.dp

/** 展开、收起，和想完时露出的那两行缩回去的动画，和工具清单展开整串的一样。 */
private val BodySizeSpec = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = IntSize.VisibilityThreshold,
)
private val BodyFadeSpec = spring<Float>(stiffness = 700f)
