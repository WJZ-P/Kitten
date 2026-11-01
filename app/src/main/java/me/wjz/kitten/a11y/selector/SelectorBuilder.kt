package me.wjz.kitten.a11y.selector

import me.wjz.kitten.a11y.UiNode
import me.wjz.kitten.a11y.UiSnapshot

/**
 * 给界面上的一个节点写选择器：模型点了第几号节点，这里把它换成一条下次抓屏还找得到它的选择器，
 * 存进操作记录（见 ActionTrace），复盘时再整理成自动流程。
 *
 * ## 怎么写
 *
 * 按"越稳越先"的顺序试一串写法，每条都拿当前这份快照验一遍：**只命中它、而且就是它**才算数
 * （看得见的节点里唯一；它自己看不见的话，在全部节点里唯一）。最先通过的那条最稳，后面通过的
 * 留作备选。
 *
 * 1. 资源 id：`[vid="search_btn"]`。开发者起的名字，改版也不常变，最稳。
 * 2. id 加文字：`[vid="tab"][text="我的"]`，底部几个 tab 共用一个 id 的时候。
 * 3. 文字、描述：`[text="我的订单"]`、`[desc="返回"]`。不带数字的排前面，带数字的（价格、时间、
 *    "购物车(3)"）多半下次就变了，排到结构写法后面去。
 * 4. 唯一的输入框、唯一的滚动容器：`[editable=true]`、`[scrollable=true]`。
 * 5. 靠里面的文字定位：可点的是外层容器、文字在子节点上（这在应用里是常态），写成
 *    `@[clickable=true] >2 [text="我的订单"]`——文字是"我的订单"的那个节点往上第二层。
 * 6. 靠外面的 id 定位：从最近一个有 id 的祖先往下数第几个子节点，
 *    `@* <2 * < [vid="tab_bar"]`。改版容易断，但总比没有强。
 *
 * 一条都写不出来（整棵树上没有一点能抓的特征）就返回空列表，这一步不进操作记录：宁可流程断在
 * 这里交给 AI，也不要记一条下次会点歪的。
 */
object SelectorBuilder {

    /**
     * 给 [snapshot] 里编号是 [nodeId] 的节点写选择器，最稳的排第一，最多 [max] 条。
     * 编号在完整树（[UiSnapshot.rawRoots]）里找：模型看到的是裁过的树，编号两边一致。
     */
    fun build(snapshot: UiSnapshot, nodeId: Int, max: Int = MAX_CANDIDATES): List<String> {
        val tree = NodeTree(snapshot.rawRoots)
        val target = tree.byId(nodeId) ?: return emptyList()
        val accepted = mutableListOf<String>()
        for (candidate in candidates(target, tree)) {
            if (candidate in accepted) continue
            if (locatesOnly(candidate, target, tree)) {
                accepted += candidate
                if (accepted.size >= max) break
            }
        }
        return accepted
    }

    /** [source] 在 [tree] 里是不是只命中 [target]。 */
    private fun locatesOnly(source: String, target: UiNode, tree: NodeTree): Boolean {
        val selector = Selector.parseOrNull(source) ?: return false
        val hits = selector.queryAll(tree)
        // 看得见的节点里唯一就行：同一个 id 的按钮在预加载的别的 tab 里也有一份，是常事。
        val relevant = if (target.visibleToUser) hits.filter { it.visibleToUser } else hits
        return relevant.size == 1 && relevant[0].id == target.id
    }

    /** 按"越稳越先"列出各种写法，还没验过唯一。 */
    private fun candidates(target: UiNode, tree: NodeTree): Sequence<String> = sequence {
        val vid = target.vid?.takeIf { it.isNotBlank() }?.let { "[vid=${quote(it)}]" }
        val text = usableText(target.text)?.let { "[text=${quote(it)}]" }
        val desc = usableText(target.contentDescription)?.let { "[desc=${quote(it)}]" }
        val stableText = text?.takeIf { !target.text.orEmpty().hasDigit() }
        val stableDesc = desc?.takeIf { !target.contentDescription.orEmpty().hasDigit() }

        // 1、2：资源 id，和 id 加文字。
        if (vid != null) {
            yield(vid)
            stableText?.let { yield(vid + it) }
            stableDesc?.let { yield(vid + it) }
        }
        // 3：文字、描述。
        stableText?.let { yield(it) }
        stableDesc?.let { yield(it) }
        // 4：唯一的输入框、滚动容器。
        if (target.editable) yield("[editable=true]")
        if (target.scrollable) yield("[scrollable=true]")

        // 5：靠里面的文字（或 id）定位。
        val self = vid ?: when {
            target.clickable -> "[clickable=true]"
            target.editable -> "[editable=true]"
            target.scrollable -> "[scrollable=true]"
            else -> "*"
        }
        descendantAnchors(target).forEach { (anchor, depth) ->
            yield("@$self >${offset(depth)} $anchor")
        }

        // 带数字的文字放到这里：比结构写法容易变，但比下面"数第几个"好读。
        if (vid != null) {
            text?.takeIf { it != stableText }?.let { yield(vid + it) }
        }
        text?.takeIf { it != stableText }?.let { yield(it) }
        desc?.takeIf { it != stableDesc }?.let { yield(it) }

        // 6：从最近的有 id 的祖先往下数。
        ancestorPath(target, tree)?.let { yield("@$self$it") }
    }

