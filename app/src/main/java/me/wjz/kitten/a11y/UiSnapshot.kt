package me.wjz.kitten.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 一个窗口及其节点树。
 *
 * 一块屏上同时存在多个窗口是常态：应用主窗口、输入法、系统弹窗、悬浮球、
 * 状态栏。只读「当前应用」会漏掉正好挡在用户面前的那个对话框，所以这里按窗口
 * 分别保留，由上层决定要哪些。
 */
data class UiWindow(
    val windowId: Int,
    val displayId: Int,

    /** `AccessibilityWindowInfo.TYPE_*`，用 [typeName] 看可读名字。 */
    val type: Int,

    /** 窗口标题，很多应用没有。 */
    val title: String?,

    /** 从根节点取的包名，用来判断这个窗口属于哪个应用。 */
    val packageName: String?,

    /** 是否是活动窗口（用户正在交互的那个）。 */
    val active: Boolean,

    /** 是否持有输入焦点。 */
    val focused: Boolean,

    /** 层级，越大越靠上。窗口按它降序排，所以列表第一个就是最上层的。 */
    val layer: Int,

    val bounds: Rect,

    /** 节点树的根。拿不到（窗口已销毁、或没有内容）时为 null。 */
    val root: UiNode?,
) {
    val typeName: String
        get() = when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "application"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "input_method"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "accessibility_overlay"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "split_divider"
            AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "magnification"
            else -> "unknown($type)"
        }

    val nodeCount: Int
        get() = root?.subtreeSize ?: 0
}

/**
 * 某一瞬间、某块屏幕上的全部界面内容。
 *
 * ## 它为什么是"某一瞬间"
 *
 * 遍历节点树要跨进程读很多次，期间用户或应用本身都可能让界面继续变化。
 * 也就是说这份快照不是原子的——深处的节点可能比根节点晚几十毫秒。
 * 对 Agent 来说这是可以接受的：操作前重新抓一次即可，不要缓存着反复用。
 */
data class UiSnapshot(
    val displayId: Int,

    /** 抓取开始时的 `System.currentTimeMillis()`。 */
    val capturedAtMillis: Long,

    /** 抓完整棵树花了多久。节点多的页面会明显偏慢，用它判断要不要收紧压缩。 */
    val elapsedMillis: Long,

    /** 按 [UiWindow.layer] 降序，最上层的窗口在最前面。 */
    val windows: List<UiWindow>,

    /** 本次抓到的节点总数。 */
    val nodeCount: Int,

    /** 是否因为撞上深度或数量上限而提前收尾。为 true 说明这份快照不完整。 */
    val truncated: Boolean,
) {
    /** 用户正在交互的那个应用窗口，通常就是上层想要的"当前页面"。 */
    val activeWindow: UiWindow?
        get() = windows.firstOrNull { it.active && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

    /** 当前页面所属的应用包名。 */
    val packageName: String?
        get() = activeWindow?.packageName

    /** 跨所有窗口找节点。 */
    fun findAll(predicate: (UiNode) -> Boolean): List<UiNode> =
        windows.flatMap { it.root?.findAll(predicate).orEmpty() }

    /** 按 [UiNode.id] 跨窗口找回节点，供动作层回指。 */
    fun findById(id: Int): UiNode? {
        windows.forEach { window -> window.root?.findById(id)?.let { return it } }
        return null
    }

    /**
     * 人眼可读的树状输出，**只用于开发期排查**，不是给模型吃的格式。
     *
     * 给模型的压缩表示是另一回事（要砍掉不可见节点、合并冗余容器、控制 token），
     * 那部分单独做。这里优先把信息铺开，方便肉眼比对抓得全不全。
     *
     * @param maxTextLength 单个字段最多打印多少字符，避免一条长文本刷屏。
     * @param visibleOnly 只打印用户看得见的节点。
     */
    fun toDebugString(maxTextLength: Int = 40, visibleOnly: Boolean = false): String = buildString {
        appendLine(
            "UiSnapshot(display=$displayId, windows=${windows.size}, nodes=$nodeCount, " +
                "${elapsedMillis}ms${if (truncated) ", TRUNCATED" else ""})",
        )
        windows.forEach { window ->
            appendLine(
                "┌ window#${window.windowId} ${window.typeName} " +
                    "${window.packageName ?: "?"} layer=${window.layer}" +
                    (if (window.active) " ACTIVE" else "") +
                    (if (window.focused) " FOCUSED" else "") +
                    " nodes=${window.nodeCount}",
            )
            window.root?.walk { node ->
                if (visibleOnly && !node.visibleToUser) return@walk
                append("│ ")
                repeat(node.depth) { append("  ") }
                append("[${node.id}] ")
                append(node.className?.substringAfterLast('.') ?: "?")
                node.text.clip(maxTextLength)?.let { append(" text=\"$it\"") }
                node.contentDescription.clip(maxTextLength)?.let { append(" desc=\"$it\"") }
                node.hintText.clip(maxTextLength)?.let { append(" hint=\"$it\"") }
                (node.vid ?: node.viewId)?.let { append(" id=$it") }
                buildList {
                    if (node.clickable) add("click")
                    if (node.longClickable) add("longClick")
                    if (node.scrollable) add("scroll")
                    if (node.editable) add("edit")
                    if (node.checkable) add(if (node.checked) "checked" else "uncheck")
                    if (node.password) add("PASSWORD")
                    if (!node.enabled) add("disabled")
                    if (!node.visibleToUser) add("invisible")
                    // 子节点没抄全时必须显式标出来，否则会被误读成"这里本来就是空的"。
                    if (node.childrenTruncated) {
                        add("+${node.childCount - node.children.size}个子节点被截断")
                    }
                }.takeIf { it.isNotEmpty() }?.let { append(" {${it.joinToString(",")}}") }
                append(" ${node.bounds.toShortString()}")
                appendLine()
            }
        }
    }
}

/** 空白当没有；过长就截断并加省略号。 */
private fun CharSequence?.clip(max: Int): String? {
    val s = this?.toString()?.replace('\n', '⏎')?.trim()
    if (s.isNullOrEmpty()) return null
    return if (s.length <= max) s else s.take(max) + "…"
}
