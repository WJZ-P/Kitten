package me.wjz.kitten.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

/*
 * 回复里的数学公式（LaTeX）：$…$ 是夹在句子里的行内公式，$$…$$ 是单独一块的公式。
 *
 * 排版交给 huarangmeng/latex（用 Compose 写的 LaTeX 排版库，MIT 协议，带 KaTeX 的数学字体）。整个 App
 * 只有这个文件直接用它：哪天要换库、降级，只改这里。
 *
 * ## 三步
 *
 * 1. 交给 Markdown 解析之前，先把原文整理一遍（[scanMath]）：
 *    - `\(…\)`、`\[…\]` 换成 `$…$`、`$$…$$`。有的模型爱用这种写法，Markdown 会把反斜杠当转义吃掉，
 *      剩下一对光秃秃的括号。
 *    - 中文紧挨着公式时，中间垫一个窄空格（U+2009）。Markdown 解析器认公式的规矩是"开头的 $ 前面
 *      不能是字母、数字或标点，收尾的 $ 后面不能是字母或数字"，防的是"$5 和 $10"这种价格被认成公式；
 *      可中文字也算字母，"答案是$\pi$"、"$e$、$i$"就都认不出来。垫一个窄空格就认得了，中文和公式
 *      之间本来也该空一点。
 * 2. 解析出来的公式不再原样贴原文，换成一个占位（[appendMath]）。占位的大小是库量好的公式尺寸，公式
 *    就画在那个位置上，和前后的字排在同一行（[rememberMathContent]）。
 * 3. 一段里只有一个 $$…$$ 的，单独排成一块，居中，太宽了左右滑（[DisplayMath]）。
 *
 * 公式写错了、或者用了库不认识的写法，量不出来，就照原文显示，不会空出一块。
 *
 * ## 流式
 *
 * 写到一半的公式（`$\frac{1}{` 还没闭合）先不显示，等闭合了整个出现（[MathScan.safeEnd]），免得一串
 * 反斜杠闪一下。没闭合的 $ 也可能就是个价格：遇到换行、等得太长、或者回复已经写完了，就不再等，
 * 照原文显示。
 */

/**
 * 整理过的原文，和里面的公式在哪，见 [scanMath]。
 *
 * @property text 整理过的原文，交给 Markdown 解析的是它。
 */
internal class MathScan(
    val text: String,
    /** 写完了的公式在 [text] 里的位置：从开头的 $ 到收尾的 $，按出现的顺序。 */
    private val formulas: List<IntRange>,
    /** 结尾还没闭合的那个公式从哪个 $ 开始，没有是 -1。 */
    private val pending: Int,
) {
    /**
     * 流式时原文显示到 [shown] 为止的话，会不会把公式切成两半：落在写完的公式中间，就挪到公式结尾
     * （整个一起出现）；落在还没闭合的公式里，就退到公式开头（先不显示）。没切到公式就原样返回。
     */
    fun safeEnd(shown: Int): Int {
        if (pending in 0 until shown) return pending
        for (range in formulas) {
            if (range.first >= shown) break
            if (shown <= range.last) return range.last + 1
        }
        return shown
    }
}