    /**
     * 子树里能当锚点的节点：有文字、描述或 id 的，往下最多 [MAX_ANCHOR_DEPTH] 层、最多 [MAX_ANCHORS] 个。
     * 返回锚点的写法和它比 [target] 深几层。不带数字的排前面。
     */
    private fun descendantAnchors(target: UiNode): List<Pair<String, Int>> {
        val stable = mutableListOf<Pair<String, Int>>()
        val unstable = mutableListOf<Pair<String, Int>>()
        fun visit(node: UiNode, depth: Int) {
            if (depth > MAX_ANCHOR_DEPTH || stable.size + unstable.size >= MAX_ANCHORS) return
            if (depth > 0) {
                usableText(node.text)?.let { value ->
                    (if (value.hasDigit()) unstable else stable) += "[text=${quote(value)}]" to depth
                }
                usableText(node.contentDescription)?.let { value ->
                    (if (value.hasDigit()) unstable else stable) += "[desc=${quote(value)}]" to depth
                }
                node.vid?.takeIf { it.isNotBlank() }?.let { stable += "[vid=${quote(it)}]" to depth }
            }
            node.children.forEach { visit(it, depth + 1) }
        }
        visit(target, 0)
        return stable + unstable
    }

    /**
     * 从最近的、有 id 的祖先往下，一层层数到 [target] 是第几个子节点：返回 ` <2 * < [vid="tab_bar"]`
     * 这样的后半截（前面接上 [target] 自己）。往上 [MAX_ANCESTOR_DEPTH] 层都没有带 id 的祖先就返回 null。
     */
    private fun ancestorPath(target: UiNode, tree: NodeTree): String? {
        val steps = mutableListOf<Int>()
        var node = target
        repeat(MAX_ANCESTOR_DEPTH) {
            val parent = tree.parentOf(node) ?: return null
            steps += tree.positionOf(node)
            val parentVid = parent.vid?.takeIf { it.isNotBlank() }
            if (parentVid != null) {
                // steps 是从 target 往上记的：第一个是 target 在它父节点里排第几。
                return buildString {
                    steps.forEachIndexed { level, position ->
                        append(" <").append(offset(position + 1))
                        append(if (level == steps.lastIndex) " [vid=${quote(parentVid)}]" else " *")
                    }
                }
            }
            node = parent
        }
        return null
    }

    /** 关系符后面的数字：1 不用写（`>` 就是父节点、`<` 就是第一个子节点）。 */
    private fun offset(n: Int): String = if (n == 1) "" else n.toString()

    /** 能拿来定位的文字：不空、一行、不太长。太长的多半是正文、聊天内容，下次就不是这句了。 */
    private fun usableText(value: String?): String? =
        value?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH && '\n' !in it && it == value }

    private fun String.hasDigit(): Boolean = any { it.isDigit() }

    /** 写成选择器里的字符串：加双引号，转义反斜杠、引号和换行。 */
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }

    /** 最多留几条：第一条是要用的，后面的给复盘时看个参考。 */
    private const val MAX_CANDIDATES = 3

    /**
     * 拿来定位的文字最长多少字。按钮、标签、tab 名一般十个字以内；再长多半是标题、正文，
     * 换个商品、换条消息就变了。
     */
    private const val MAX_TEXT_LENGTH = 30

    /** 靠里面的文字定位时往下找几层。可点的容器里，文字一般就在两三层以内。 */
    private const val MAX_ANCHOR_DEPTH = 3

    /** 子树里最多看几个锚点。列表容器的子树可能很大，没必要都试。 */
    private const val MAX_ANCHORS = 12

    /** 往上最多找几层带 id 的祖先。再远的话中间层数一变就断，没意义。 */
    private const val MAX_ANCESTOR_DEPTH = 6
}
