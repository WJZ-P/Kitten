package me.wjz.kitten.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.compose.LocalMarkdownInlineContent
import com.mikepenz.markdown.compose.LocalMarkdownTypography
import com.mikepenz.markdown.compose.MarkdownElement
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownText
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownAnimations
import com.mikepenz.markdown.model.MarkdownColors
import com.mikepenz.markdown.model.MarkdownDimens
import com.mikepenz.markdown.model.MarkdownPadding
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.markdownAlertColors
import com.mikepenz.markdown.model.markdownAnimations
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotatorConfig
import com.mikepenz.markdown.model.markdownDimens
import com.mikepenz.markdown.model.markdownInlineContent
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.parseMarkdown
import com.mikepenz.markdown.model.rememberStreamingMarkdownState
import kotlinx.coroutines.flow.first
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.theme.KittenTheme
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import kotlin.math.max
import kotlin.math.min

/**
 * 模型回复里的 Markdown：标题、加粗、列表、引用、代码块、表格……
 *
 * 排版交给 mikepenz 的 multiplatform-markdown-renderer，底下是 JetBrains 的 Markdown 解析器
 * （IntelliJ 自己用的那个），GFM 的表格、任务列表、删除线都认。这里只管三件事：
 *
 * - 字号、颜色、间距跟着 Kitten 走（[chatMarkdownStyle]）。
 * - 块和块之间的间距自己排（[MarkdownBlocks]）：库默认在每个块前面垫一截，连块之间的空行也垫，
 *   段落之间空几行、隔多远就跟着变。
 * - 流式回复时字怎么冒出来，见下面。
 *
 * 数学公式（$…$、$$…$$）在 ChatMath 里：原文先在那儿整理一遍再交给这里解析，公式的排版也在那儿。
 *
 * ## 流式：节奏自己控制，喂给只追加的解析
 *
 * 和以前的纯文字一样有两条线（[RevealLines]）：
 *
 * - **显现线**：追着已经收到的字往后走。网络是一阵一阵推过来的，一次几个到几十个字，收到就画
 *   出来会一顿一顿地跳；这里让它自己匀速往前追，落下得越多追得越快（[CatchUpSeconds] 内追平），
 *   但不慢于 [MinCharsPerSecond]。
 * - **定型线**：跟在显现线后面，落后大约 [SettleSeconds] 的路程。
 *
 * 显现线走过的字才交给库的 StreamingMarkdownState。它只能往后加：写完的块（前面的段落、列完的
 * 清单）解析一次就定下来，之后只重新解析最后那个还没写完的块，回复再长，每帧要做的事也不多。
 *
 * 两条线之间是刚冒出来的字，越靠近显现线越透明、越偏天依蓝，每个新字都是从一抹淡蓝里浮出来，
 * 再沉成正常的颜色（段落、标题、列表和引用里的字，见 [RevealText]；代码块、表格是一行一行整行
 * 出现的）。网络卡住时显现线停下，定型线照样追上来，最后几个字不会一直半透明地挂着。
 *
 * 新的一行冒出来时，高度在很短的时间里长上去（[HeightSpec]），不是一下子跳一行。
 *
 * @param streaming 第一次出现时是不是正在生成：是的话从第一个字开始冒，不是（历史消息）就直接
 *   整段显示。之后再变不影响：回复生成完、变成正式消息时还是同一个组件，没冒完的字接着冒完。
 */
@Composable
fun ChatMarkdown(
    text: String,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    val animate = remember { streaming }
    val style = chatMarkdownStyle()
    // 公式的写法先整理一遍（\( \) 换成 $，中文和公式之间垫窄空格），见 ChatMath。
    val scan = remember(text) { scanMath(text) }
    if (animate) {
        StreamingMarkdown(
            scan = scan,
            live = streaming,
            style = style,
            modifier = modifier.fillMaxWidth().animateContentSize(HeightSpec),
        )
    } else {
        StaticMarkdown(scan = scan, style = style, modifier = modifier.fillMaxWidth())
    }
}