/** 整理原文，见文件开头的"三步"里的第 1 步。代码块、行内代码里的东西一律不动。 */
internal fun scanMath(raw: String): MathScan {
    val text = convertBracketDelimiters(raw)
    if ('$' !in text) return MathScan(text, emptyList(), -1)
    val code = codeMask(text)

    // 先配对：哪个 $ 和哪个 $ 是一个公式。
    val pairs = ArrayList<IntRange>()
    var pending = -1
    var i = 0
    while (i < text.length) {
        if (code[i] || text[i] != '$' || isEscaped(text, i)) {
            i++
            continue
        }
        val run = runLength(text, i, '$')
        val width = if (run >= 2) 2 else 1
        val after = i + width
        // 行内公式的 $ 后面紧跟着空白，不是公式的开头（"$ 5"）。
        if (width == 1 && after < text.length && text[after].isWhitespace()) {
            i += run
            continue
        }
        val close = findClose(text, code, after, width)
        when {
            close >= 0 -> {
                pairs += i until close + width
                i = close + width
            }
            close == OpenToEnd && text.length - i <= maxPending(width) -> {
                pending = i
                break
            }
            else -> i += run
        }
    }
    if (pairs.isEmpty()) return MathScan(text, emptyList(), pending)

    // 再垫窄空格：只给配上了对的公式垫，还没闭合的不动（流式时前面已经显示出去的字不能变）。
    val out = StringBuilder(text.length + pairs.size * 2)
    val formulas = ArrayList<IntRange>(pairs.size)
    var copied = 0
    for (pair in pairs) {
        val start = pair.first
        val end = pair.last + 1
        out.append(text, copied, start)
        if (start > 0 && blocksOpening(text[start - 1])) out.append(ThinSpace)
        val outStart = out.length
        out.append(text, start, end)
        formulas += outStart until out.length
        if (end < text.length && isWordChar(text[end])) out.append(ThinSpace)
        copied = end
    }
    out.append(text, copied, text.length)
    // 没闭合的公式在所有配上对的公式后面，前面垫了几个窄空格就往后挪几个。
    val shiftedPending = if (pending >= 0) pending + (out.length - text.length) else -1
    return MathScan(out.toString(), formulas, shiftedPending)
}

/**
 * 从 [from] 往后找收尾的 $（[width] 个）。返回它的位置；到结尾都没找到返回 [OpenToEnd]；中途断掉
 * （行内公式遇到换行、块级公式遇到空行、碰到代码）返回 -1，说明这个 $ 不是公式的开头。
 */
private fun findClose(text: String, code: BooleanArray, from: Int, width: Int): Int {
    var j = from
    while (j < text.length) {
        if (code[j]) return -1
        val c = text[j]
        if (c == '\n' && (width == 1 || isBlankLineAfter(text, j))) return -1
        if (c == '$' && !isEscaped(text, j)) {
            val run = runLength(text, j, '$')
            if (width == 1 && run == 1) {
                val next = text.getOrNull(j + 1)
                // 收尾的 $ 前面不能是空白（"$x $" 不算）；后面紧跟数字的不算（"$5 到 $10" 是价格）。
                if (!text[j - 1].isWhitespace() && (next == null || !next.isDigit())) return j
            } else if (width == 2 && run >= 2) {
                return j
            }
            j += run
            continue
        }
        j++
    }
    return OpenToEnd
}

/** [findClose] 找到结尾也没找到收尾的 $：流式时可能是还没写到。 */
private const val OpenToEnd = -2

/** 还没闭合的公式最多等多长（从开头的 $ 算起），超过了就不等，照原文显示。 */
private fun maxPending(width: Int): Int = if (width == 1) MaxPendingInline else MaxPendingDisplay

/**
 * 行内公式一般就几十个字（求根公式也才三十几个），写了这么长还没闭合，多半不是公式，是个价格之类的：
 * 给大了，"$5"后面的字会被扣着半天不出来；给小了，特别长的行内公式会先露一下原文再变成公式。
 */
private const val MaxPendingInline = 80

/** 块级公式可以写好几行。 */
private const val MaxPendingDisplay = 2000

/**
 * `\(…\)`、`\[…\]` 换成 `$…$`、`$$…$$`，代码里的不动。`\\`（LaTeX 里的换行）原样留着，后面的括号不算。
 *
 * 行内的 `\( x \)` 括号里常带着空格，换成 `$ x $` 的话 $ 紧挨着空格，就不算公式的边了：开头的往后、收尾的
 * 往前，挨着的空格去掉。块级的 $$ 不在乎空格，不用管。
 */
