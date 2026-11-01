package me.wjz.kitten.a11y.selector

import me.wjz.kitten.a11y.UiNode
import me.wjz.kitten.a11y.UiSnapshot

/**
 * 选择器：按节点自己的属性、和它跟周围节点的关系，在界面树里找到某个节点。
 *
 * 自动流程（技能里存的那种，见 SkillFlow）靠它记住"点的是哪个"：节点编号每抓一次屏都会变，
 * 选择器不会——下次打开同一个页面，照着它还能找到同一个按钮。
 *
 * ## 语法：照着 GKD 来
 *
 * 写法和 [GKD 的选择器](https://gkd.li/guide/selector) 一样，是它的一个子集，GKD 的规则大多能直接拿来用：
 *
 * ```
 * [vid="search_btn"]                          vid 是 search_btn 的节点
 * [text="我的订单"][clickable=true]            同时满足两个条件
 * [text^="广告" || desc*="关闭"]               或；&& 是且，可以加括号
 * TextView[text!=null]                        类名是 TextView（或以 .TextView 结尾）
 * @[clickable=true] > [text="我的订单"]         文字是"我的订单"的节点，它的父节点；@ 标出要的是哪个
 * [vid="item"] <2 [vid="list"]                列表 list 的第 2 个子节点
 * ```
 *
 * 关系符写在两个节点之间，说的是**左边相对右边**是什么：
 *
 * | 写法 | 意思 |
 * |---|---|
 * | `A > B`   | A 是 B 的父节点；`>2` 祖父，`>n` 任意一层祖先 |
 * | `A < B`   | A 是 B 的第 1 个子节点；`<2` 第 2 个，`<n` 任意一个 |
 * | `A << B`  | A 是 B 的后代，常写成 `<<n`（任意后代） |
 * | `A + B`   | A 是 B 紧挨着的前一个兄弟；`+2` 往前数第 2 个，`+n` 前面任意一个 |
 * | `A - B`   | A 是 B 紧挨着的后一个兄弟；`-n` 后面任意一个 |
 *
 * 关系符后面的数字也可以写成 `(1,3,5)`，表示这几个里任意一个。
 *
 * 匹配从最右边那个节点开始：先找满足最右边条件的节点，再沿着关系符往左一路找过去，都找得到才算
 * 命中。命中的结果默认是最右边那个，前面加了 `@` 的就是加了 `@` 的那个——和 GKD 一样。
 *
 * ### 属性
 *
 * - 文字：`id`（完整的资源 id）、`vid`（去掉包名前缀的短 id）、`name`（类名）、`text`、`desc`、`hint`，
 *   后面可以接 `.length` 取长度（`text.length>10`）。
 * - 开关：`clickable`、`longClickable`、`scrollable`、`editable`、`checkable`、`checked`、`selected`、
 *   `focused`、`enabled`、`visibleToUser`。
 * - 数字：`left`、`top`、`right`、`bottom`、`width`、`height`、`index`（在父节点里排第几，从 0 数）、
 *   `depth`、`childCount`。
 *
 * 比较：`=`、`!=`；文字的 `^=`（开头是）、`*=`（包含）、`$=`（结尾是）、`~=`（正则，整段匹配）和它们
 * 前面加 `!` 的否定；数字的 `<`、`<=`、`>`、`>=`。值是带引号的字符串、整数、`true`、`false`、`null`。
 *
 * ### 和 GKD 不一样的地方
 *
 * - 没有：`focusable`（无障碍里只有"有没有焦点"）、`->` 关系、`(2n+1)` 这种多项式、属性上的方法调用
 *   （`text.contains(...)` 之类）、`parent` 这类节点属性。写了会报语法错误，不会悄悄当成别的。
 * - 多了：`hint`、`scrollable`、`selected`、`focused`、`enabled`，都是 Kitten 抓屏时本来就有的字段。
 *
 * ## 在哪棵树上找
 *
 * [queryAll] 收的是裁剪之前的完整节点树（[UiSnapshot.rawRoots]），不是给模型看的那份裁过的：裁过的
 * 树砍掉了包装层、父子关系对不上，下次抓屏也不一定裁成一样。GKD 用的也是原始的树。
 *
 * 不可见的节点（滚到外面的、其它 tab 预加载的）也在树里，选择器照样会找到它们。要不要只认看得见的，
 * 由调用方决定（自动流程只认看得见的，见 FlowRunner）。
 */
