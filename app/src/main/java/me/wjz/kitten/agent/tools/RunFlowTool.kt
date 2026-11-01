package me.wjz.kitten.agent.tools

import android.content.Context
import me.wjz.kitten.agent.AgentTaskHandle
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.flow.FlowOutcome
import me.wjz.kitten.agent.flow.FlowRunner
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.data.skills.SkillStore
import me.wjz.kitten.data.skills.UserSkill
import org.json.JSONObject

/**
 * 跑一个技能里存好的自动流程（见 [me.wjz.kitten.data.skills.SkillFlow]）：程序照着记下来的步骤自己
 * 点完（[FlowRunner]），模型只调这一下。
 *
 * 这就是"跑通过一次，下次不用再花 token"的那一半：第一次模型一步步摸索着办成，复盘把这条路存成
 * 流程；下次同样的事，模型看到技能列表里有这个流程，调一下就完了。卡住了照样交回给模型接着做。
 *
 * 只在有开着的技能带着流程时才给模型（见 ChatViewModel），系统提示词里列着有哪些、要什么参数
 * （见 SystemPrompt）。[skills] 是这一轮开始时开着的那些，整轮不变。
 */
class RunFlowTool(
    private val context: Context,
    private val session: DeviceSession,
    private val skills: List<UserSkill>,
) : AgentTool {

    override val name = NAME

    override val description =
        "直接跑一个技能里存好的自动流程：程序照着以前跑通过的步骤自己点完，几秒钟就好，不用你一步步操作。" +
            "系统提示词的技能列表里写着每个技能有哪些流程、要哪些参数，对得上就优先用它。" +
            "跑完了先 read_screen 看一眼现在的页面再接着办；跑到一半卡住了，结果里会说卡在哪一步，你从那里接着自己做完。"

    override val parameters = objectSchema(
        "skill" to stringParam("技能的名字，和技能列表里的一字不差。"),
        "flow" to stringParam("流程的名字，和技能列表里的一字不差。"),
        "params" to JSONObject()
            .put("type", "object")
            .put("description", "流程要的参数，比如 {\"keyword\": \"蓝牙耳机\"}。流程不要参数就不用传。")
            .put("additionalProperties", JSONObject().put("type", "string")),
        required = listOf("skill", "flow"),
    )

    override suspend fun execute(args: JSONObject): String {
        val skillName = args.optString("skill").trim()
        val flowName = args.optString("flow").trim()
        if (skillName.isEmpty() || flowName.isEmpty()) return "ERROR bad_args: skill 和 flow 都要给。"
        val skill = skills.firstOrNull { it.name == skillName }
            ?: skills.firstOrNull { it.name.trim().equals(skillName, ignoreCase = true) }
            ?: return "ERROR unknown_skill: 没有叫「$skillName」的技能。带流程的技能有：" +
                skills.filter { it.flows.isNotEmpty() }.joinToString("、") { "「${it.name}」" } + "。"
        val flow = skill.findFlow(flowName)
            ?: return "ERROR unknown_flow: 技能「${skill.name}」里没有叫「$flowName」的流程，有的是：" +
                skill.flows.joinToString("、") { "「${it.name}」" } + "。"

        val params = readParams(args)
        val missing = flow.params.filter { params[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            return "ERROR bad_args: 流程「${flow.name}」要这些参数：${flow.params.joinToString("、")}，" +
                "还缺 ${missing.joinToString("、")}。放在 params 里传，比如 {\"${missing.first()}\": \"...\"}。"
        }

        val total = flow.steps.size
        val outcome = FlowRunner(session).run(flow, params) { index, step ->
            // 常驻通知和任务页上看得到跑到哪一步了。
            AgentTaskHandle.update("跑流程「${flow.name}」第 ${index + 1}/$total 步：${step.describe()}")
        }
        SkillStore.recordFlowRun(context, skill.id, flow.name, succeeded = outcome is FlowOutcome.Done)
        return when (outcome) {
            FlowOutcome.Done ->
                "OK 流程「${flow.name}」跑完了，一共 $total 步。先 read_screen 看看现在的页面，再接着办。"
            is FlowOutcome.Stuck ->
                "ERROR flow_stuck: 流程「${flow.name}」跑到第 ${outcome.index + 1} 步（${outcome.step.describe()}）卡住了：" +
                    "${outcome.reason}。前 ${outcome.index} 步已经做完。" +
                    "先 read_screen 看看现在停在哪，再自己接着把事情办完。"
        }
    }

    /** 读参数。模型偶尔会把整个 params 写成一段 JSON 字符串，两种都收。 */
    private fun readParams(args: JSONObject): Map<String, String> {
        val json = args.optJSONObject("params")
            ?: args.optString("params").takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return emptyMap()
        return json.keys().asSequence().associateWith { json.optString(it).trim() }
    }

    companion object {
        const val NAME = "run_flow"
    }
}
