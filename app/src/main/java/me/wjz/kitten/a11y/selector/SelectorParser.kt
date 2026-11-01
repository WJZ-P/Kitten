package me.wjz.kitten.a11y.selector

/**
 * 把一条选择器的文字解析成 [Selector]。手写的递归下降，语法见 [Selector] 的类注释：
 *
 * ```
 * 选择器   := 节点 (关系符 节点)*
 * 节点     := '@'? 类名? ('[' 条件 ']')*          类名和条件至少有一个
 * 条件     := 且 ('||' 且)*
 * 且       := 单项 ('&&' 单项)*
 * 单项     := '(' 条件 ')' | 属性 比较符 值
 * 关系符   := ('<<' | '<' | '>' | '+' | '-') 偏移?
 * 偏移     := 正整数 | 'n' | '(' 正整数 (',' 正整数)* ')'
 * ```
 *
 * 出错就抛 [SelectorSyntaxException]，带位置和一句中文说明：这条选择器多半是模型或者用户写的，
 * 要让写的人看得懂错在哪。
 */
internal class SelectorParser(private val source: String) {

    private var position = 0

    private val current: Char?
        get() = source.getOrNull(position)

    fun parse(): Selector {
        skipWhitespace()
        if (current == null) fail("选择器是空的")
        val units = mutableListOf<UnitSelector>()
        val relations = mutableListOf<Relation>()
        var target = -1
        while (true) {
            val markedAsTarget = current == '@'
            if (markedAsTarget) {
                if (target >= 0) fail("一条选择器里只能有一个 @")
                target = units.size
                position++
            }
            units += readUnit()
            skipWhitespace()
            if (current == null) break
            relations += readRelation()
            skipWhitespace()
            if (current == null) fail("关系符后面少了一个节点")
        }
        return Selector(
            source = source.trim(),
            units = units,
            relations = relations,
            targetIndex = if (target >= 0) target else units.lastIndex,
        )
    }

    private fun readUnit(): UnitSelector {
        val start = position
        val name = when {
            current == '*' -> {
                position++
                "*"
            }
            current?.isNameStart() == true -> readWhile { it.isNameChar() }
            else -> ""
        }
        val filters = mutableListOf<Expression>()
        while (current == '[') filters += readFilter()
        if (name.isEmpty() && filters.isEmpty()) {
            position = start
            fail("这里应该是一个节点，比如 [vid=\"xxx\"]、TextView 或者 *")
        }
        return UnitSelector(name, filters)
    }

    private fun readFilter(): Expression {
        expect('[')
        skipWhitespace()
        val expression = readOr()
        skipWhitespace()
        expect(']')
        return expression
    }

    private fun readOr(): Expression {
        var left = readAnd()
        while (true) {
            skipWhitespace()
            if (!source.startsWith("||", position)) return left
            position += 2
            skipWhitespace()
            left = Expression.Or(left, readAnd())
        }
    }

    private fun readAnd(): Expression {
        var left = readPrimary()
        while (true) {
            skipWhitespace()
            if (!source.startsWith("&&", position)) return left
            position += 2
            skipWhitespace()
            left = Expression.And(left, readPrimary())
        }
    }

    private fun readPrimary(): Expression {
        if (current == '(') {
            position++
            skipWhitespace()
            val inner = readOr()
            skipWhitespace()
            expect(')')
            return inner
        }
        return readComparison()
    }

    private fun readComparison(): Expression {
        val nameStart = position
        val name = readWhile { it.isLetterOrDigit() || it == '_' || it == '.' }
        if (name.isEmpty()) fail("这里应该是属性名，比如 text、vid、clickable")
        val property = Property.of(name) ?: run {
            position = nameStart
            fail("不认识的属性 $name，能用的有：${Property.names.joinToString("、")}，文字属性后面可以接 .length")
        }
        skipWhitespace()
        val operatorStart = position
        val operator = CompareOperator.parseOrder().firstOrNull { source.startsWith(it.key, position) }
            ?: fail("属性 $name 后面应该是比较符，比如 =、!=、^=、*=、\$=、~=")
        position += operator.key.length
        skipWhitespace()
        val valueStart = position
        val value = readLiteral()
        if (!operator.accepts(property.type, value)) {
            position = operatorStart
            fail("$name 是${property.type.description()}，不能用 ${operator.key} 和 ${value.description()} 比")
        }
        if (operator is CompareOperator.RegexMatch) {
            try {
                operator.compile((value as Literal.Text).value)
            } catch (e: IllegalArgumentException) {
                position = valueStart
                fail("正则写错了：${e.message?.lineSequence()?.firstOrNull().orEmpty()}")
            }
        }
        return Expression.Compare(property, operator, value)
    }

