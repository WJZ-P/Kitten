package me.wjz.kitten.agent.flow

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import me.wjz.kitten.a11y.A11yActuator
import me.wjz.kitten.a11y.ActionResult
import me.wjz.kitten.a11y.CaptureResult
import me.wjz.kitten.a11y.CaptureScope
import me.wjz.kitten.a11y.ScreenError
import me.wjz.kitten.a11y.ScreenReader
import me.wjz.kitten.a11y.UiSnapshot
import me.wjz.kitten.a11y.selector.Selector
import me.wjz.kitten.a11y.selector.SelectorSyntaxException
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.tools.KeyOutcome
import me.wjz.kitten.agent.tools.pressKey
import me.wjz.kitten.agent.tools.settle
import me.wjz.kitten.agent.tools.swipe
import me.wjz.kitten.agent.tools.tapNode
import me.wjz.kitten.data.skills.FlowStep
import me.wjz.kitten.data.skills.SkillFlow

/** 一个流程跑下来的结果。 */
sealed interface FlowOutcome {

    /** 每一步都做成了。 */
    data object Done : FlowOutcome

    /**
     * 第 [index] 步（从 0 数）卡住了，前面的都做完了。
     *
     * @param reason 一句给模型看的原因：等了多久、要找的是什么、现在屏幕上是什么情况。
     */
    data class Stuck(val index: Int, val step: FlowStep, val reason: String) : FlowOutcome
}

/**
 * 照着技能里存的流程（[SkillFlow]）一步步做完，全程不问模型：几十步也就十几秒，一个 token 不花。
 *
 * ## 找节点
 *
 * 要找节点的那几步（点、长按、输入、滚动）：抓一次屏（完整的树），拿那一步的选择器找**看得见的**
 * 节点，找到就动手；找不到就隔 [POLL_MILLIS] 再抓一次，直到等满 [STEP_TIMEOUT_MILLIS]。页面在加载、
 * 转场动画还没放完，都靠这个等过去，用不着在流程里写死"等几秒"。刚打开应用的下一步多等一会儿
 * （[LAUNCH_TIMEOUT_MILLIS]）：开屏广告、首页加载都要时间。
 *
 * 节点是灰的（disabled）也接着等：输入框没填完时"搜索"按钮常是灰的，过一会儿就好了。
 *
 * ## 卡住了交给 AI
 *
 * 某一步等到超时还找不到（应用改版了、弹了个广告、今天的页面不一样），或者动手失败了，就停在那里，
 * 把卡在第几步、要找的是什么告诉模型（[FlowOutcome.Stuck]），它从那里接着自己做。脚本带路、AI 兜底：
 * 能走通的那段走得快，走不通的照样能办成。
 *
 * ## 照原样点，不再问
 *
 * 流程里的步骤是这台手机上真点成功过的（见 ActionTrace），照着重放，中途不停下来确认——按用户的
 * 意思，给 AI 最大的权限。要不要先问用户，是模型调 run_flow 之前的事。
 *
 * 点下去的方式和模型自己调 tap 一模一样（[tapNode] 那一套），每一步之后照样等页面稳一稳（[settle]）。
 * 跑的每一步也记进操作记录（[ActionTrace]，带着流程名），流程半路卡住、AI 接着办成的话，复盘拿着这份
 * 完整的路能把流程修好。
 */
class FlowRunner(private val session: DeviceSession) {

    /**
     * 跑一遍 [flow]。
     *
     * @param params 参数的值，`{keyword}` → 实际的关键词。调用方先检查过齐不齐。
     * @param onStep 开始做第几步了（从 0 数），任务页和常驻通知上显示进度用。
     */
    suspend fun run(
        flow: SkillFlow,
        params: Map<String, String>,
        onStep: (index: Int, step: FlowStep) -> Unit = { _, _ -> },
    ): FlowOutcome = withContext(Dispatchers.IO) {
        var justLaunched = false
        try {
            flow.steps.forEachIndexed { index, step ->
                onStep(index, step)
                val problem = perform(step, params, flow.name, justLaunched)
                if (problem != null) return@withContext FlowOutcome.Stuck(index, step, problem)
                justLaunched = step is FlowStep.Launch
            }
            FlowOutcome.Done
        } finally {
            // 模型手里那份快照的节点编号早就对不上了，逼它先 read_screen 再动手。
            session.lastSnapshot = null
        }
    }

