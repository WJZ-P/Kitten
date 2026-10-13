package me.wjz.kitten.a11y

import android.graphics.Rect

/**
 * 界面上的一个节点，从 `AccessibilityNodeInfo` 抄下来的不可变快照。
 *
 * 为什么要抄一份而不是直接传 `AccessibilityNodeInfo`：后者是活的跨进程句柄，
 * 读它的任何属性都可能再往目标应用进程跑一趟，而且目标页面一变它就失效。
 * 感知层要的是"某一瞬间屏幕长什么样"，必须是定格的——否则模型读到一半，
 * 树已经变了。抄成纯数据之后，它就可以安全地跨线程传递、缓存、序列化给模型。
 *
 * API 33 起 `recycle()` 已经是空方法（对象池被废弃了），所以这里不需要、也不应该
 * 去回收原始节点。
 */
data class UiNode(
    /**
     * 本次快照内的稳定编号，按深度优先的访问顺序从 0 递增。
     *
     * 它是感知层和动作层之间的契约：模型看到「[12] 按钮 发送」，回复 click(12) 即可，
     * 不用让它去猜坐标。**只在同一次快照内有效**——页面一动编号就可能错位，
     * 所以每次操作前都该重新抓一次。
     */
    val id: Int,

    /** 在树里的深度，根为 0。调试输出靠它缩进。 */
    val depth: Int,

    /** 在父节点里排第几个，根节点为 0。定位同类控件时用得上（"第 3 个列表项"）。 */
    val index: Int,

    /** 控件类名，如 android.widget.TextView。 */
    val className: String?,

    /** `viewIdResourceName`，形如 com.example:id/send_button。靠 flagReportViewIds 才有。 */
    val viewId: String?,

    /**
     * 去掉 `包名:id/` 前缀后的短 id，如 send_button。
     *
     * 前缀对同一个应用的每个节点都一样，纯属重复，喂给模型时白烧 token。
     * 有些节点的 id 不带 `包名:id/` 前缀，那种情况下为 null，看 [viewId] 原值即可。
     */
    val vid: String?,

    /** 显示的文字。可能很长（整篇文章），这里不截断，交给压缩层决定。 */
    val text: String?,

    /** 无障碍描述。图标按钮通常没有 text，全靠这个说明用途。 */
    val contentDescription: String?,

    /** 输入框的提示文字（placeholder）。 */
    val hintText: String?,

    /** 屏幕坐标。注意是**屏幕**不是窗口，副屏上的节点同样以该屏左上角为原点。 */
    val bounds: Rect,

    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val selected: Boolean,
    val focused: Boolean,
    val enabled: Boolean,

    /**
     * 是否是密码框。
     *
     * 系统不会把密码的真实内容放进 [text]，但这个标记必须留着：喂给模型之前
     * 要能认出它，避免把输入框里的东西（哪怕是被屏蔽后的占位）送出设备。
     */
    val password: Boolean,

    /**
     * 用户当前是否真的看得见。
     *
     * 节点树里有大量 `visibleToUser=false` 的东西——滚动容器外的、被遮住的、
     * 预加载还没显示的。它们对"屏幕上现在有什么"是纯噪音，压缩层的第一刀就砍它们，
     * 但抓取阶段先如实记下来。
     */
    val visibleToUser: Boolean,

    /**
     * 这个节点**真实**有多少个子节点。
     *
     * 和 `children.size` 不一定相等：抓取时对单节点子数和总节点数都设了上限，
     * 撞上限就会少抄一些。留着原始值，上层才能看出"这里被截断了"，
     * 而不是以为页面本来就长这样。
     */
    val childCount: Int,

    val children: List<UiNode>,
) {
    /** 子节点有没有因为撞上限而没抄全。 */
    val childrenTruncated: Boolean
        get() = children.size < childCount

    /** 这个节点能不能被操作。动作层挑目标、压缩层决定去留，都看它。 */
    val interactive: Boolean
        get() = clickable || longClickable || scrollable || editable || checkable

    /** 有没有可读的文字信息。 */
    val hasLabel: Boolean
        get() = !text.isNullOrBlank() ||
            !contentDescription.isNullOrBlank() ||
            !hintText.isNullOrBlank()

    /** 连同自己在内的子树节点数。 */
    val subtreeSize: Int
        get() = 1 + children.sumOf { it.subtreeSize }

    /** 深度优先遍历整棵子树（含自己）。 */
    fun walk(action: (UiNode) -> Unit) {
        action(this)
        children.forEach { it.walk(action) }
    }

    /** 按条件找节点，返回所有命中的。 */
    fun findAll(predicate: (UiNode) -> Boolean): List<UiNode> =
        buildList { walk { if (predicate(it)) add(it) } }

    /** 按 [id] 找回某个节点，找不到返回 null。 */
    fun findById(id: Int): UiNode? {
        if (this.id == id) return this
        children.forEach { child -> child.findById(id)?.let { return it } }
        return null
    }
}