private fun convertBracketDelimiters(raw: String): String {
    if ('\\' !in raw) return raw
    val code = codeMask(raw)
    val out = StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && !code[i] && i + 1 < raw.length) {
            val next = raw[i + 1]
            when (next) {
                '\\' -> {
                    out.append(c).append(next)
                    i += 2
                    continue
                }
                '(' -> {
                    out.append('$')
                    i += 2
                    while (i < raw.length && (raw[i] == ' ' || raw[i] == '\t')) i++
                    continue
                }
                ')' -> {
                    while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\t')) out.setLength(out.length - 1)
                    out.append('$')
                    i += 2
                    continue
                }
                '[', ']' -> {
                    out.append("$$")
                    i += 2
                    continue
                }
            }
        }
        out.append(c)
        i++
    }
    return out.toString()
}

/**
 * 哪些字在代码里：``` / ~~~ 围起来的代码块，和反引号括起来的行内代码。代码里的 $、\( 都不是公式。
 * 只求大致准：和 Markdown 解析器认的有出入的话，最多是少垫一个窄空格、流式时少扣一下。
 */
private fun codeMask(text: String): BooleanArray {
    val mask = BooleanArray(text.length)
    var i = 0
    var lineStart = true
    while (i < text.length) {
        if (lineStart) {
            val fenceEnd = fencedBlockEnd(text, i)
            if (fenceEnd > i) {
                for (k in i until fenceEnd) mask[k] = true
                i = fenceEnd
                continue
            }
        }
        val c = text[i]
        if (c == '`') {
            val run = runLength(text, i, '`')
            val close = findBacktickRun(text, i + run, run)
            if (close >= 0) {
                for (k in i until close + run) mask[k] = true
                i = close + run
            } else {
                i += run
            }
            lineStart = false
            continue
        }
        lineStart = c == '\n'
        i++
    }
    return mask
}

/**
 * [lineStart] 这一行是不是一个代码块的开头（最多缩进三格，三个以上的 ` 或 ~）。是的话返回整个代码块
 * 结束的位置（结束那一行的下一个字；没写结束的就是全文结尾），不是返回 [lineStart]。
 */
private fun fencedBlockEnd(text: String, lineStart: Int): Int {
    var i = lineStart
    while (i < text.length && i - lineStart < 3 && text[i] == ' ') i++
    if (i >= text.length || (text[i] != '`' && text[i] != '~')) return lineStart
    val fenceChar = text[i]
    val fenceLength = runLength(text, i, fenceChar)
    if (fenceLength < 3) return lineStart
    var lineEnd = text.indexOf('\n', i)
    while (lineEnd >= 0) {
        var j = lineEnd + 1
        while (j < text.length && j - (lineEnd + 1) < 3 && text[j] == ' ') j++
        if (j < text.length && text[j] == fenceChar && runLength(text, j, fenceChar) >= fenceLength) {
            val closeEnd = text.indexOf('\n', j)
            return if (closeEnd < 0) text.length else closeEnd + 1
        }
        lineEnd = text.indexOf('\n', lineEnd + 1)
    }
    return text.length
}

/** 从 [from] 往后找一段正好 [length] 个的反引号（不是更长一段的一部分），找不到返回 -1。 */
private fun findBacktickRun(text: String, from: Int, length: Int): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == '`') {
            val run = runLength(text, i, '`')
            if (run == length) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}

/** 从 [start] 起连着几个 [char]。 */
private fun runLength(text: String, start: Int, char: Char): Int {
    var i = start
    while (i < text.length && text[i] == char) i++
    return i - start
}

/** 第 [index] 个字前面是不是有奇数个反斜杠，被转义了（`\$` 是一个普通的美元符号）。 */
private fun isEscaped(text: String, index: Int): Boolean {
    var count = 0
    var i = index - 1
    while (i >= 0 && text[i] == '\\') {
        count++
        i--
    }
    return count % 2 == 1
}

/** 这个换行后面紧跟着一个空行（只有空格的也算）：段落到这儿就断了，块级公式不会跨过去。 */
private fun isBlankLineAfter(text: String, newline: Int): Boolean {
    var k = newline + 1
    while (k < text.length && (text[k] == ' ' || text[k] == '\t')) k++
    return k < text.length && text[k] == '\n'
}