/** 一整段直接排出来（历史消息）。解析是同步的：列表里滑出来的那一条马上就有高度，不会先空一下再撑开。 */
@Composable
private fun StaticMarkdown(scan: MathScan, style: ChatMarkdownStyle, modifier: Modifier) {
    val text = scan.text
    val state = remember(text) { parseMarkdown(text) }
    Markdown(
        state = state,
        colors = style.colors,
        typography = style.typography,
        modifier = modifier,
        padding = style.padding,
        dimens = style.dimens,
        imageTransformer = NoImages,
        annotator = ChatAnnotator,
        components = ChatComponents,
        animations = style.animations,
        success = { success, components, blocksModifier ->
            MarkdownBlocks(
                nodes = success.node.children,
                components = components,
                stableContent = success.content,
                modifier = blocksModifier,
            )
        },
        // 解析失败（库说只有打开断言时才会）的话，至少把原文照样显示出来，不能整条消失。
        error = { errorModifier ->
            Text(text = text, style = style.typography.paragraph, color = KittenTheme.colors.textPrimary, modifier = errorModifier)
        },
    )
}

/**
 * 一边收一边冒的回复，见 [ChatMarkdown] 的"流式"一节。
 *
 * 写到一半的公式先扣着不显示，闭合了整个出现（[MathScan.safeEnd]）；回复写完了（[live] 变 false）
 * 还没闭合的，就不是公式了，照原文放出来。
 *
 * @param live 回复还在生成。
 */
@Composable
private fun StreamingMarkdown(scan: MathScan, live: Boolean, style: ChatMarkdownStyle, modifier: Modifier) {
    val latest by rememberUpdatedState(scan)
    val stillLive by rememberUpdatedState(live)
    val reveal = remember { RevealLines() }
    // 收到的字不再是之前那段的接续时（比如生成完换成正式消息，首尾的空白被修掉了），库那边的解析只能
    // 往后加、改不了前面的，只好换一份新的，把已经显现的字一次加进去。
    var generation by remember { mutableIntStateOf(0) }

    key(generation) {
        val markdown = rememberStreamingMarkdownState()
        LaunchedEffect(markdown) {
            // 已经交给解析的字数，和最近一次核对过"是接续"的原文。
            var appended = 0
            var checked: String? = null
            var lastNanos = 0L
            while (true) {
                val now = latest
                val current = now.text
                if (current !== checked) {
                    if (!current.startsWith(markdown.content)) {
                        generation++
                        return@LaunchedEffect
                    }
                    checked = current
                }
                // 这一轮最多显示到哪：写到一半的公式扣着（回复写完了就不扣）。已经交出去的收不回来，不往回退。
                val limit = if (stillLive) maxOf(appended, now.safeEnd(current.length)) else current.length
                if (reveal.settled >= limit) {
                    // 能显示的都显示完了：歇着，等有新字进来，或者回复写完了、把扣着的放出来。
                    reveal.revealed = limit.toFloat()
                    reveal.settled = limit.toFloat()
                    if (appended < limit) {
                        markdown.append(current.substring(appended, limit))
                        appended = limit
                    }
                    val wasLive = stillLive
                    snapshotFlow { latest.text !== current || stillLive != wasLive }.first { it }
                    lastNanos = 0L
                    continue
                }
                withFrameNanos { frameNanos ->
                    // 夹住单帧步长：卡了一下之后不该一口气冒出一大截。
                    val dt = if (lastNanos == 0L) 0f else ((frameNanos - lastNanos) / 1_000_000_000f).coerceAtMost(MaxFrameSeconds)
                    lastNanos = frameNanos
                    reveal.step(target = limit.toFloat(), dt = dt)
                }
                val head = reveal.revealed.toInt().coerceAtMost(limit)
                // 显现线走到一个公式中间了：整个公式一起出来，显现线跟着跳到公式后面，公式照样从淡蓝里浮出来。
                val shown = if (stillLive) maxOf(head, now.safeEnd(head)) else head
                if (shown > head) reveal.revealed = shown.toFloat()
                if (shown > appended) {
                    markdown.append(current.substring(appended, shown))
                    appended = shown
                }
            }
        }

        CompositionLocalProvider(LocalReveal provides reveal) {
            Markdown(
                streamingMarkdownState = markdown,
                colors = style.colors,
                typography = style.typography,
                modifier = modifier,
                padding = style.padding,
                dimens = style.dimens,
                imageTransformer = NoImages,
                annotator = ChatAnnotator,
                components = ChatComponents,
                animations = style.animations,
                success = { snapshot, components, blocksModifier ->
                    // 定下来的块从这一份原文里取字，只在又定下来一块时才重新拷一份。每帧给一份新的字符串的话，
                    // 库会当成每个块的原文都变了，整篇重新排一遍。
                    val stableContent = remember(snapshot.stableAst.size) { markdown.content.toString() }
                    MarkdownBlocks(
                        nodes = snapshot.stableAst + snapshot.unstableAstTail,
                        components = components,
                        stableContent = stableContent,
                        stableCount = snapshot.stableAst.size,
                        tailContent = markdown.content.toString(),
                        modifier = blocksModifier,
                    )
                },
            )
        }
    }
}