class Selector internal constructor(
    /** 原样的写法，存进技能里的就是它。 */
    val source: String,
    internal val units: List<UnitSelector>,
    /** 第 i 个是 units[i] 和 units[i + 1] 之间的关系。 */
    internal val relations: List<Relation>,
    /** 结果取哪个节点：带 `@` 的那个，没有就是最右边那个。 */
    internal val targetIndex: Int,
) {
    override fun toString(): String = source

    /** 在 [snapshot] 的完整节点树里找所有命中的节点，见 [queryAll]。 */
    fun queryAll(snapshot: UiSnapshot): List<UiNode> = queryAll(NodeTree(snapshot.rawRoots))

    /** 在这几棵树里找所有命中的节点，按先序遍历的先后排，同一个节点只出现一次。 */
    fun queryAll(roots: List<UiNode>): List<UiNode> = queryAll(NodeTree(roots))

    internal fun queryAll(tree: NodeTree): List<UiNode> {
        val results = LinkedHashMap<Int, UiNode>()
        val last = units.last()
        val path = arrayOfNulls<UiNode>(units.size)
        tree.preorder.forEach { node ->
            // 先拿最右边的条件筛一遍，绝大多数节点在这里就被刷掉了，后面的关系不用走。
            if (!last.matches(node)) return@forEach
            if (matchFrom(node, units.lastIndex, path, tree)) {
                val target = path[targetIndex] ?: return@forEach
                results.putIfAbsent(target.id, target)
            }
        }
        return results.values.toList()
    }

    /**
     * [node] 满足第 [index] 个节点的条件，再沿着关系往左找下去，一路都找得到就是 true，[path] 里记着
     * 这一路上每一环命中的节点。往左走不通时退回来换下一个候选（回溯），和 GKD 一样。
     */
    private fun matchFrom(node: UiNode, index: Int, path: Array<UiNode?>, tree: NodeTree): Boolean {
        if (!units[index].matches(node)) return false
        path[index] = node
        if (index == 0) return true
        for (candidate in relations[index - 1].traverse(node, tree)) {
            if (matchFrom(candidate, index - 1, path, tree)) return true
        }
        return false
    }

    companion object {
        /**
         * 解析一条选择器。写错了抛 [SelectorSyntaxException]，带着出错的位置和一句说明。
         *
         * 解析时就把类型检查做了（文字属性不能和数字比、正则写错了……），写错的选择器存不进技能，
         * 也不会等到跑流程的时候才发现。
         */
        fun parse(source: String): Selector = SelectorParser(source).parse()

        /** 解析不了返回 null。只在调用方不关心为什么错时用。 */
        fun parseOrNull(source: String): Selector? =
            try {
                parse(source)
            } catch (e: SelectorSyntaxException) {
                null
            }
    }
}

/** 选择器写错了：[position] 是出错的位置（从 0 数）。 */
class SelectorSyntaxException(message: String, val position: Int) :
    IllegalArgumentException("$message（第 ${position + 1} 个字符）")

/** 选择器里的一个节点：类名（可以不写）、若干个 `[...]` 条件。 */
internal class UnitSelector(
    /** 空字符串或 `*` 表示不限类名。 */
    private val name: String,
    private val filters: List<Expression>,
) {
    private val anyName = name.isEmpty() || name == "*"

    fun matches(node: UiNode): Boolean = matchesName(node) && filters.all { it.evaluate(node) }

    /** 类名完全一样，或者以 `.名字` 结尾：写 `TextView` 能匹配 `android.widget.TextView`，和 GKD 一样。 */
    private fun matchesName(node: UiNode): Boolean {
        if (anyName) return true
        val className = node.className ?: return false
        return className == name ||
            (className.length > name.length && className.endsWith(name) && className[className.length - name.length - 1] == '.')
    }
}

/** `[...]` 里的条件。 */
internal sealed interface Expression {
    fun evaluate(node: UiNode): Boolean

