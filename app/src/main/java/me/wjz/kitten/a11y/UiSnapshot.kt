package me.wjz.kitten.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
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

    /**
     * 节点树的顶层节点。
     *
     * 抓取阶段只会有一个（窗口的根），但**过滤之后可能变成多个**：原来的根不符合
     * 保留条件被砍掉时，它底下那些符合条件的后代就各自成了顶层节点。
     * QQ 主界面在 [CaptureScope.INTERACTIVE] 下就是这样——根是个不可交互的
     * FrameLayout，砍掉后会话列表、底部导航、顶部功能区变成三支并列的树。
     *
     * 所以这里必须是列表。之前写成单个 `root?`，过滤时用 `firstOrNull()` 取头一个，
     * 结果除第一支以外的内容整块消失，实测 QQ 丢掉了整个底部导航栏。
     *
     * 窗口已销毁或没有内容时为空列表。
     */
    val roots: List<UiNode>,
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
        get() = roots.sumOf { it.subtreeSize }
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

    /** 这份快照被裁到了哪一档。 */
    val scope: CaptureScope,

    /** 裁剪之前抓到的节点总数。和 [nodeCount] 一比就知道滤掉了多少。 */
    val rawNodeCount: Int,


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

    /**
     * 当前应用的显示名，比如"哔哩哔哩"。查不到时为 null，这时只能看 [packageName]。
     *
     * 抓取时就查好存下来，而不是让上层拿着 packageName 再去问 PackageManager——
     * 快照是纯数据，不该为了一个名字再绑一个 Context。
     *
     * 能查到是因为 manifest 里声明了 QUERY_ALL_PACKAGES；没有它的话，Android 11 起
     * 的 package visibility 会让 `getApplicationInfo()` 对大部分应用抛
     * NameNotFoundException（实测 shell 能看到 494 个包，app 侧只有 472 个）。
     *
     * 仍然可能是 null：应用正在被卸载、跨用户、或者某些 ROM 的额外限制。所以这个
     * 字段只用来让模型读着舒服，**任何判断逻辑都必须认 [packageName]**。
     */
    val appName: String? = null,

    /**
     * [UiNode.id] → 对应的活节点，动作层执行操作用。
     *
     * [UiNode] 是抄下来的纯数据，上面没有、也不可能有 `performAction`。模型回一句
     * `click(217)`，要真点下去就得找回那个活节点——这张表就是 id 和它之间的桥。
     *
     * 为什么敢长期持有：minSdk 33 起 `AccessibilityNodeInfo.recycle()` 已是空方法
     * （对象池被废弃），不再有回收负担。
     *
     * 过滤不改动 id，所以 [filter] 里的 `copy()` 会把整张表原样带过去——**包括被
     * 裁掉的那些节点**。这是有意的：模型只该看见 INTERACTIVE 档的节点，但动作层
     * 偶尔需要碰一下被裁掉的祖先（比如点子节点没反应、要回退到外层容器）。
     *
     * 节点会随页面变化失效，那时 `performAction` 返回 false——这正是"页面已经变了，
     * 该重抓"的可靠信号，比我们自己猜强。
     */
    internal val handles: Map<Int, AccessibilityNodeInfo> = emptyMap(),
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
        windows.flatMap { window -> window.roots.flatMap { it.findAll(predicate) } }

    /** 按 [UiNode.id] 跨窗口找回节点，供动作层回指。 */
    fun findById(id: Int): UiNode? {
        windows.forEach { window -> window.roots.forEach { r -> r.findById(id)?.let { return it } } }
        return null
    }

    /**
     * 按 [UiNode.id] 取回可操作的活节点，动作层用它 `performAction`。
     *
     * 返回 null 表示这个 id 不是本次快照发出去的。拿到了也不保证能用——页面变化后
     * 节点会失效，那时 `performAction` 返回 false，应该重抓而不是重试。
     *
     * 查得到的范围比 [findById] 宽：它覆盖抓取时的**全部**节点，包括被 [CaptureScope]
     * 裁掉、因而没出现在模型眼前的那些。
     */
    fun handleOf(id: Int): AccessibilityNodeInfo? = handles[id]

    /**
     * 喂给模型的紧凑表示。配 [CaptureScope.INTERACTIVE] 用。
     *
     * ```
     * app=QQ pkg=com.tencent.mobileqq display=2233
     * [20] RecyclerView scroll
     * [23] "晚上11:38" click
     * [45] "@人工智障 🦌" click
     * [131] EditText click,longClick,edit
     * [132] "发送" click,DISABLED
     * ```
     *
     * ## 为什么不是 JSON
     *
     * 同一份数据（QQ 聊天页 34 个节点）实测下来：完整 JSON ≈1287 token，
     * 精简 JSON（短键、去 null）≈853，这个格式 ≈335。**JSON 贵 2.5 倍**——
     * 字段名在每个节点上重复一遍，34 个节点就是 34 遍 `"clickable":`，
     * 括号引号逗号也都要钱。而 Agent 是每个操作前都要重抓一次的，一次会话几十轮，
     * 这个倍数会被放大几十次。
     *
     * JSON 的长处是机器解析无歧义，但这里的消费者是模型不是解析器；这种
     * `[id] 内容 标志` 的形式在训练数据里大量存在（日志、目录树），模型读得很稳。
     * 反过来，**模型发回来的动作指令必须结构化**（tool use 的 schema），
     * 那边要被我们的代码执行，容不得歧义。两个方向的约束是相反的。
     *
     * ## 为什么不给坐标
     *
     * 动作层按 [UiNode.id] 回指节点，模型不需要坐标。给了反而诱导它自己算位置，
     * 而 bounds 四个数字对每个节点都是净增开销。只有在回退到坐标点击时我们自己用。
     *
     * ## 为什么是扁平的
     *
     * 过滤后剩下的层级是原始 DOM 的残影，不是语义分组——包着一条消息的那层容器
     * 往往不可交互、已经被砍掉了。实测 QQ 聊天页用缩进输出，消息正文的缩进比昵称
     * 还浅，看起来像"昵称属于头像、正文是它们的叔辈"；底部六个并列的工具按钮也会
     * 分裂成两个缩进层级。扁平反而更诚实：id 单调递增，相邻即相近。顺带省 25% token。
     */
    fun toCompactString(): String = buildString {
        // appName 经常查不到（package visibility），拿不到就整段省掉，
        // 不要留一个空的 "app=" 让模型去猜它是什么意思。
        appName?.let { append("app=$it ") }
        appendLine("pkg=${packageName ?: "?"} display=$displayId")

        windows.forEach { window ->
            // 多窗口时标一行，否则模型会把弹窗/输入法的元素当成主界面的。
            if (windows.size > 1) {
                appendLine("--- ${window.typeName} ${window.packageName ?: "?"} ---")
            }
            window.roots.forEach { root ->
                root.walk { node ->
                    append("[${node.id}] ")
                    val label = node.displayLabel?.compactOneLine()
                    // 没有任何文字的节点保留类名——它至少说明这是个什么控件。
                    // 这种节点实测多半是没设 contentDescription 的图标，skill 的
                    // 提示词里要告诉模型会遇到。
                    if (label != null) append("\"$label\"") else {
                        append(node.className?.substringAfterLast('.') ?: "?")
                    }
                    node.compactFlags().takeIf { it.isNotEmpty() }
                        ?.let { append(" ${it.joinToString(",")}") }
                    appendLine()
                }
            }
        }
    }

    /**
     * 人眼可读的树状输出，**只用于开发期排查**，不是给模型吃的格式。
     *
     * 给模型的是 [toCompactString]。这里优先把信息铺开（带层级、带坐标、带原始字段），
     * 方便肉眼比对抓得全不全。
     *
     * @param maxTextLength 单个字段最多打印多少字符，避免一条长文本刷屏。
     * @param visibleOnly 只打印用户看得见的节点。
     */
    fun toDebugString(maxTextLength: Int = 40, visibleOnly: Boolean = false): String = buildString {
        appendLine(
            "UiSnapshot(display=$displayId, scope=$scope, windows=${windows.size}, " +
                "nodes=$nodeCount/$rawNodeCount, ${elapsedMillis}ms" +
                "${if (truncated) ", TRUNCATED" else ""})",
        )
        windows.forEach { window ->
            appendLine(
                "┌ window#${window.windowId} ${window.typeName} " +
                    "${window.packageName ?: "?"} layer=${window.layer}" +
                    (if (window.active) " ACTIVE" else "") +
                    (if (window.focused) " FOCUSED" else "") +
                    " nodes=${window.nodeCount}" +
                    // 过滤后会出现多支并列的树，标出来免得看漏整支。
                    (if (window.roots.size > 1) " roots=${window.roots.size}" else ""),
            )
            window.roots.forEach { it.walk { node ->
                if (visibleOnly && !node.visibleToUser) return@walk
                append("│ ")
                repeat(node.depth) { append("  ") }
                append("[${node.id}] ")
                append(node.className?.substringAfterLast('.') ?: "?")
                // 聚合标签存在时它就是这个节点的全部文本信息，再打原始字段纯属重复。
                if (node.label != null) {
                    node.label.clip(maxTextLength * 3)?.let { append(" label=\"$it\"") }
                } else {
                    node.text.clip(maxTextLength)?.let { append(" text=\"$it\"") }
                    node.contentDescription.clip(maxTextLength)?.let { append(" desc=\"$it\"") }
                    node.hintText.clip(maxTextLength)?.let { append(" hint=\"$it\"") }
                }
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
                    // 抓取时没抄全要显式标出来，否则会被误读成"这里本来就是空的"。
                    // 只报事实不报数量：过滤后 children 已被裁剪，再去减得到的是假数字。
                    if (node.childrenTruncated) add("子节点未抄全(共${node.childCount})")
                }.takeIf { it.isNotEmpty() }?.let { append(" {${it.joinToString(",")}}") }
                append(" ${node.bounds.toShortString()}")
                appendLine()
            } }
        }
    }
}