/**
 * 两条线的位置（源文本里的第几个字，带小数），见 [ChatMarkdown]。每帧都在变，只在绘制里读，不引起重组。
 */
@Stable
private class RevealLines {
    var revealed by mutableFloatStateOf(0f)
    var settled by mutableFloatStateOf(0f)

    /** 往前走一帧：显现线追着 [target]（收到的字数）走，定型线追着显现线走。 */
    fun step(target: Float, dt: Float) {
        val head = revealed
        val nextHead = min(target, head + max(MinCharsPerSecond, (target - head) / CatchUpSeconds) * dt)
        val tail = settled
        val nextTail = min(nextHead, tail + max(MinSettleSpeed, (nextHead - tail) / SettleSeconds) * dt)
        revealed = nextHead
        settled = nextTail
    }
}

/** 正在冒字的那条回复的两条线。历史消息里是 null：字一开始就全在，不用淡入。 */
private val LocalReveal = staticCompositionLocalOf<RevealLines?> { null }

/**
 * 一串顶层的块（段落、标题、列表……）竖着排，块之间隔 [BlockGap]。块之间的空行（EOL）、空白不画、
 * 也不占地方，所以模型空了一行还是两行，看起来都一样。
 *
 * 每个块按它在原文里的起点做 key：流式时还没写完的块（在 tail 里）写完了挪进 stable，还是同一个，
 * 状态不会丢（[RevealText] 记着的排版结果丢了的话，那一帧刚冒出来的字会全不透明地闪一下）。
 *
 * 这些块里的公式在这里统一量好、做成占位的画法（[rememberMathContent]），交给段落里的字按 id 去找
 * （Markdown 库从 LocalMarkdownInlineContent 里取）。表格格子里的公式也能找到。
 *
 * @param stableContent 前 [stableCount] 个块取字用的原文。
 * @param tailContent 剩下的块（流式时还没写完的那几个）取字用的原文。
 */
@Composable
private fun MarkdownBlocks(
    nodes: List<ASTNode>,
    components: MarkdownComponents,
    stableContent: String,
    modifier: Modifier = Modifier,
    stableCount: Int = nodes.size,
    tailContent: String = stableContent,
) {
    // 定下来的块里有哪些公式，只在又定下来一块时重新找；还没写完的那几块每次都找（就那么几块）。
    val stableFormulas = remember(stableCount, stableContent) {
        formulasIn(nodes.subList(0, stableCount), stableContent)
    }
    val formulas = if (stableCount >= nodes.size) {
        stableFormulas
    } else {
        stableFormulas + formulasIn(nodes.subList(stableCount, nodes.size), tailContent)
    }
    val math = rememberMathContent(
        formulas = formulas,
        fontSize = LocalMarkdownTypography.current.paragraph.fontSize * MathFontScale,
        color = KittenTheme.colors.textPrimary,
    )
    CompositionLocalProvider(LocalMarkdownInlineContent provides markdownInlineContent(math)) {
        Column(modifier = modifier) {
            var first = true
            nodes.forEachIndexed { index, node ->
                if (node.type == MarkdownTokenTypes.EOL || node.type == MarkdownTokenTypes.WHITE_SPACE) return@forEachIndexed
                key(node.startOffset) {
                    if (!first) Spacer(modifier = Modifier.height(BlockGap))
                    MarkdownElement(
                        node = node,
                        components = components,
                        content = if (index < stableCount) stableContent else tailContent,
                        includeSpacer = false,
                    )
                }
                first = false
            }
        }
    }
}

/**
 * 段落、标题里的字。排版和库自己的一样（同一套 AnnotatedString、同一个 MarkdownText），只是流式时
 * 把还没定型的字擦淡、染上天依蓝，见 [drawFresh]。
 *
 * 只有这一块里还有没定型的字时（[fresh]），才离屏画、每帧重画；字都定型了就和普通文字一样，
 * 滚动、别的块在冒字时都不用重画它。
 *
 * @param contentChildType 标题的字在哪个子节点里（ATX 标题是 ATX_CONTENT，不含前面的 `#`）。
 */