/** 公式开头的 $ 前面是这个字时，Markdown 解析器不认它是公式（字母、数字、标点），得垫一个窄空格。 */
private fun blocksOpening(c: Char): Boolean = isWordChar(c) || isParserPunctuation(c)

/** 解析器眼里的"字母"：字母（含汉字）、数字、下划线。 */
private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** 解析器眼里的标点：各类标点符号，加上数学符号（+、= 这些）和 $ ^ `。和它的判断保持一致。 */
private fun isParserPunctuation(c: Char): Boolean =
    c == '$' || c == '^' || c == '`' || ((ParserPunctuationMask shr Character.getType(c)) and 1) != 0

private val ParserPunctuationMask: Int =
    (1 shl Character.DASH_PUNCTUATION.toInt()) or
        (1 shl Character.START_PUNCTUATION.toInt()) or
        (1 shl Character.END_PUNCTUATION.toInt()) or
        (1 shl Character.CONNECTOR_PUNCTUATION.toInt()) or
        (1 shl Character.OTHER_PUNCTUATION.toInt()) or
        (1 shl Character.INITIAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.FINAL_QUOTE_PUNCTUATION.toInt()) or
        (1 shl Character.MATH_SYMBOL.toInt())

/**
 * 垫在中文和公式之间的窄空格（U+2009）。解析器把它当空白，所以认得出公式；显示出来只有普通空格的
 * 一半左右宽。
 */
private const val ThinSpace = ' '

/**
 * 段落里遇到一个公式：不贴原文，贴一个占位，id 是公式本身（同一个公式出现几次共用一个占位的画法）。
 * 不是公式的节点返回 false，交还给 Markdown 库照常处理。
 *
 * 占位底下的"替身文字"是原文（带 $）：公式量不出来、没有对应的画法时，显示的就是它。
 */
internal fun AnnotatedString.Builder.appendMath(content: String, node: ASTNode): Boolean {
    val formula = formulaOf(node, content) ?: return false
    appendInlineContent(mathId(formula), node.getTextInNode(content).toString())
    return true
}

/** 这些块里出现的所有公式（[formulaOf] 的写法），按出现的顺序，同一个只算一次。 */
internal fun formulasIn(nodes: List<ASTNode>, content: String): Set<String> {
    val found = LinkedHashSet<String>()
    fun visit(node: ASTNode) {
        when (node.type) {
            GFMElementTypes.INLINE_MATH, GFMElementTypes.BLOCK_MATH -> formulaOf(node, content)?.let(found::add)
            // 代码里不会有公式，省得往里翻。
            MarkdownElementTypes.CODE_FENCE, MarkdownElementTypes.CODE_BLOCK, MarkdownElementTypes.CODE_SPAN -> Unit
            else -> node.children.forEach(::visit)
        }
    }
    nodes.forEach(::visit)
    return found
}

/**
 * 这一段是不是只有一个 $$…$$（前后的空白、换行不算）：是的话单独排成一块（[DisplayMath]），返回公式；
 * 不是返回 null。
 */
internal fun displayFormulaOf(paragraph: ASTNode, content: String): String? {
    val parts = paragraph.children.filter {
        it.type != MarkdownTokenTypes.WHITE_SPACE && it.type != MarkdownTokenTypes.EOL
    }
    val only = parts.singleOrNull() ?: return null
    if (only.type != GFMElementTypes.BLOCK_MATH) return null
    return formulaOf(only, content)
}

/**
 * 公式节点里要交给库排版的那一段：去掉外面的 $。不是公式、或者是空的（`$$$$`）返回 null。
 *
 * 行内公式前面加上 `\textstyle`：库默认按单独一块的大小排（分式、求和号都排得很高），夹在句子里会把
 * 那一行撑得老高；`\textstyle` 是 LaTeX 里行内公式本来的排法。
 */