/**
 * 紧凑输出里的状态标志。
 *
 * `DISABLED` 特意大写：视觉上看不出来、模型又最容易忽略的就是它。实测 QQ 输入框
 * 为空时，发送按钮在屏幕上是正常的蓝色，节点树里却是 disabled——只看截图的话会点
 * 下去然后以为发出去了。
 */
private fun UiNode.compactFlags(): List<String> = buildList {
    if (clickable) add("click")
    if (longClickable) add("longClick")
    if (scrollable) add("scroll")
    if (editable) add("edit")
    if (checkable) add(if (checked) "checked" else "unchecked")
    if (password) add("PASSWORD")
    if (!enabled) add("DISABLED")
}

/**
 * 压成单行。
 *
 * 换行会把一个节点拆成多行，而紧凑格式靠"一行一个节点"让模型对齐 id，
 * 一条多行的聊天记录就能把后面所有节点的归属搞乱。引号同理，会让模型误判字段边界。
 */
private fun String.compactOneLine(): String =
    replace('\n', ' ').replace('\r', ' ').replace('"', '\'').trim()

/** 空白当没有；过长就截断并加省略号。 */
private fun CharSequence?.clip(max: Int): String? {
    val s = this?.toString()?.replace('\n', '⏎')?.trim()
    if (s.isNullOrEmpty()) return null
    return if (s.length <= max) s else s.take(max) + "…"
}
