package me.wjz.kitten.agent.tools

import android.content.Context
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.flow.TraceStep
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.data.skills.FlowStep
import me.wjz.kitten.data.skills.SkillFlow
import me.wjz.kitten.data.skills.SkillStore
import me.wjz.kitten.data.skills.UserSkill
import org.json.JSONArray
import org.json.JSONObject

/**
 * 复盘 Agent 用的：把这次操作记录（[trace]，见 ActionTrace）里跑通的一段存成某个技能里的自动流程。
 * 对话里的模型拿不到它。
 *
 * ## 只能挑，不能编
 *
 * 模型给的是操作记录里的编号，不是自己写的选择器：每一步都原样从记录里拿，那是这台手机上真点
 * 成功过、当场验过只找得到那一个节点的。模型能改的只有输入的内容——把每次会变的搜索词、收件人
 * 换成 `{参数名}`。模型写的选择器八成是错的，而且这样也越不过"流程只能是真做过的操作"这条线。
 *
 * ## 往哪存
 *
 * 存进已有的技能里，用户写的也行：用户的说明一个字不动，只是多了能直接跑的流程。同名的流程整份
 * 覆盖（修流程就是这么修的），steps 给空数组就是删掉。技能还没有 [UserSkill.app] 的，拿流程里打开的
 * 第一个应用补上。
 *
 * @param onSaved 存好了，告诉复盘那边动了哪个技能（任务卡片下面那一行要写它的名字）。
 */