    class And(private val left: Expression, private val right: Expression) : Expression {
        override fun evaluate(node: UiNode) = left.evaluate(node) && right.evaluate(node)
    }

    class Or(private val left: Expression, private val right: Expression) : Expression {
        override fun evaluate(node: UiNode) = left.evaluate(node) || right.evaluate(node)
    }

    class Compare(
        private val property: Property,
        private val operator: CompareOperator,
        private val value: Literal,
    ) : Expression {
        override fun evaluate(node: UiNode): Boolean = operator.compare(property.read(node), value)
    }
}

/** 属性的值是什么类型。解析时据此检查比较符和值写得对不对。 */
internal enum class PropertyType { STRING, INT, BOOLEAN }

/** 节点上的一个属性。 */
internal class Property(val name: String, val type: PropertyType, val read: (UiNode) -> Any?) {
    companion object {
        private val strings: Map<String, (UiNode) -> String?> = mapOf(
            "id" to { it.viewId },
            "vid" to { it.vid },
            "name" to { it.className },
            "text" to { it.text },
            "desc" to { it.contentDescription },
            "hint" to { it.hintText },
        )

        private val booleans: Map<String, (UiNode) -> Boolean> = mapOf(
            "clickable" to { it.clickable },
            "longClickable" to { it.longClickable },
            "scrollable" to { it.scrollable },
            "editable" to { it.editable },
            "checkable" to { it.checkable },
            "checked" to { it.checked },
            "selected" to { it.selected },
            "focused" to { it.focused },
            "enabled" to { it.enabled },
            "visibleToUser" to { it.visibleToUser },
        )

        private val ints: Map<String, (UiNode) -> Int> = mapOf(
            "left" to { it.bounds.left },
            "top" to { it.bounds.top },
            "right" to { it.bounds.right },
            "bottom" to { it.bounds.bottom },
            "width" to { it.bounds.width() },
            "height" to { it.bounds.height() },
            "index" to { it.index },
            "depth" to { it.depth },
            "childCount" to { it.childCount },
        )

        /** 按名字找属性，`text.length` 这种取长度的也认。不认识的返回 null。 */
        fun of(name: String): Property? {
            strings[name]?.let { return Property(name, PropertyType.STRING, it) }
            booleans[name]?.let { return Property(name, PropertyType.BOOLEAN, it) }
            ints[name]?.let { return Property(name, PropertyType.INT, it) }
            if (name.endsWith(LENGTH_SUFFIX)) {
                val base = strings[name.removeSuffix(LENGTH_SUFFIX)] ?: return null
                return Property(name, PropertyType.INT) { node -> base(node)?.length }
            }
            return null
        }

        /** 认得的全部属性名，报错时列给写的人看。 */
        val names: List<String> get() = (strings.keys + booleans.keys + ints.keys).toList()

        private const val LENGTH_SUFFIX = ".length"
    }
}

/** 条件右边写的值。 */
internal sealed interface Literal {
    data class Text(val value: String) : Literal
    data class Number(val value: Int) : Literal
    data class Bool(val value: Boolean) : Literal
    data object Null : Literal

    /** 拿去和属性值比的那个值。 */
    val raw: Any?
        get() = when (this) {
            is Text -> value
            is Number -> value
            is Bool -> value
            Null -> null
        }
}

/**
 * 比较符。语义照 GKD：文字类的比较（开头、包含、结尾、正则）碰到属性是 null 一律不成立，否定的
 * 也不成立——`[text!^="广告"]` 不会命中没有文字的节点。
 */
internal sealed class CompareOperator(val key: String) {

    abstract fun compare(left: Any?, right: Literal): Boolean

    /** [type] 类型的属性能不能拿这个比较符和 [value] 比。 */
    abstract fun accepts(type: PropertyType, value: Literal): Boolean

    data object Equal : CompareOperator("=") {
        override fun compare(left: Any?, right: Literal) = left == right.raw
        override fun accepts(type: PropertyType, value: Literal) = value.fits(type)
    }

    data object NotEqual : CompareOperator("!=") {
        override fun compare(left: Any?, right: Literal) = left != right.raw
        override fun accepts(type: PropertyType, value: Literal) = value.fits(type)
    }

