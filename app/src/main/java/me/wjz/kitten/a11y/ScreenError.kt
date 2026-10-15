package me.wjz.kitten.a11y

/**
 * 感知和动作共用的失败原因。
 *
 * ## 为什么要分这么细，而不是返回 null
 *
 * 这些错误最终是要**给模型看的**，它得据此决定下一步：重新拉起应用？改去操作另一块屏？
 * 还是放弃这次任务？一个 null 什么都说明不了，模型只能瞎猜或者反复重试。
 *
 * 典型场景：Agent 正在副屏操作 QQ，用户自己在主屏打开了 QQ——微信、QQ、抖音都是
 * 单实例应用，新实例一起来旧的就被它们自己 finish 掉（实测日志里是 `app-request`）。
 * 于是副屏那个 task 凭空消失。这时 Agent 再发指令过去，收到的应该是
 * [AppOnOtherDisplay]「它跑到 display=0 去了」或者 [AppNotRunning]「它已经没了」，
 * 而不是一个无从判断的失败。
 *
 * 每个分支都带够下一步需要的信息，[describe] 把它转成模型能直接读的一行。
 */
sealed interface ScreenError {

    /** 给模型读的一行说明：发生了什么 + 为什么 + 可以怎么办。 */
    fun describe(): String

    /**
     * 无障碍服务没连上。可能是用户关了它，也可能系统正在重启这个服务。
     *
     * 这不是重试能解决的，得让用户去处理。
     */
    data object ServiceUnavailable : ScreenError {
        override fun describe(): String =
            "ERROR service_unavailable: 无障碍服务未连接，无法读取屏幕。需要用户在系统设置里开启 Kitten 的无障碍服务。"
    }

    /**
     * 这块屏不存在了。
     *
     * 副屏是 Agent 自己创建的，可能已经被释放；也可能特权进程重启过，旧的 displayId
     * 跟着作废了。
     */
    data class DisplayGone(val displayId: Int) : ScreenError {
        override fun describe(): String =
            "ERROR display_gone: display=$displayId 不存在或已被释放。需要重新创建副屏并拉起应用。"
    }

    /**
     * 目标应用不在这块屏上，但在别的屏上找到了。
     *
     * **最常见的原因是被单实例应用的自我保护机制搬走了**：用户在主屏打开微信/QQ/抖音
     * 时，它们会把 Agent 在副屏开的那个实例 finish 掉，只留用户那个。
     *
     * [foundOn] 给出它现在在哪，模型可以据此决定是改去操作那块屏，还是重新拉起。
     */
    data class AppOnOtherDisplay(
        val packageName: String,
        val expectedDisplayId: Int,
        val foundOn: List<Int>,
    ) : ScreenError {
        override fun describe(): String = buildString {
            append("ERROR app_moved: $packageName 不在 display=$expectedDisplayId，")
            append("它现在在 display=${foundOn.joinToString("/")}。")
            append("常见原因是用户自己打开了这个应用——微信、QQ、抖音是单实例的，")
            append("新实例起来时旧的会被应用自己关掉。可以改为操作它所在的屏，或在副屏重新拉起。")
        }
    }

    /**
     * 所有屏幕上都找不到这个应用的窗口。
     *
     * 它没启动，或者已经退出了（被用户划掉、被系统回收、崩溃）。
     */
    data class AppNotRunning(
        val packageName: String,
        val expectedDisplayId: Int,
    ) : ScreenError {
        override fun describe(): String =
            "ERROR app_not_running: 所有屏幕上都没有 $packageName 的窗口，它没有在运行。" +
                "需要先把它拉起到 display=$expectedDisplayId。"
    }

    /**
     * 这块屏上一个窗口都没有。
     *
     * 刚建好还没拉起应用，或者应用启动失败了。和 [AppNotRunning] 的区别是这里没有
     * 指定期望的应用，纯粹是屏幕空着。
     */
    data class DisplayEmpty(val displayId: Int) : ScreenError {
        override fun describe(): String =
            "ERROR display_empty: display=$displayId 上没有任何窗口，可能应用还没拉起或启动失败。"
    }

    /**
     * 节点已经失效——页面变了，这个 id 指向的东西不在了。
     *
     * 快照是某一瞬间的定格，模型思考期间界面可能已经动过。正确的处理是**重新抓一次**
     * 再决策，而不是换个 id 重试。
     */
    data class NodeGone(val nodeId: Int) : ScreenError {
        override fun describe(): String =
            "ERROR node_gone: 节点 [$nodeId] 已失效，页面在上次抓取之后发生了变化。需要重新抓取屏幕再决定操作。"
    }

    /**
     * 节点在，但当前不可用。
     *
     * 视觉上未必看得出来：实测 QQ 输入框为空时，发送按钮是正常的蓝色，节点树里却是
     * disabled。模型应该先满足它的启用条件（比如先填输入框）再点。
     */
    data class NodeDisabled(val nodeId: Int, val label: String?) : ScreenError {
        override fun describe(): String =
            "ERROR node_disabled: 节点 [$nodeId]${label?.let { " \"$it\"" } ?: ""} 当前不可用，" +
                "需要先满足它的启用条件（例如输入框还空着时发送按钮通常是禁用的）。"
    }

    /**
     * 动作发出去了，但系统报告没有执行成功。
     *
     * `performAction` 返回 false 的原因系统不会告诉我们，常见的是这个节点不支持该动作
     * （很多自绘控件不实现 ACTION_CLICK）。这时可以退回到坐标点击。
     */
    data class ActionRejected(val nodeId: Int, val action: String) : ScreenError {
        override fun describe(): String =
            "ERROR action_rejected: 节点 [$nodeId] 拒绝了 $action 操作，该控件可能不支持这个动作。"
    }
}