class SaveFlowTool(
    private val context: Context,
    private val trace: List<TraceStep>,
    private val onSaved: (UserSkill) -> Unit,
) : AgentTool {

    /** 这次复盘存了几次，见 [MAX_SAVES]。 */
    private var saves = 0

    override val name = NAME

    override val description =
        "把这次操作记录里跑通的一段存成某个技能里的自动流程，以后 Kitten 调一下就能原样点完、不用再一步步操作。" +
            "steps 按执行顺序填操作记录里的编号。某一步输入的内容每次不一样（搜索词、收件人、留言）的，" +
            "在 inputs 里把那一步的内容写成 {参数名}，比如 {\"4\": \"{keyword}\"}，跑的时候再填。" +
            "同一个技能里同名的流程会被整份覆盖；steps 给空数组就是删掉这个流程。" +
            "技能要先有：还没有的先用 learn_skill 建。"

    override val parameters = objectSchema(
        "skill" to stringParam("存进哪个技能，技能的名字。"),
        "name" to stringParam("流程名，${SkillFlow.MAX_NAME_LENGTH} 字以内，说清楚办什么，比如「搜索商品」「查历史订单」。"),
        "description" to stringParam(
            "这个流程办什么、跑完停在哪个页面，一两句话，${SkillFlow.MAX_DESCRIPTION_LENGTH} 字以内。" +
                "比如「打开购物软件搜一个商品，停在搜索结果页」。",
        ),
        "steps" to JSONObject()
            .put("type", "array")
            .put("description", "操作记录里的编号，按执行顺序。空数组表示删掉这个流程。")
            .put("items", JSONObject().put("type", "integer")),
        "inputs" to JSONObject()
            .put("type", "object")
            .put(
                "description",
                "要改输入内容的步骤：编号 → 新的内容，内容里可以带 {参数名}。比如 {\"4\": \"{keyword}\"}。不改就不传。",
            )
            .put("additionalProperties", JSONObject().put("type", "string")),
        required = listOf("skill", "name", "steps"),
    )

    override suspend fun execute(args: JSONObject): String {
        if (saves >= MAX_SAVES) return "ERROR too_many: 这次复盘已经存了 $MAX_SAVES 次流程，够了，收尾吧。"
        val skillName = args.optString("skill").trim()
        val flowName = args.optString("name").trim()
        val description = args.optString("description").trim()
        if (skillName.isEmpty()) return "ERROR bad_args: 没有给技能名字 skill。"
        if (flowName.isEmpty()) return "ERROR bad_args: 没有给流程名 name。"
        if (flowName.length > SkillFlow.MAX_NAME_LENGTH) {
            return "ERROR too_long: 流程名最多 ${SkillFlow.MAX_NAME_LENGTH} 字，起个短一点的。"
        }
        if (description.length > SkillFlow.MAX_DESCRIPTION_LENGTH) {
            return "ERROR too_long: description 最多 ${SkillFlow.MAX_DESCRIPTION_LENGTH} 字，一两句话就够。"
        }
        val skill = SkillStore.findByName(context, skillName)
            ?: return "ERROR unknown_skill: 还没有叫「$skillName」的技能，先用 learn_skill 建一个，再往里存流程。"
        val numbers = readNumbers(args.optJSONArray("steps"))
            ?: return "ERROR bad_args: steps 要是一串编号，比如 [1, 2, 3]。"

        if (numbers.isEmpty()) return remove(skill, flowName)

        if (numbers.size > SkillFlow.MAX_STEPS) {
            return "ERROR too_long: 一个流程最多 ${SkillFlow.MAX_STEPS} 步，拆成几个流程。"
        }
        val inputs = readInputs(args.optJSONObject("inputs"))
            ?: return "ERROR bad_args: inputs 的键要是步骤编号，比如 {\"4\": \"{keyword}\"}。"
        inputs.keys.firstOrNull { it !in numbers }?.let {
            return "ERROR bad_args: inputs 里的第 $it 步不在 steps 里。"
        }
        val steps = numbers.map { number ->
            val recorded = trace.firstOrNull { it.number == number }?.step
                ?: return "ERROR bad_step: 操作记录里没有第 $number 步，编号只能从操作记录里挑。"
            val text = inputs[number] ?: return@map recorded
            if (recorded !is FlowStep.Input) return "ERROR bad_step: 第 $number 步不是输入，不能改内容。"
            recorded.copy(text = text)
        }

        val existing = skill.findFlow(flowName)
        if (existing == null && skill.flows.size >= SkillFlow.MAX_PER_SKILL) {
            return "ERROR skill_full: 技能「${skill.name}」里已经有 ${SkillFlow.MAX_PER_SKILL} 个流程了，" +
                "删掉用不着的（steps 给空数组）再存。"
        }
        val flow = SkillFlow(
            name = flowName,
            description = description,
            steps = steps,
            // 修过的流程重新算：以前卡住的次数说的是旧的那一份。
        )
        val saved = skill.copy(
            flows = if (existing == null) skill.flows + flow else skill.flows.map { if (it === existing) flow else it },
            app = skill.app ?: steps.filterIsInstance<FlowStep.Launch>().firstOrNull()?.packageName,
        )
        SkillStore.saveUserSkill(context, saved)
        saves++
        onSaved(saved)
        val params = flow.params.takeIf { it.isNotEmpty() }?.let { "，参数：${it.joinToString("、")}" }.orEmpty()
        return if (existing == null) {
            "已把流程「$flowName」存进技能「${skill.name}」（${steps.size} 步$params）。"
        } else {
            "已更新技能「${skill.name}」里的流程「$flowName」（${steps.size} 步$params）。"
        }
    }

    private fun remove(skill: UserSkill, flowName: String): String {
        val existing = skill.findFlow(flowName)
            ?: return "ERROR unknown_flow: 技能「${skill.name}」里没有叫「$flowName」的流程，不用删。"
        val saved = skill.copy(flows = skill.flows.filterNot { it === existing })
        SkillStore.saveUserSkill(context, saved)
        saves++
        onSaved(saved)
        return "已删掉技能「${skill.name}」里的流程「${existing.name}」。"
    }

    /** steps 里的编号。不是一串整数就返回 null。 */
    private fun readNumbers(array: JSONArray?): List<Int>? {
        array ?: return null
        return (0 until array.length()).map { index ->
            val value = array.opt(index)
            (value as? Number)?.toInt() ?: (value as? String)?.trim()?.toIntOrNull() ?: return null
        }
    }

    /** inputs：编号 → 内容。键不是编号就返回 null；没传是空的。 */
    private fun readInputs(json: JSONObject?): Map<Int, String>? {
        json ?: return emptyMap()
        return json.keys().asSequence().associate { key ->
            (key.trim().toIntOrNull() ?: return null) to json.optString(key)
        }
    }

    companion object {
        const val NAME = "save_flow"

        /** 一次复盘最多存几次流程。一件事整理出两三个流程就很多了，再多是在凑数。 */
        private const val MAX_SAVES = 6
    }
}