@Composable
private fun RevealText(
    model: MarkdownComponentModel,
    style: TextStyle,
    contentChildType: IElementType? = null,
    heading: Boolean = false,
) {
    val node = model.node
    val settings = annotatorSettings()
    val styled = buildAnnotatedString {
        pushStyle(style.toSpanStyle())
        buildMarkdownAnnotatedString(
            content = model.content,
            node = contentChildType?.let { node.findChildOfType(it) } ?: node,
            annotatorSettings = settings,
        )
        pop()
    }
    val reveal = LocalReveal.current
    val tint = KittenTheme.colors.accentStrong
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fresh by remember(reveal, node) {
        derivedStateOf { reveal != null && reveal.settled < node.endOffset }
    }

    MarkdownText(
        content = styled,
        node = node,
        style = style,
        onTextLayout = { result, _ -> layout = result },
        sourceContent = model.content,
        modifier = Modifier
            .then(if (heading) Modifier.semantics { heading() } else Modifier)
            .graphicsLayer {
                // 擦字的时候不能把下面的东西一起擦掉，所以冒字期间单独画在一个离屏图层里，冒完就撤掉。
                compositingStrategy = if (fresh) CompositingStrategy.Offscreen else CompositingStrategy.Auto
            }
            .drawWithContent {
                drawContent()
                val lines = reveal
                val current = layout
                if (fresh && lines != null && current != null) {
                    drawFresh(current, node.startOffset, node.endOffset, lines.settled, lines.revealed, tint)
                }
            },
    )
}

/**
 * 把一块里还没定型的字擦淡：源文本里 [settled] 之前的字原样留着；[settled] 到 [revealed] 之间
 * 越往后越透明、越偏 [tint]。一行一行地画，字的位置从排版结果里取。
 *
 * 两条线是源文本里的位置，排版结果里是渲染出来的字（`**`、`#` 这些标记已经去掉了）。这一块在源文本里
 * 占 [start]..[end]，按比例摊到渲染出来的字上：标记只有几个，摊下来差一两个字，看不出来。
 */
