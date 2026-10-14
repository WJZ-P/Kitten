package me.wjz.kitten.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import me.wjz.kitten.service.KittenAccessibilityService

/**
 * 一键抓取屏幕上的全部界面节点。
 *
 * ## 为什么只能走无障碍
 *
 * View 树只活在目标应用自己的进程内存里，跨进程读它的唯一官方通道就是
 * `AccessibilityNodeInfo`。ADB 特权给的是"系统级操作权限"，不是"读别人进程内存"，
 * 所以这条路它也绕不开。真机实测过的另外两条：
 *
 * - `dumpsys activity top` 有完整 View 树，但 `TextView.toString()` 不输出文本
 *   （AOSP 的隐私设计），对读不了画面的模型等于没有内容；
 * - `uiautomator dump` 底层也是无障碍，且注册 UiAutomation 时 flags=0x0，会把
 *   其它无障碍服务整个挂起——每 dump 一次我们自己的服务就被断开重连一次，
 *   而且它写死 DEFAULT_DISPLAY，副屏读不到。
 *
 * 所以特权进程在这里完全不参与，[capture] 只和系统的
 * AccessibilityManagerService 打交道。
 *
 * ## 性能是怎么回事
 *
 * 取子节点时系统会批量预取，但一次最多带回
 * [AccessibilityNodeInfo.MAX_NUMBER_OF_PREFETCHED_NODES]（50）个节点——
 * 500 个节点的页面至少 10 个跨进程来回。这是地板，优化不掉，只能靠少抓。
 *
 * 两个策略位都是有意选的：
 * - `FLAG_PREFETCH_DESCENDANTS_HYBRID`：先铺开一层再往下递归，和我们深度优先的
 *   遍历顺序最贴近，预取回来的大多能直接用上；
 * - `FLAG_PREFETCH_UNINTERRUPTIBLE`：默认情况下预取会被"取节点/执行动作"的请求
 *   打断，打断就白取、得重来。我们是一次性抓整棵树，不希望半途作废。
 *
 * ## 必须在工作线程调用
 *
 * 每一次 `getChild` 都可能是一次阻塞的跨进程调用，整棵树抓下来几十到几百毫秒都
 * 正常。放主线程会直接卡住界面，页面复杂时足以 ANR。
 */
object ScreenReader {

    private const val TAG = "KittenScreenReader"

    /** 见类注释。两个策略位必须一起用。 */
    private const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or
        AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

    /**
     * 深度上限。
     *
     * 正常页面不会超过 30 层；设这个值是防御——节点树理论上可能成环或畸形，
     * 真撞上了只会无限递归到栈溢出。
     */
    const val MAX_DEPTH = 60

    /**
     * 节点数上限。
     *
     * 超过就停下并把 [UiSnapshot.truncated] 置真，宁可给上层一份标明残缺的结果，
     * 也不要为了一个异常页面把服务拖死。长列表类应用很容易上千节点。
     */
    const val MAX_NODES = 5000

    /**
     * 单个节点最多抄多少个子节点。
     *
     * 总量上限挡不住这种情况：某一个容器自己就挂着上万个子节点，光是把它们取回来
     * 就能把内存撑爆。GKD 为此开过两个 issue（gkd-kit/gkd#115、#650）后加了同样的
     * 限制，这里取同一个量级。
     *
     * 真实子节点数记在 [UiNode.childCount] 里，所以截断了也看得出来。
     */
    const val MAX_CHILDREN_PER_NODE = 512

    /** 无障碍服务没连上时为 null——没开启、或刚被系统重启。 */
    private val service: AccessibilityService?
        get() = KittenAccessibilityService.peek()

    val isAvailable: Boolean
        get() = service != null