    private fun readLiteral(): Literal {
        val c = current ?: fail("这里应该是一个值")
        return when {
            c == '"' || c == '\'' -> Literal.Text(readString(c))
            c == '-' || c.isDigit() -> {
                val start = position
                if (c == '-') position++
                val digits = readWhile { it.isDigit() }
                if (digits.isEmpty()) fail("这里应该是数字")
                source.substring(start, position).toIntOrNull()?.let { Literal.Number(it) } ?: run {
                    position = start
                    fail("数字太大了")
                }
            }
            else -> {
                val start = position
                when (readWhile { it.isLetter() }) {
                    "true" -> Literal.Bool(true)
                    "false" -> Literal.Bool(false)
                    "null" -> Literal.Null
                    else -> {
                        position = start
                        fail("这里应该是一个值：带引号的文字、数字、true、false 或 null")
                    }
                }
            }
        }
    }

    /** 读一个带引号的字符串，认 `\\` `\"` `\'` `\n` `\r` `\t` `\b` `\f` `\uXXXX` 这几种转义，和 GKD 一样。 */
    private fun readString(quote: Char): String {
        val start = position
        position++
        val builder = StringBuilder()
        while (true) {
            val c = current ?: run {
                position = start
                fail("字符串少了结尾的 $quote")
            }
            position++
            when (c) {
                quote -> return builder.toString()
                '\\' -> {
                    val escaped = current ?: fail("转义符 \\ 后面少了字符")
                    position++
                    builder.append(
                        when (escaped) {
                            '\\', '"', '\'' -> escaped
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'u' -> {
                                val hex = source.substring(position, (position + 4).coerceAtMost(source.length))
                                val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                                    ?: fail("\\u 后面要跟四位十六进制数")
                                position += 4
                                Char(code)
                            }
                            else -> {
                                position--
                                fail("不认识的转义 \\$escaped")
                            }
                        },
                    )
                }
                else -> builder.append(c)
            }
        }
    }

    private fun readRelation(): Relation {
        val kind = when {
            source.startsWith("<<", position) -> Relation.Kind.Descendant
            current == '<' -> Relation.Kind.Child
            current == '>' -> Relation.Kind.Ancestor
            current == '+' -> Relation.Kind.BeforeSibling
            current == '-' -> Relation.Kind.AfterSibling
            else -> fail("两个节点之间要有关系符：>、<、<<、+、-")
        }
        position += kind.key.length
        return Relation(kind, readOffsets())
    }

    /** 关系符后面紧跟着的偏移。没写就是只认偏移 0。 */
    private fun readOffsets(): Offsets {
        val c = current
        return when {
            c != null && c.isDigit() -> Offsets.Exactly(readPositiveInt() - 1)
            // `n` 后面紧跟着字母、数字的话，那是下一个节点的类名（比如 `>NumberPicker`），不是偏移。
            c == 'n' && source.getOrNull(position + 1)?.isNameChar() != true -> {
                position++
                Offsets.All
            }
            c == '(' -> {
                position++
                val numbers = mutableListOf<Int>()
                while (true) {
                    skipWhitespace()
                    val numberStart = position
                    val number = readPositiveInt()
                    if (numbers.isNotEmpty() && number <= numbers.last()) {
                        position = numberStart
                        fail("括号里的数字要从小到大写")
                    }
                    numbers += number
                    skipWhitespace()
                    if (current == ',') {
                        position++
                        continue
                    }
                    if (current == 'n') fail("不支持 (2n+1) 这种写法，换成 n 或者把数字列出来")
                    expect(')')
                    break
                }
                Offsets.OneOf(numbers.map { it - 1 }.toSet())
            }
            else -> Offsets.Exactly(0)
        }
    }

    private fun readPositiveInt(): Int {
        val start = position
        val digits = readWhile { it.isDigit() }
        val value = digits.toIntOrNull()
        if (value == null || value < 1) {
            position = start
            fail("这里应该是一个正整数")
        }
        return value
    }

    private inline fun readWhile(predicate: (Char) -> Boolean): String {
        val start = position
        while (current?.let(predicate) == true) position++
        return source.substring(start, position)
    }

    private fun skipWhitespace() {
        while (current?.isWhitespace() == true) position++
    }

    private fun expect(c: Char) {
        if (current != c) fail(if (current == null) "少了 $c" else "这里应该是 $c")
        position++
    }

    private fun fail(message: String): Nothing = throw SelectorSyntaxException(message, position)
}

/** 类名可以以什么开头：字母、下划线。 */
private fun Char.isNameStart(): Boolean = isLetter() || this == '_'

/** 类名里能有什么：字母、数字、下划线、点、`$`（内部类）。 */
private fun Char.isNameChar(): Boolean = isLetterOrDigit() || this == '_' || this == '.' || this == '$'

private fun PropertyType.description(): String = when (this) {
    PropertyType.STRING -> "文字"
    PropertyType.INT -> "数字"
    PropertyType.BOOLEAN -> "true/false"
}

private fun Literal.description(): String = when (this) {
    is Literal.Text -> "文字"
    is Literal.Number -> "数字"
    is Literal.Bool -> "true/false"
    Literal.Null -> "null"
}
