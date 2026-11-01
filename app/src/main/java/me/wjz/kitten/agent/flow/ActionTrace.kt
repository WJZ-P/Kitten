package me.wjz.kitten.agent.flow

import android.util.Log
import me.wjz.kitten.a11y.UiSnapshot
import me.wjz.kitten.a11y.selector.SelectorBuilder
import me.wjz.kitten.data.skills.FlowStep

/**
 * 操作记录里的一步：做了什么（已经换成能重放的 [FlowStep]，节点编号换成了选择器），在哪个应用里。
 *
 * @param number 这一轮里的第几步，从 1 数。复盘存流程时按它挑（见 SaveFlowTool）。
 * @param packageName 做这一步时屏幕上是哪个应用。复盘按它把流程归到对应应用的技能里。
 * @param alternatives 同一个节点的其它写法，最稳的那条已经在 [step] 里了。复盘时给模型看个参考：
 *   主选择器里要是带着价格、时间这种会变的字，它能看出这一步不适合放进流程。
 * @param fromFlow 这一步是跑某个流程时做的（见 FlowRunner），写着流程名；模型自己点的是 null。
 */
data class TraceStep(
    val number: Int,
    val step: FlowStep,
    val packageName: String?,
    val alternatives: List<String> = emptyList(),
    val fromFlow: String? = null,
)

/**
 * 一轮任务的操作记录：Agent 每次点、输入、滚动、打开应用……做成功了，就在这里记一笔。这一轮办完，
 * 复盘时从这里读到完整的操作路径，把跑通的一段存成技能里的自动流程（见 SkillReflector、SaveFlowTool）。
 *
 * ## 记的是能重放的样子
 *
 * 模型点的是"第 37 号节点"，这个编号下次抓屏就变了。所以记的时候当场把它换成选择器（见
 * [SelectorBuilder]）：趁着那份快照还在手里，验一遍这条选择器只找得到它。换不出来的那一步不记，
 * 宁可流程在那里断开交给 AI，也不记一条下次会点歪的。
 *
 * 只记成功了的：点了报错、节点失效的那一下不是这条路的一部分。读屏、截图、查记忆这些不改变界面的
 * 不记；跑流程时程序做的每一步照样记（带着 [TraceStep.fromFlow]），流程跑到一半卡住、AI 接着做完的
 * 话，复盘看到的是完整的一条路，能拿去修那个流程。
 *
 * ## 只在内存里
 *
 * 一轮一份，跟着 [me.wjz.kitten.agent.DeviceSession] 走，复盘读完就没了，不落盘：里面有用户输入
 * 过的字（搜索词、发出去的消息），没必要留。复盘真把哪一步存进流程，那一步才跟着技能存下来；隐私
 * 的输入复盘时要做成参数，见复盘的提示词。
 *
 * 密码框里的输入也记（自动登录、签到要用），但标成 [FlowStep.Input.secret]：内容照样存、照样重放，
 * 说给人和模型听的那句话里只写"输入密码（已隐藏）"，见 [recordNode]。
 */
class ActionTrace {

    private val steps = mutableListOf<TraceStep>()

    /** 到现在为止记下的全部步骤。 */
    fun snapshot(): List<TraceStep> = synchronized(this) { steps.toList() }

    /**
     * 记一步不找节点的操作：打开应用、滑动、按键、等待。
     *
     * @param packageName 做这一步时屏幕上的应用；打开应用那一步就是它要打开的。
     */
    fun record(step: FlowStep, packageName: String?, fromFlow: String? = null) = synchronized(this) {
        if (steps.size >= MAX_STEPS) return@synchronized
        steps += TraceStep(steps.size + 1, step, packageName, fromFlow = fromFlow)
    }

    /**
     * 记一步找节点的操作：模型对 [snapshot] 里编号 [nodeId] 的节点做了点什么。当场写好选择器，
     * 交给 [make] 拼成那一步；写不出能唯一定位它的选择器就不记。
     *
     * 在工作线程上调：写选择器要在整棵树上验几遍，节点多的页面要花几十毫秒。
     */
    fun recordNode(snapshot: UiSnapshot, nodeId: Int, make: (selector: String) -> FlowStep) {
        val node = snapshot.findRawById(nodeId) ?: return
        val selectors = SelectorBuilder.build(snapshot, nodeId)
        if (selectors.isEmpty()) {
            Log.i(TAG, "这一步写不出能唯一定位的选择器，不进操作记录")
            return
        }
        val step = make(selectors.first()).let {
            // 往密码框里输的：内容留着（重放要用），描述里藏起来，复盘的模型、设置页、通知上都看不到。
            if (node.password && it is FlowStep.Input) it.copy(secret = true) else it
        }
        synchronized(this) {
            if (steps.size >= MAX_STEPS) return
            steps += TraceStep(
                number = steps.size + 1,
                step = step,
                packageName = snapshot.packageName,
                alternatives = selectors.drop(1),
            )
        }
    }

    private companion object {
        const val TAG = "KittenTrace"

        /**
         * 一轮最多记几步。一轮任务几十步很常见，再多就是在绕圈了；复盘也只需要看一段跑通的路，
         * 记太多只会让复盘的请求变贵。
         */
        const val MAX_STEPS = 150
    }
}

/** 节点上的字，记进步骤里给人看（设置页、复盘）。太长的截掉。 */
internal fun UiSnapshot.labelFor(nodeId: Int): String? =
    (findById(nodeId)?.displayLabel ?: findRawById(nodeId)?.displayLabel)
        ?.replace('\n', ' ')
        ?.trim()
        ?.let { if (it.length <= MAX_LABEL_LENGTH) it else it.take(MAX_LABEL_LENGTH) + "…" }
        ?.takeIf { it.isNotEmpty() }

/** 步骤上记的字最长多少：设置页一行放得下，复盘时也认得出是哪个按钮。 */
private const val MAX_LABEL_LENGTH = 20