    /**
     * 抓取指定屏幕上的所有窗口和节点。
     *
     * @param displayId 要抓哪块屏。默认主屏；副屏传 `VirtualDisplayManager` 给的 displayId。
     * @param scope 保留到什么程度，默认 [CaptureScope.ALL]——默认值不做有损变换，
     *        排查问题时第一步看到的就该是全貌。给模型用的是 [CaptureScope.INTERACTIVE]，
     *        由上层显式指定，别改这里的默认值。
     * @param maxNodes 本次最多抓多少节点，用于给慢页面兜底。
     * @return 无障碍服务没就绪时返回 null。那和"屏幕上没东西"是两回事，所以不返回空快照。
     */
    fun capture(
        displayId: Int = Display.DEFAULT_DISPLAY,
        scope: CaptureScope = CaptureScope.ALL,
        maxNodes: Int = MAX_NODES,
    ): UiSnapshot? {
        val service = service ?: run {
            Log.w(TAG, "无障碍服务未连接，抓不了屏幕")
            return null
        }
        val startedAtWall = System.currentTimeMillis()
        val startedAt = SystemClock.elapsedRealtime()

        val windows = runCatching { windowsOn(service, displayId) }
            .onFailure { Log.e(TAG, "取 display=$displayId 的窗口列表失败", it) }
            .getOrDefault(emptyList())

        val budget = Budget(maxNodes)
        // layer 降序：最上层的窗口排在最前，上层想要"挡在用户面前的那个"时直接取首个。
        val snapshots = windows
            .sortedByDescending { it.layer }
            .map { readWindow(it, displayId, budget) }

        // 先抓全树再裁剪，而不是边抓边滤：跨进程的遍历开销省不掉——要判断一个节点
        // 可不可见、能不能点，本来就得先把它读过来。而 INTERACTIVE 还要聚合子树的
        // 文本，更是非得有完整子树不可。裁剪是本地的纯数据变换，几乎不花时间。
        val snapshot = UiSnapshot(
            displayId = displayId,
            scope = CaptureScope.ALL,
            rawNodeCount = budget.used,
            capturedAtMillis = startedAtWall,
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt,
            windows = snapshots,
            nodeCount = budget.used,
            truncated = budget.truncated,
            appName = resolveAppName(service, snapshots),
            handles = budget.handles,
        ).filter(scope)

        Log.i(
            TAG,
            "抓取完成：display=$displayId scope=$scope 窗口=${snapshot.windows.size} " +
                "节点=${snapshot.nodeCount}/${snapshot.rawNodeCount} " +
                "耗时=${snapshot.elapsedMillis}ms 应用=${snapshot.packageName ?: "?"}" +
                if (snapshot.truncated) " 已截断(上限 $maxNodes)" else "",
        )
        return snapshot
    }

    /**
     * 只抓用户正在交互的那个应用窗口。
     *
     * 比 [capture] 轻，但会丢掉输入法和弹窗——正好挡在用户面前的对话框往往是独立
     * 窗口，需要完整上下文时还是用 [capture]。
     */
    fun captureActiveWindow(
        displayId: Int = Display.DEFAULT_DISPLAY,
        scope: CaptureScope = CaptureScope.ALL,
    ): UiWindow? = capture(displayId, scope)?.activeWindow