private fun DrawScope.drawFresh(
    layout: TextLayoutResult,
    start: Int,
    end: Int,
    settled: Float,
    revealed: Float,
    tint: Color,
) {
    val length = layout.layoutInput.text.length
    if (length == 0) return
    // 源文本里一个字，摊到渲染出来的字上是几个。
    val scale = length.toFloat() / max(end - start, 1)
    // 定型线落在渲染出来的第几个字上。之前的字都定型了。
    val from = ((settled - start) * scale).coerceIn(0f, length.toFloat())
    if (from >= length) return
    val span = max(revealed - settled, 0.001f)
    // 渲染出来的第 index 个字有多新：0 是已经定型，1 是刚冒头。
    fun freshness(index: Float) = ((start + index / scale - settled) / span).coerceIn(0f, 1f)

    val firstLine = layout.getLineForOffset(from.toInt().coerceIn(0, length))
    for (line in firstLine until layout.lineCount) {
        val lineFrom = max(from, layout.getLineStart(line).toFloat())
        val lineTo = layout.getLineEnd(line).toFloat()
        if (lineTo <= lineFrom) continue
        val left = xAt(layout, line, lineFrom)
        val right = xAt(layout, line, lineTo)
        if (right <= left) continue
        val top = layout.getLineTop(line)
        val bottom = layout.getLineBottom(line)
        val fromFresh = freshness(lineFrom)
        val toFresh = freshness(lineTo)
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

/** 一套 Markdown 的样子。是 data class：内容一样就算同一套，回复冒一段字、重组一次时库不会以为样式变了。 */
private data class ChatMarkdownStyle(
    val colors: MarkdownColors,
    val typography: MarkdownTypography,
    val padding: MarkdownPadding,
    val dimens: MarkdownDimens,
    val animations: MarkdownAnimations,
)

/**
 * 字号、颜色、间距。正文和用户气泡里的字一样是 bodyMedium；标题不用 Material 默认的 display 那几档
 * （对话里一个 `#` 能顶半屏），从 titleLarge 往下排。颜色都取自 KittenColors。
 *
 * 不用斜体：引用默认是斜体，中文没有斜体字形，硬掰斜了很难看，引用改成浅一档的颜色。
 */
@Composable
private fun chatMarkdownStyle(): ChatMarkdownStyle {
    val colors = KittenTheme.colors
    val type = MaterialTheme.typography
    val body = type.bodyMedium
    return ChatMarkdownStyle(
        colors = markdownColor(
            text = colors.textPrimary,
            codeBackground = colors.wellTint,
            inlineCodeBackground = colors.wellTint,
            dividerColor = colors.hairline,
            tableBackground = colors.controlTint,
            alert = markdownAlertColors(
                note = colors.accentStrong,
                tip = colors.positive,
                important = colors.iconViolet,
                warning = colors.warning,
                caution = colors.danger,
            ),
            highlightText = colors.textPrimary,
            highlightBackground = colors.sliderIdle,
        ),
        typography = markdownTypography(
            h1 = type.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            h2 = type.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            h3 = type.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            h4 = body.copy(fontWeight = FontWeight.SemiBold),
            h5 = body.copy(fontWeight = FontWeight.SemiBold),
            h6 = body.copy(fontWeight = FontWeight.SemiBold),
            text = body,
            code = body.copy(fontFamily = FontFamily.Monospace, fontSize = CodeFontSize),
            // 只换字体、不定字号：行内代码跟着它所在的那段走，标题里的就和标题一样大。
            inlineCode = TextStyle(fontFamily = FontFamily.Monospace),
            quote = body.copy(color = colors.textSecondary),
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            textLink = TextLinkStyles(style = SpanStyle(color = colors.accentStrong, textDecoration = TextDecoration.Underline)),
            table = body,
            alertTitle = body.copy(fontWeight = FontWeight.SemiBold),
        ),
        padding = markdownPadding(
            // 列表上下不另外留白：和别的块之间的距离统一由 BlockGap 管。
            list = 0.dp,
            listItemTop = ListItemGap / 2,
            listItemBottom = ListItemGap / 2,
            listIndent = ListIndent,
            // 代码块的内边距、圆角不在这里调：代码块是自己画的，见 ChatCodeBlock 文件底部。
            blockQuote = PaddingValues(start = 12.dp),
        ),
        dimens = markdownDimens(
            tableCellWidth = TableCellWidth,
            tableCellPadding = 10.dp,
            tableCornerSize = GlassRadius.Small,
        ),
        animations = markdownAnimations(animateTextSize = NoSizeAnimation),
    )
}

/**
 * 段落、标题换成会淡入的 [RevealText]，代码块换成带语言名、复制按钮和语法高亮的 [ChatCodeBlock]，
 * 别的块（列表、引用、表格……）用库自己的。列表、引用里的段落也走 paragraph，所以也会淡入；引用里的
 * 段落用引用那一档的颜色。
 *
 * 任务列表的勾选框用 Material 3 的那个：传了自己的组件，库就不会自动换上 m3 的了。
 *
 * 是顶层的常量：每次给的都是同一份，库不会以为组件变了、整篇重排。
 */
private val ChatComponents: MarkdownComponents = markdownComponents(
    paragraph = {
        // 一段里只有一个 $$…$$：单独排成一块公式，居中（见 ChatMath）。
        val formula = displayFormulaOf(it.node, it.content)
        if (formula != null) {
            DisplayMath(
                formula = formula,
                fontSize = it.typography.paragraph.fontSize * MathFontScale,
                color = KittenTheme.colors.textPrimary,
                fallbackStyle = it.typography.paragraph,
                appear = LocalReveal.current != null,
            )
        } else {
            val inQuote = it.node.parent?.type == MarkdownElementTypes.BLOCK_QUOTE
            RevealText(it, if (inQuote) it.typography.quote else it.typography.paragraph)
        }
    },
    heading1 = { RevealText(it, it.typography.h1, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    heading2 = { RevealText(it, it.typography.h2, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    heading3 = { RevealText(it, it.typography.h3, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    heading4 = { RevealText(it, it.typography.h4, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    heading5 = { RevealText(it, it.typography.h5, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    heading6 = { RevealText(it, it.typography.h6, MarkdownTokenTypes.ATX_CONTENT, heading = true) },
    setextHeading1 = { RevealText(it, it.typography.h1, MarkdownTokenTypes.SETEXT_CONTENT, heading = true) },
    setextHeading2 = { RevealText(it, it.typography.h2, MarkdownTokenTypes.SETEXT_CONTENT, heading = true) },
    // 代码块：库负责从节点里取出代码和语言名，画成什么样是 ChatCodeBlock 的事（语言名、复制、上色）。
    // 有了结束的 ``` 才算写完；历史消息里的不会再变长，没写结束符也算写完。
    codeFence = { model ->
        val finished = LocalReveal.current == null ||
            model.node.findChildOfType(MarkdownTokenTypes.CODE_FENCE_END) != null
        MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
            ChatCodeBlock(code = code, language = language, style = style, finished = finished)
        }
    },
    // 缩进四格的老式代码块：写不了语言名，不上色，只要复制。
    codeBlock = { model ->
        MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, style ->
            ChatCodeBlock(code = code, language = language, style = style, finished = true)
        }
    },
    checkbox = { MarkdownCheckBox(it.content, it.node, it.typography.text) },
)

/**
 * 段落里的单个换行照样换行。
 *
 * 标准 Markdown 把段落里的单个换行当成空格（"软换行"：手动折过行的原文排出来还是一整段），库默认也是
 * 这样。可模型回复里的单个换行几乎都是真想换行，比如一行写一步：当成空格的话几行字挤成一行，中间还
 * 夹着空格，中文尤其难看。以前回复是纯文字，换行原样保留，这里保持一样。
 */
private val ChatAnnotator = markdownAnnotator(
    config = markdownAnnotatorConfig(eolAsNewLine = true),
    // 公式不贴原文，贴一个占位，画法在 MarkdownBlocks 里统一量好（见 ChatMath）。别的节点照常处理。
    annotate = { content, child -> appendMath(content, child) },
)

/**
 * 不加载图片：模型给的图片链接不去下载（省流量，也不替模型去访问随便哪个地址），只当没有。
 *
 * 只建一个：库每次重组都会把它放进 CompositionLocal，每次 new 一个新的话，库会以为它变了，整篇重排。
 */
private val NoImages = NoOpImageTransformerImpl()

/** 库默认给每段字的尺寸变化做动画（为了图片加载完撑开），不要：流式时每冒一个字都会动一下。高度动画在外面统一做。 */
private val NoSizeAnimation: Modifier.() -> Modifier = { this }

/**
 * 把一段 Markdown 压成一两行能直接显示的纯文字：去掉 `**`、`#`、`>`、列表符号和代码块的围栏，链接只留
 * 文字。给只能放纯文字的地方用（任务卡片上那句最新的话），不求精确，看着不带符号就行。
 */
internal fun String.markdownPreview(): String =
    lineSequence()
        .filterNot { FenceLine.matches(it) || RuleLine.matches(it) || TableRuleLine.matches(it) }
        .map { line -> line.replace(LinePrefix, "").trimEnd() }
        .filter { it.isNotBlank() }
        .joinToString("\n")
        .replace(ImageOrLink) { it.groupValues[1] }
        .replace(Emphasis) { it.groupValues[2] }
        .replace(InlineCode) { it.groupValues[1] }

private val FenceLine = Regex("""^\s*(```|~~~).*$""")
private val RuleLine = Regex("""^\s*([-*_])(\s*\1){2,}\s*$""")
private val TableRuleLine = Regex("""^\s*\|?(\s*:?-{3,}:?\s*\|)+\s*:?-*:?\s*\|?\s*$""")

/** 行首的标题 `#`、引用 `>`、列表符号（含任务列表的方框）。 */
private val LinePrefix = Regex("""^\s*(#{1,6}\s+|(>\s?)+|[-*+]\s+(\[[ xX]]\s+)?)""")
private val ImageOrLink = Regex("""!?\[([^\]]*)]\([^)]*\)""")
private val Emphasis = Regex("""(\*\*|__|~~|\*)(.+?)\1""")
private val InlineCode = Regex("""`([^`]+)`""")

/** 顶层的块和块之间隔多远。比一行字的行距（bodyMedium 20sp）小一截：段落之间分得开，又不至于稀稀拉拉。 */
private val BlockGap = 10.dp

/** 列表里两项之间隔多远（上下各一半）。 */
private val ListItemGap = 4.dp

/** 嵌套的列表每深一层往里缩多少。 */
private val ListIndent = 12.dp

/** 代码块的字号。等宽字比正文宽，小一号一行才放得下常见的代码，放不下的左右滑。 */
private val CodeFontSize = 13.sp

/**
 * 公式的字号是正文的多少倍。数学字体（KaTeX）的字面比正文字体小一圈，原样大小夹在句子里显得小；
 * 网页上的 KaTeX 也是放大一点排的（1.21 倍）。
 */
private const val MathFontScale = 1.1f

/** 表格每一格多宽。一屏放得下两三格，多的左右滑。 */
private val TableCellWidth = 140.dp

/**
 * 换到新一行时，回复长高的弹簧。很硬、不回弹：一行在一百来毫秒里长出来。给软了跟不上字，新一行的
 * 字会在被裁掉的那截里冒出来。
 */
private val HeightSpec = spring(
    dampingRatio = 1f,
    stiffness = 1500f,
    visibilityThreshold = IntSize.VisibilityThreshold,
)

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