private fun formulaOf(node: ASTNode, content: String): String? {
    val raw = node.getTextInNode(content).toString()
    return when (node.type) {
        GFMElementTypes.INLINE_MATH ->
            raw.removePrefix("$").removeSuffix("$").trim().takeIf { it.isNotEmpty() }?.let { "\\textstyle $it" }
        GFMElementTypes.BLOCK_MATH ->
            raw.removePrefix("$$").removeSuffix("$$").trim().takeIf { it.isNotEmpty() }
        else -> null
    }
}

/** 公式占位的 id。加个前缀，免得和 Markdown 库自己的占位（图片）撞上。 */
private fun mathId(formula: String): String = "kitten-math:$formula"

/**
 * 把 [formulas] 量好、做成占位的画法，key 是 [appendMath] 用的 id。量不出来的（写错了、不认识）不放进来，
 * 那里就显示原文。
 *
 * 量过的结果库自己会缓存，流式时每帧调一次不会每帧重新排版。字体（KaTeX）第一次用到时在后台加载，
 * 加载完之前先用系统字体量，加载完自动重新量一遍。
 */
@Composable
internal fun rememberMathContent(
    formulas: Collection<String>,
    fontSize: TextUnit,
    color: Color,
): Map<String, InlineTextContent> {
    // 没有公式就不碰库：省得每条消息都去加载一遍数学字体。
    if (formulas.isEmpty()) return emptyMap()
    val config = rememberMathConfig(fontSize, color)
    val measurer = rememberLatexMeasurer(config)
    val dark = isSystemInDarkTheme()
    return formulas.mapNotNull { formula ->
        measurer.inlineContent(formula, config, dark)?.let { mathId(formula) to it }
    }.toMap()
}

/**
 * 单独一块的公式：居中；比屏幕宽就左右滑，不缩小（缩小了字就看不清了）。
 *
 * @param appear 出现时淡入。流式时这一块是公式写完了才整个冒出来的，淡入一下不那么突兀；历史消息不用。
 * @param fallbackStyle 公式量不出来时，照原文显示用的字体。
 */
@Composable
internal fun DisplayMath(
    formula: String,
    fontSize: TextUnit,
    color: Color,
    fallbackStyle: TextStyle,
    appear: Boolean,
    modifier: Modifier = Modifier,
) {
    val config = rememberMathConfig(fontSize, color)
    val measurer = rememberLatexMeasurer(config)
    val dark = isSystemInDarkTheme()
    val renderable = remember(measurer, formula, dark) { measurer.measure(formula, config, dark) != null }
    val shown = remember { Animatable(if (appear) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(AppearMillis)) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = shown.value },
    ) {
        val viewport = maxWidth
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Box(
                modifier = Modifier
                    .widthIn(min = viewport)
                    .padding(vertical = DisplayPaddingVertical),
                contentAlignment = Alignment.Center,
            ) {
                if (renderable) {
                    Latex(latex = formula, config = config, isDarkTheme = dark)
                } else {
                    Text(text = "\$\$$formula\$\$", style = fallbackStyle, color = color)
                }
            }
        }
    }
}

/**
 * 交给库的设置：字号、颜色（只有前景色，底是透明的，压在页面上）。读屏时公式念成"分数：1 比 2"这样的
 * 话（库自带，英文），比念一串 LaTeX 源码好懂。
 */
@Composable
private fun rememberMathConfig(fontSize: TextUnit, color: Color): LatexConfig =
    remember(fontSize, color) {
        LatexConfig(
            fontSize = fontSize,
            theme = LatexTheme.light(color = color),
            accessibilityEnabled = true,
        )
    }

/** 单独一块的公式上下多留的空：分式、求和号上下都比字高，挨着上下的段落太挤。 */
private val DisplayPaddingVertical = 4.dp

/** 流式时单独一块的公式冒出来，淡入多久（毫秒）。 */
private const val AppearMillis = 300