    /** 文字的开头、包含、结尾，和它们的否定。 */
    class TextMatch(key: String, private val negate: Boolean, private val test: (String, String) -> Boolean) :
        CompareOperator(key) {
        override fun compare(left: Any?, right: Literal): Boolean {
            if (left !is String || right !is Literal.Text) return false
            return test(left, right.value) != negate
        }

        override fun accepts(type: PropertyType, value: Literal) =
            type == PropertyType.STRING && value is Literal.Text
    }

    /** 正则：整段匹配，和 GKD 一样（要"包含"就自己在两头加 `.*`）。正则在解析时编译好。 */
    class RegexMatch(key: String, private val negate: Boolean) : CompareOperator(key) {
        private var regex: Regex? = null

        fun compile(pattern: String) {
            regex = Regex(pattern)
        }

        override fun compare(left: Any?, right: Literal): Boolean {
            if (left !is String) return false
            val pattern = regex ?: return false
            return pattern.matches(left) != negate
        }

        override fun accepts(type: PropertyType, value: Literal) =
            type == PropertyType.STRING && value is Literal.Text
    }

    class NumberMatch(key: String, private val test: (Int, Int) -> Boolean) : CompareOperator(key) {
        override fun compare(left: Any?, right: Literal): Boolean =
            left is Int && right is Literal.Number && test(left, right.value)

        override fun accepts(type: PropertyType, value: Literal) =
            type == PropertyType.INT && value is Literal.Number
    }

    companion object {
        /**
         * 全部比较符的写法，长的排前面：解析时按这个顺序试，`!^=` 不会被当成 `!=` 加一个 `^`。
         * 正则那两个每次新建：编译好的正则存在它身上，不能共用。
         */
        fun parseOrder(): List<CompareOperator> = listOf(
            TextMatch("!^=", negate = true) { a, b -> a.startsWith(b) },
            TextMatch("!*=", negate = true) { a, b -> a.contains(b) },
            TextMatch("!$=", negate = true) { a, b -> a.endsWith(b) },
            RegexMatch("!~=", negate = true),
            NotEqual,
            TextMatch("^=", negate = false) { a, b -> a.startsWith(b) },
            TextMatch("*=", negate = false) { a, b -> a.contains(b) },
            TextMatch("$=", negate = false) { a, b -> a.endsWith(b) },
            RegexMatch("~=", negate = false),
            NumberMatch("<=") { a, b -> a <= b },
            NumberMatch(">=") { a, b -> a >= b },
            Equal,
            NumberMatch("<") { a, b -> a < b },
            NumberMatch(">") { a, b -> a > b },
        )
    }
}

/** 这个值能不能和 [type] 类型的属性比相等。null 和谁都能比（"有没有这个属性"）。 */
private fun Literal.fits(type: PropertyType): Boolean = when (this) {
    Literal.Null -> true
    is Literal.Text -> type == PropertyType.STRING
    is Literal.Number -> type == PropertyType.INT
    is Literal.Bool -> type == PropertyType.BOOLEAN
}

/** 两个节点之间的关系，见 [Selector] 类注释里的表。 */
internal class Relation(private val kind: Kind, private val offsets: Offsets) {

    enum class Kind(val key: String) {
        /** `A > B`：A 是 B 的祖先。偏移 0 是父节点。 */
        Ancestor(">"),

        /** `A < B`：A 是 B 的子节点。偏移就是在 B 的子节点里排第几（从 0 数）。 */
        Child("<"),

        /** `A << B`：A 是 B 的后代。偏移是在 B 的子树里按先序数第几个。 */
        Descendant("<<"),

        /** `A + B`：A 在 B 前面。偏移 0 是紧挨着的前一个。 */
        BeforeSibling("+"),

        /** `A - B`：A 在 B 后面。偏移 0 是紧挨着的后一个。 */
        AfterSibling("-"),
    }

