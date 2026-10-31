package me.wjz.kitten.ui.chat

import android.content.ClipData
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.CodeHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.ContentCopy
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 对话里的代码块：顶上一条细栏，左边写着是什么语言（模型写了的话），右边一个"复制"；下面是代码，
 * 等宽字、按语言上色。一行放不下就左右滑，不折行：折了行的代码缩进全乱，更难读。
 *
 * ## 上色
 *
 * 交给 Highlights（dev.snipme:highlights，Apache 2.0），它按语言认出关键字、字符串、数字、注释、注解。
 * 颜色用它的默认主题（[CodeTheme]，IntelliJ Darcula 的浅色版），不另配。认得的语言见 [CodeLanguages]：
 *
 * - 没写语言、或者写的是 text 的不上色：多半是命令输出或者纯文字，随便按哪种语言上色都会把普通单词
 *   染成关键字。
 * - 写了但引擎没有专门规则的（SQL、YAML……）用它的通用规则，字符串、数字、注释大体是对的。
 *
 * 引擎在后台线程算（[rememberCodeHighlights]），算好之前先显示上一次的结果（第一次就是没上色的字），
 * 不会一会儿有色一会儿没色地闪。写完的代码块算出来的结果缓存一份，列表滑走再滑回来不用重算。
 *
 * ## 流式
 *
 * 回复还在冒字时，代码块每帧都在变长，每帧整段重算太浪费：只给写完的行上色（到最后一个换行为止），
 * 正在写的那一行先不上色，写完了再一起算。代码块写完了（[finished]）就整段上色，包括没有换行结尾的
 * 最后一行。
 *
 * @param language 代码块开头写的语言名（```kotlin 里的 kotlin），没写是 null。
 * @param finished 代码块已经写完了，不会再变长。
 */
@Composable
internal fun ChatCodeBlock(
    code: String,
    language: String?,
    style: TextStyle,
    finished: Boolean,
) {
    val colors = KittenTheme.colors
    val tag = language?.trim()?.takeIf { it.isNotEmpty() }
    val known = tag?.let { CodeLanguages[it.lowercase()] }
    // 认得的按它的规则（纯文字是 null，不上色）；写了但不认得的用通用规则；没写的不上色。
    val syntax = when {
        tag == null -> null
        known != null -> known.syntax
        else -> SyntaxLanguage.DEFAULT
    }
    // 要上色的那一截：写完了是整段，还在写就到最后一个换行为止。
    val target = if (finished) code else code.substring(0, code.lastIndexOf('\n') + 1)
    val highlights = rememberCodeHighlights(code = target, language = syntax, cache = finished)
    val text = remember(code, highlights) { colorize(code, highlights) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CodeBlockMargin)
            .clip(CodeShape)
            .background(colors.wellTint),
    ) {
        CodeHeader(label = known?.label ?: tag, code = code)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.hairline),
        )
        Text(
            text = text,
            style = style,
            // 没上色的字（变量名、函数名）也用主题里的颜色，和上了色的字是一套。
            color = PlainCodeColor,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CodePaddingHorizontal, vertical = CodePaddingVertical),
        )
    }
}

/** 代码块顶上那条栏：左边语言名（没写就空着），右边"复制"。 */
@Composable
private fun CodeHeader(label: String?, code: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = CodePaddingHorizontal, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = KittenTheme.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        CopyButton(code = code)
    }
}

/**
 * "复制"：一个小图标加两个字。按下垫一层淡天依蓝，在它上面抬手才算数，和工具清单那一行一样；不做成
 * 玻璃，代码块里再放一块玻璃太重了。
 *
 * 复制完图标换成一个勾、字换成"已复制"，过 [CopiedHoldMillis] 换回来。系统（Android 13 起）复制时
 * 自己会弹提示，这里不另弹 Toast。
 */
@Composable
private fun CopyButton(code: String) {
    val colors = KittenTheme.colors
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val currentCode by rememberUpdatedState(code)
    val press = rememberGlassPressState()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedHoldMillis)
            copied = false
        }
    }

    // 只读 State 和 remember 住的东西：按下的处理挂一次就不换了，读到的永远是最新的代码。
    fun copy() {
        val snapshot = currentCode
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("代码", snapshot))) }
        copied = true
    }

    val tint = if (copied) colors.positive else colors.textSecondary
    Row(
        modifier = Modifier
            .semantics {
                role = Role.Button
                contentDescription = if (copied) "已复制" else "复制代码"
                onClick(label = "复制代码") {
                    copy()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { copy() } }
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) {
                    drawRoundRect(colors.sliderIdle, cornerRadius = CornerRadius(CopyRadius.toPx()), alpha = pressed)
                }
            }
            .heightIn(min = CopyHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (copied) Icons.Filled.Check else KittenIcons.ContentCopy,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(CopyIconSize),
        )
        Text(
            text = if (copied) "已复制" else "复制",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
            maxLines = 1,
        )
    }
}

/**
 * 算出 [code] 里哪些地方该上什么色，在后台线程算。算好之前返回上一次的结果（第一次是空的，字先不上色），
 * 不会在"有色""没色"之间闪。流式时上一次是短一截的代码，它的位置在新的代码里照样对得上（前面的字没变）。
 *
 * @param language 按什么规则上色，null 是不上色。
 * @param cache 把这次的结果存进缓存。只存写完的代码块：流式时一行一个结果，存进去只会把别的挤掉。
 */
@Composable
private fun rememberCodeHighlights(code: String, language: SyntaxLanguage?, cache: Boolean): List<CodeHighlight> {
    val key = if (language == null || code.isEmpty()) null else "${language.name}\u0000$code"
    var highlights by remember { mutableStateOf(key?.let { HighlightCache.get(it) }.orEmpty()) }
    LaunchedEffect(key) {
        if (key == null || language == null) {
            highlights = emptyList()
            return@LaunchedEffect
        }
        val cached = HighlightCache.get(key)
        if (cached != null) {
            highlights = cached
            return@LaunchedEffect
        }
        // 太长的就不上色了：引擎是一遍遍扫正则，几万字的代码要算好一会儿，对话里也很少见。
        if (code.length > MaxHighlightChars) return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            runCatching {
                Highlights.Builder()
                    .code(code)
                    .language(language)
                    .theme(CodeTheme)
                    .build()
                    .getHighlights()
            }.getOrDefault(emptyList())
        }
        if (cache) HighlightCache.put(key, result)
        highlights = result
    }
    return highlights
}

/** 把算好的颜色套到代码上。位置超出代码长度的（代码被换掉了，新结果还没算好）截掉，不越界。 */
private fun colorize(code: String, highlights: List<CodeHighlight>): AnnotatedString =
    buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            if (highlight !is ColorHighlight) return@forEach
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (end > start) addStyle(SpanStyle(color = opaque(highlight.rgb)), start, end)
        }
    }

/**
 * 上色用的主题：引擎的默认主题（IntelliJ Darcula 的浅色版：关键字橙、字符串绿、数字蓝、注释灰）。
 * Kitten 只有浅色主题，所以固定用浅色版。想换一套的话，SyntaxThemes 里还有 atom、monokai、notepad……
 */
private val CodeTheme = SyntaxThemes.default(darkMode = false)

/** 没上色的字的颜色：主题里的正文色。 */
private val PlainCodeColor = opaque(CodeTheme.code)

/** 引擎给的颜色是 0xRRGGBB，没有透明度那一字节，补成不透明。 */
private fun opaque(rgb: Int): Color = Color(rgb).copy(alpha = 1f)

/**
 * 一种语言：栏上显示的名字，和上色用的规则（null 是不上色）。
 */
private class CodeLanguage(val label: String, val syntax: SyntaxLanguage?)

/**
 * 代码块开头写的语言名（转成小写）→ [CodeLanguage]。模型的写法五花八门（kotlin、kt；js、javascript），
 * 常见的几种都列上；不在表里的，栏上照原样写它，上色用通用规则。
 */
private val CodeLanguages: Map<String, CodeLanguage> = buildMap {
    fun add(label: String, syntax: SyntaxLanguage?, vararg tags: String) {
        val language = CodeLanguage(label, syntax)
        tags.forEach { put(it, language) }
    }
    add("Kotlin", SyntaxLanguage.KOTLIN, "kotlin", "kt", "kts")
    add("Java", SyntaxLanguage.JAVA, "java")
    add("Swift", SyntaxLanguage.SWIFT, "swift")
    add("Python", SyntaxLanguage.PYTHON, "python", "py", "python3")
    add("JavaScript", SyntaxLanguage.JAVASCRIPT, "javascript", "js", "jsx", "mjs", "node")
    add("TypeScript", SyntaxLanguage.TYPESCRIPT, "typescript", "ts", "tsx")
    add("C", SyntaxLanguage.C, "c", "h")
    add("C++", SyntaxLanguage.CPP, "cpp", "c++", "cc", "cxx", "hpp")
    add("C#", SyntaxLanguage.CSHARP, "csharp", "c#", "cs")
    add("Go", SyntaxLanguage.GO, "go", "golang")
    add("Rust", SyntaxLanguage.RUST, "rust", "rs")
    add("Dart", SyntaxLanguage.DART, "dart")
    add("Ruby", SyntaxLanguage.RUBY, "ruby", "rb")
    add("PHP", SyntaxLanguage.PHP, "php")
    add("Perl", SyntaxLanguage.PERL, "perl", "pl")
    add("Shell", SyntaxLanguage.SHELL, "shell", "sh", "bash", "zsh", "console", "terminal")
    add("CoffeeScript", SyntaxLanguage.COFFEESCRIPT, "coffeescript", "coffee")
    // 引擎没有专门的规则：JSON 是 JS 的一部分，按 JS 认；别的用通用规则。
    add("JSON", SyntaxLanguage.JAVASCRIPT, "json", "jsonc")
    add("SQL", SyntaxLanguage.DEFAULT, "sql")
    add("YAML", SyntaxLanguage.DEFAULT, "yaml", "yml")
    add("XML", SyntaxLanguage.DEFAULT, "xml")
    add("HTML", SyntaxLanguage.DEFAULT, "html")
    add("CSS", SyntaxLanguage.DEFAULT, "css")
    add("Markdown", SyntaxLanguage.DEFAULT, "markdown", "md")
    // 纯文字：只显示名字，不上色。
    add("文本", null, "text", "txt", "plaintext", "plain")
}

/**
 * 算过的上色结果，按"语言 + 代码"存。一屏也就几个代码块，存最近这么多个，滑来滑去够用了。
 * LruCache 自己带锁，后台线程存、主线程取都没问题。
 */
private val HighlightCache = LruCache<String, List<CodeHighlight>>(HighlightCacheSize)

private const val HighlightCacheSize = 32

/** 超过这么多字的代码不上色。 */
private const val MaxHighlightChars = 20_000

/** 代码块的圆角：和卡片里的小控件同一档。 */
private val CodeShape = RoundedCornerShape(GlassRadius.Small)

/**
 * 代码块上下各让出多少。顶层的块之间本来就隔着 BlockGap；列表里的代码块没有，靠它和上下的字分开。
 */
private val CodeBlockMargin = 4.dp

/** 代码到代码块边的距离。栏上的语言名左边也按它对齐。 */
private val CodePaddingHorizontal = 12.dp
private val CodePaddingVertical = 10.dp

/** "复制"那一块多高（也是栏的高度）、按下时垫的底色的圆角、图标多大。 */
private val CopyHeight = 32.dp
private val CopyRadius = 6.dp
private val CopyIconSize = 14.dp

/** 复制完"已复制"留多久（毫秒）再换回"复制"。 */
private const val CopiedHoldMillis = 1500L
