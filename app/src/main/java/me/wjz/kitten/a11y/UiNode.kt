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

    /**
     * 当前是否可用。**动作层执行前必须检查它。**
     *
     * 视觉状态和实际可用性可以完全不一致：QQ 聊天页输入框为空时，发送按钮看着是
     * 正常的蓝色、一点没置灰，节点树里却是 `enabled=false`。
     *
     * ```
     * [132] Button label="发送" id=send_btn {click,disabled}
     * ```
     *
     * 这是节点树相对截图不可替代的地方——只看画面的话会点下去然后以为发出去了。
     */
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

    /**
     * **抓取时**有没有因为撞上限而没把子节点抄全。
     *
     * 必须是抓取阶段定下的事实，不能用 `children.size < childCount` 现算：
     * [CaptureScope] 的过滤会主动裁掉子节点，现算的话会把"我有意砍掉的"误报成
     * "没抄全"。真机实测过——QQ 那页 235 个节点离上限差得远，ALL 档一个截断都没有，
     * 过滤后却冒出 29 个假截断，模型会以为页面上还藏着东西。
     */
    val childrenTruncated: Boolean,

    val children: List<UiNode>,

    /**
     * 从子树聚合上来的可读标签，抓取阶段恒为 null，只有 [CaptureScope.INTERACTIVE] 会填。
     *
     * 为什么需要它：**可交互的是容器，有文本的是它的子节点**。真机实测微信聊天列表，
     * 12 个可见且可交互的节点里，自己带文本的是 0 个：
     *
     * ```
     * [196] LinearLayout id=cj1 {click,longClick}      ← 可点，但自己没有任何文本
     *   [204] View text="Kitten"                        ← 文本都在子节点上
     *   [205] TextView text="昨天"
     *   [208] View text="Miss you!"
     * ```
     *
     * 所以"只返回可交互节点"如果直接照做，给模型的就是 8 个一模一样的 `cj1` 空壳，
     * 它根本分不出哪个是"Kitten"哪个是"公众号"。必须把子树的文本收上来，变成
     * `Kitten · 昨天 · 测试123。`，这个模式才有意义。
     */
    val label: String? = null,
) {
    /** 给人看的最佳名称：优先用聚合标签，其次是节点自己的文本。 */
    val displayLabel: String?
        get() = label?.takeIf { it.isNotBlank() }
            ?: text?.takeIf { it.isNotBlank() }
            ?: contentDescription?.takeIf { it.isNotBlank() }
            ?: hintText?.takeIf { it.isNotBlank() }

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