    /** 从 [node]（右边那个）出发，按关系找出左边可能是谁，近的先出来。 */
    fun traverse(node: UiNode, tree: NodeTree): Sequence<UiNode> = when (kind) {
        Kind.Ancestor -> sequence {
            var offset = 0
            var current = tree.parentOf(node)
            while (current != null && offsets.mayReach(offset)) {
                if (offsets.contains(offset)) yield(current)
                current = tree.parentOf(current)
                offset++
            }
        }
        Kind.Child -> sequence {
            node.children.forEachIndexed { offset, child ->
                if (!offsets.mayReach(offset)) return@sequence
                if (offsets.contains(offset)) yield(child)
            }
        }
        Kind.Descendant -> sequence {
            var offset = 0
            for (descendant in descendantsOf(node)) {
                if (!offsets.mayReach(offset)) return@sequence
                if (offsets.contains(offset)) yield(descendant)
                offset++
            }
        }
        Kind.BeforeSibling -> sequence {
            val siblings = tree.siblingsOf(node)
            val position = siblings.indexOfFirst { it.id == node.id }
            for (offset in 0 until position.coerceAtLeast(0)) {
                if (!offsets.mayReach(offset)) return@sequence
                if (offsets.contains(offset)) yield(siblings[position - offset - 1])
            }
        }
        Kind.AfterSibling -> sequence {
            val siblings = tree.siblingsOf(node)
            val position = siblings.indexOfFirst { it.id == node.id }
            if (position < 0) return@sequence
            for (index in position + 1 until siblings.size) {
                val offset = index - position - 1
                if (!offsets.mayReach(offset)) return@sequence
                if (offsets.contains(offset)) yield(siblings[index])
            }
        }
    }

    /** 先序遍历 [node] 的子树，不含它自己。 */
    private fun descendantsOf(node: UiNode): Sequence<UiNode> = sequence {
        val stack = ArrayDeque<UiNode>()
        node.children.asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val top = stack.removeLast()
            yield(top)
            top.children.asReversed().forEach { stack.addLast(it) }
        }
    }
}

/** 关系符后面的数字：允许哪几个偏移（从 0 数；写法上 `>2` 是偏移 1）。 */
internal sealed interface Offsets {
    fun contains(offset: Int): Boolean

    /** 再往后数还可能有允许的偏移吗。走到头了就别再往下找。 */
    fun mayReach(offset: Int): Boolean

    /** 什么都不写：只认偏移 0（`>` 父节点、`<` 第一个子节点、`+` 紧挨着的前一个）。也是 `>1` 这种。 */
    data class Exactly(val offset: Int) : Offsets {
        override fun contains(offset: Int) = offset == this.offset
        override fun mayReach(offset: Int) = offset <= this.offset
    }

    /** `n`：任意一个。 */
    data object All : Offsets {
        override fun contains(offset: Int) = true
        override fun mayReach(offset: Int) = true
    }

    /** `(1,3,5)`：这几个里任意一个。 */
    class OneOf(private val allowed: Set<Int>) : Offsets {
        private val max = allowed.max()
        override fun contains(offset: Int) = offset in allowed
        override fun mayReach(offset: Int) = offset <= max
    }
}

/**
 * 一棵（几棵）节点树，补上 [UiNode] 自己没有的"父节点是谁"。节点编号在一份快照里是唯一的，按它记。
 * 多个窗口的树各管各的，窗口的根节点没有父节点、也没有兄弟。
 */
internal class NodeTree(private val roots: List<UiNode>) {

    private val parents = HashMap<Int, UiNode>()
    private val nodesById = HashMap<Int, UiNode>()

    /** 全部节点，按窗口从上到下、每棵树先序。 */
    val preorder: List<UiNode> = buildList {
        val stack = ArrayDeque<UiNode>()
        roots.asReversed().forEach { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            add(node)
            nodesById[node.id] = node
            node.children.asReversed().forEach { child ->
                parents[child.id] = node
                stack.addLast(child)
            }
        }
    }

    fun parentOf(node: UiNode): UiNode? = parents[node.id]

    fun byId(id: Int): UiNode? = nodesById[id]

    /** 和 [node] 同一个父节点的全部节点（含它自己），按原来的顺序。根节点没有兄弟。 */
    fun siblingsOf(node: UiNode): List<UiNode> = parentOf(node)?.children ?: emptyList()

    /** [node] 在兄弟里排第几（从 0 数），根节点是 -1。 */
    fun positionOf(node: UiNode): Int = siblingsOf(node).indexOfFirst { it.id == node.id }
}