    /** 做一步：做成了返回 null，没做成返回原因。 */
    private suspend fun perform(
        step: FlowStep,
        params: Map<String, String>,
        flowName: String,
        justLaunched: Boolean,
    ): String? {
        val timeout = if (justLaunched) LAUNCH_TIMEOUT_MILLIS else STEP_TIMEOUT_MILLIS
        return when (step) {
            is FlowStep.Launch -> {
                val result = session.launchApp(step.packageName)
                if (result.startsWith("ERROR")) {
                    result
                } else {
                    session.trace.record(step, step.packageName, flowName)
                    null
                }
            }
            is FlowStep.Tap -> onNode(step, step.selector, flowName, timeout) { snapshot, id ->
                tapNode(snapshot, id).result
            }
            is FlowStep.LongPress -> onNode(step, step.selector, flowName, timeout) { snapshot, id ->
                A11yActuator.longClick(snapshot, id)
            }
            is FlowStep.Input -> {
                val text = SkillFlow.fill(step.text, params)
                // 记进操作记录的是实际输入的内容，和模型自己输入时一样。
                onNode(step.copy(text = text), step.selector, flowName, timeout) { snapshot, id ->
                    A11yActuator.setText(snapshot, id, text)
                }
            }
            is FlowStep.Scroll -> onNode(step, step.selector, flowName, timeout) { snapshot, id ->
                A11yActuator.scroll(snapshot, id, step.forward)
            }
            is FlowStep.Swipe -> when (
                val result = swipe(session.displayId, step.fromX, step.fromY, step.toX, step.toY, step.durationMs)
            ) {
                is ActionResult.Failed -> result.error.describe()
                is ActionResult.Ok -> finished(step, flowName)
            }
            is FlowStep.Key -> when (val outcome = pressKey(session.displayId, step.key)) {
                is KeyOutcome.Failed -> outcome.message
                is KeyOutcome.Done -> finished(step, flowName)
            }
            is FlowStep.Wait -> {
                delay(step.seconds * 1000L)
                session.trace.record(step, session.currentPackage, flowName)
                null
            }
        }
    }

    /** 不找节点的一步做成了：记一笔、等页面稳一稳。 */
    private suspend fun finished(step: FlowStep, flowName: String): String? {
        session.trace.record(step, session.currentPackage, flowName)
        settle()
        return null
    }

    /**
     * 找到 [selectorSource] 指的那个看得见的节点，对它做 [action]。找不到就隔一会儿再找，等满 [timeout]
     * 毫秒还没有就返回原因。
     */
    private suspend fun onNode(
        step: FlowStep,
        selectorSource: String,
        flowName: String,
        timeout: Long,
        action: (UiSnapshot, Int) -> ActionResult,
    ): String? {
        val selector = try {
            Selector.parse(selectorSource)
        } catch (e: SelectorSyntaxException) {
            return "这一步记的定位方式坏了（${e.message}）"
        }
        val target = step.label?.let { "「$it」" } ?: "要找的元素"
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            val problem = when (
                val capture = ScreenReader.capture(
                    displayId = session.displayId,
                    scope = CaptureScope.ALL,
                    // 和 read_screen 一样核对应用还在不在这块屏上，被用户抢走了就别对着别的应用乱点。
                    expectedPackage = session.currentPackage,
                )
            ) {
                is CaptureResult.Failed -> capture.error.describe()
                is CaptureResult.Ok -> {
                    val snapshot = capture.snapshot
                    val node = selector.queryAll(snapshot).firstOrNull { it.visibleToUser }
                    when {
                        node == null -> "屏幕上没找到$target（$selectorSource）"
                        !node.enabled -> "$target 是灰的，点不了"
                        else -> when (val result = action(snapshot, node.id)) {
                            is ActionResult.Ok -> {
                                session.trace.record(step, snapshot.packageName, flowName)
                                settle()
                                return null
                            }
                            is ActionResult.Failed -> {
                                // 抓屏和动手之间页面又动了一下，节点失效：再找一次就好。别的失败如实报。
                                if (result.error !is ScreenError.NodeGone) return result.error.describe()
                                result.error.describe()
                            }
                        }
                    }
                }
            }
            if (SystemClock.elapsedRealtime() >= deadline) return "等了 ${timeout / 1000} 秒，$problem"
            delay(POLL_MILLIS)
        }
    }

    private companion object {
        /**
         * 一步最多等多久（毫秒）。一般的页面切换一两秒就好；网络慢的时候要三四秒；再等下去多半是
         * 页面真的不一样了，不如早点交给 AI。
         */
        const val STEP_TIMEOUT_MILLIS = 6_000L

        /** 刚打开应用的下一步最多等多久：开屏广告一般三到五秒，再加上首页加载。 */
        const val LAUNCH_TIMEOUT_MILLIS = 10_000L

        /**
         * 找不到时隔多久再抓一次。抓一次完整的树要一两百毫秒，抓得太勤只是白占着无障碍服务；
         * 隔太久又白白多等。
         */
        const val POLL_MILLIS = 400L
    }
}
