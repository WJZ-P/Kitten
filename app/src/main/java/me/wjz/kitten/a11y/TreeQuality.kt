package me.wjz.kitten.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 节点树缺了什么。[reason] 是给模型看的半句话，接在"这页"后面；[empty] 是树里一个字都没有。
 */
internal class TreeGap(val reason: String, val empty: Boolean)

/**
 * 这一屏的节点树够不够用。不够用时 read_screen 从画面上认字补上（见 [ScreenTexts]）。
 *
 * ## 只看树、不截图
 *
 * 每次 read_screen 都要判一次，得便宜：这里只在已经抓回来的树上数一数，判出不够用才去截图认字。
 *
 * 看的是裁剪之前的完整树（[UiSnapshot.rawWindows]），不是给模型看的那份：INTERACTIVE 那一档本来就把纯文字
 * 的节点裁掉了，拿它判会把每一页都判成"没有字"。只看应用自己的窗口（状态栏、输入法不算），只算用户看得见
 * 的节点。
 *
 * ## 三种算不够用
 *
 * - 树里一个字都没有：自绘的界面（游戏、部分小程序），或者应用把内容藏起来了。
 * - 能点、能填的控件大多没有名字：自己和子树里都没有字，模型只能看到一排类名。
 * - 有一大块区域在树里没有字：图片、画布、地图、读不到内容的网页。垫在有字的元素底下的那种不算（背景图、
 *   视频底下压着标题和按钮），见 [blindArea]。
 *
 * 判得宽一点没关系：认完发现没有树里没有的字，就什么都不补，只多花一次截图和认字的时间。
 */
internal object TreeQuality {

    /** 不够用的话缺了什么，够用返回 null。 */
    fun gap(snapshot: UiSnapshot): TreeGap? {
        val windows = snapshot.rawWindows.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.bounds.isEmpty
        }
        // 屏上没有应用的窗口（只有系统界面）：不归这里管。
        if (windows.isEmpty()) return null

        // 子树里有字的节点。一遍走完，下面两条都用它。
        val textual = HashSet<Int>()
        windows.forEach { window -> window.roots.forEach { markTextual(it, textual) } }

        val visible = windows.flatMap { window ->
            window.roots.flatMap { root -> root.findAll { it.visibleToUser && !it.bounds.isEmpty } }
        }
        val named = visible.filter { it.hasLabel }
        if (named.isEmpty()) return TreeGap(EMPTY_REASON, empty = true)

        val controls = visible.filter { it.clickable || it.editable }
        val namedControls = controls.count { it.id in textual }
        if (controls.size >= MIN_CONTROLS && namedControls * UNNAMED_RATIO <= controls.size) {
            return TreeGap(UNNAMED_REASON, empty = false)
        }
        if (windows.any { blindArea(it, named, textual) }) return TreeGap(BLIND_REASON, empty = false)
        return null
    }

    /** 把子树里有字的节点记进 [out]，返回 [node] 自己的子树里有没有字。 */
    private fun markTextual(node: UiNode, out: MutableSet<Int>): Boolean {
        var any = node.hasLabel
        // 不能短路：每个孩子的子树都要记一遍。
        node.children.forEach { if (markTextual(it, out)) any = true }
        if (any) out += node.id
        return any
    }

    /**
     * 这个窗口里有没有一块盲区：子树里一个字也没有、看得见、占窗口 [BLIND_AREA_FRACTION] 以上，而且不是
     * 垫在有字元素底下的底板（落在它范围里的有字元素不到 [BACKDROP_NAMED_INSIDE] 个）。
     */
    private fun blindArea(window: UiWindow, named: List<UiNode>, textual: Set<Int>): Boolean {
        val windowArea = window.bounds.area()
        if (windowArea <= 0L) return false
        val clipped = Rect()
        return window.roots.any { root ->
            root.findAll { node ->
                node.visibleToUser && node.id !in textual && clipped.setIntersect(node.bounds, window.bounds) &&
                    clipped.area() >= windowArea * BLIND_AREA_FRACTION &&
                    named.count { clipped.contains(it.bounds.centerX(), it.bounds.centerY()) } < BACKDROP_NAMED_INSIDE
            }.isNotEmpty()
        }
    }

    private fun Rect.area(): Long = if (isEmpty) 0L else width().toLong() * height()

    private const val EMPTY_REASON = "节点树里几乎读不到字"
    private const val UNNAMED_REASON = "大部分能点的控件没有名字"
    private const val BLIND_REASON = "有一大块区域在节点树里没有字（图片、画布或自绘的界面）"

    /**
     * 能点的控件至少这么多个，才按"大多没名字"判：只有两三个控件的小弹窗，一个图标按钮没名字就占了一半，
     * 不算树缺东西。
     */
    private const val MIN_CONTROLS = 4

    /** 有名字的控件只占三分之一或更少，就算"大多没名字"。 */
    private const val UNNAMED_RATIO = 3

    /**
     * 盲区至少占窗口多大（面积比例）。五分之一：一张商品主图、一块地图、一段读不到的网页都过线；列表里一个
     * 没写说明的小图标、头像远远不到。
     */
    private const val BLIND_AREA_FRACTION = 0.2

    /** 范围里落着这么多个有字元素的，是垫在下面的底板（背景图、视频画面），不算盲区。 */
    private const val BACKDROP_NAMED_INSIDE = 3
}