    /**
     * 把当前应用的包名翻成显示名，比如 com.tencent.mobileqq → QQ。
     *
     * 靠 manifest 里的 QUERY_ALL_PACKAGES 才查得到，否则 package visibility 会让
     * 大部分应用抛 NameNotFoundException。
     *
     * 失败就返回 null，调用方退回用包名，不打 error 日志吵人——应用正在卸载、跨用户、
     * 某些 ROM 的额外限制都会落到这里，而这个名字只是让模型读着舒服，判断逻辑一律
     * 认包名。
     */
    private fun resolveAppName(service: AccessibilityService, windows: List<UiWindow>): String? {
        val pkg = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION
        }?.packageName ?: return null
        return runCatching {
            val pm = service.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().takeIf { it != pkg }
        }.getOrNull()
    }

    /**
     * 取某块屏上的窗口。
     *
     * `getWindows()` 只覆盖默认屏，`getWindowsOnAllDisplays()` 才看得到副屏——
     * 副屏是 Kitten 的主场，所以统一走后者。
     */
    private fun windowsOn(
        service: AccessibilityService,
        displayId: Int,
    ): List<AccessibilityWindowInfo> {
        val byDisplay = service.windowsOnAllDisplays
        val index = byDisplay.indexOfKey(displayId)
        if (index < 0) {
            Log.w(TAG, "display=$displayId 上没有任何窗口，它可能已经被释放了")
            return emptyList()
        }
        return byDisplay.valueAt(index).orEmpty()
    }

    private fun readWindow(
        window: AccessibilityWindowInfo,
        displayId: Int,
        budget: Budget,
    ): UiWindow {
        val bounds = Rect().also { window.getBoundsInScreen(it) }
        val root = runCatching { window.getRoot(PREFETCH) }
            .onFailure { Log.w(TAG, "取 window#${window.id} 的根节点失败", it) }
            .getOrNull()
        val tree = root?.let { readNode(it, depth = 0, index = 0, budget = budget) }
        return UiWindow(
            windowId = window.id,
            displayId = displayId,
            type = window.type,
            title = window.title?.toString(),
            packageName = root?.packageName?.toString(),
            active = window.isActive,
            focused = window.isFocused,
            layer = window.layer,
            bounds = bounds,
            roots = listOfNotNull(tree),
        )
    }

    /**
     * 把一个活节点连同子树抄成不可变的 [UiNode]。
     *
     * 遍历期间界面可能继续变，`getChild` 返回 null 是正常现象（节点刚被回收），
     * 跳过即可，不该让整次抓取失败。
     */
    private fun readNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        index: Int,
        budget: Budget,
    ): UiNode? {
        if (!budget.take()) return null
        val id = budget.nextId()
        // 留住活节点，动作层按 id 找回来 performAction。见 UiSnapshot.handles。
        budget.handles[id] = node

        val bounds = Rect().also { node.getBoundsInScreen(it) }
        val childCount = node.childCount
        val children = if (depth >= MAX_DEPTH) {
            // 正常页面到不了这个深度，到了多半是畸形或成环，就地收手。
            budget.truncated = true
            Log.w(TAG, "节点深度超过 $MAX_DEPTH，停止下探：${node.className}")
            emptyList()
        } else {
            val limit = childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
            if (limit < childCount) {
                budget.truncated = true
                Log.w(TAG, "节点子数 $childCount 超过 $MAX_CHILDREN_PER_NODE，只抄前 $limit 个：${node.className}")
            }
            buildList {
                for (i in 0 until limit) {
                    // 取不到就跳过继续试下一个，不中断整次抓取：遍历期间界面可能在变，
                    // 个别节点刚好被回收是常态，不该让它连累后面的兄弟节点。
                    val child = runCatching { node.getChild(i, PREFETCH) }.getOrNull() ?: continue
                    readNode(child, depth + 1, i, budget)?.let { add(it) }
                }
            }
        }

        // isChecked() 在 API 36 被三态的 getChecked() 取代。这里仍用布尔版：minSdk 33
        // 用新 API 得加版本分支，而"半选中"对模型判断勾没勾没什么区别，等 minSdk
        // 到 36 再换不迟。
        @Suppress("DEPRECATION")
        val checked = node.isChecked

        val viewId = node.viewIdResourceName
        return UiNode(
            id = id,
            depth = depth,
            index = index,
            className = node.className?.toString(),
            viewId = viewId,
            vid = viewId?.shortVid(node.packageName?.toString()),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            hintText = node.hintText?.toString(),
            bounds = bounds,
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            scrollable = node.isScrollable,
            editable = node.isEditable,
            checkable = node.isCheckable,
            checked = checked,
            selected = node.isSelected,
            focused = node.isFocused,
            enabled = node.isEnabled,
            password = node.isPassword,
            visibleToUser = node.isVisibleToUser,
            childCount = childCount,
            // 只在这里判定一次。过滤阶段原样带着走，不重算——否则裁剪会被误报成截断。
            childrenTruncated = children.size < childCount,
            children = children,
        )
    }

    /**
     * 砍掉 `包名:id/` 前缀。前缀对不上就返回 null——有些节点的 id 来自别的包
     * （比如系统注入的控件），硬截会得到错的短名。
     */
    private fun String.shortVid(packageName: String?): String? {
        val prefix = "$packageName:id/"
        return if (packageName != null && startsWith(prefix)) substring(prefix.length) else null
    }

    /**
     * 贯穿整次抓取的预算与编号器。
     *
     * 编号必须全局递增（跨窗口也不能重复），否则动作层按 id 回指会找错节点，
     * 所以它和节点计数一起放在这里，而不是各窗口自己算。
     */
    private class Budget(private val limit: Int) {
        var used = 0
            private set
        var truncated = false

        /** id → 活节点。和编号器放在一起，保证两者用的是同一套 id。 */
        val handles = HashMap<Int, AccessibilityNodeInfo>()

        /** 还有余额就记一笔并返回 true；超了就标记截断。 */
        fun take(): Boolean {
            if (used >= limit) {
                if (!truncated) {
                    truncated = true
                    Log.w(TAG, "节点数达到上限 $limit，后续节点被丢弃")
                }
                return false
            }
            used++
            return true
        }

        fun nextId(): Int = used - 1
    }
}
