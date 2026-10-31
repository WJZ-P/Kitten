package me.wjz.kitten.agent.tools

import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.data.skills.UserSkill
import org.json.JSONObject

/**
 * 读一个用户技能的完整说明书。
 *
 * ## 为什么不把说明书直接写进系统提示词
 *
 * 用户可能教了好几个技能，每个都写得很细（打开哪个应用、点哪里、选什么口味）。全塞进提示词的话，
 * 每轮每次请求都要带着它们，大多数时候根本用不上，还会让模型被一堆不相干的步骤带偏。所以提示词里
 * 只列名字和"什么时候用"（见 SystemPrompt），真碰到对得上的事，模型调这个工具把全文读进来。
 *
 * [skills] 是这一轮开始时开着的那些，整轮不变。
 */
class ReadSkillTool(private val skills: List<UserSkill>) : AgentTool {

    override val name = NAME

    override val description =
        "读一个技能的完整说明。技能是用户教你的做事方法，系统提示词里列着你会的技能和各自什么时候用。" +
            "要做的事和某个技能对得上时，先用它把那个技能读出来，再照着说明做；对不上的不用读。"

    override val parameters = objectSchema(
        "name" to stringParam("技能的名字，和系统提示词里列的一字不差。"),
        required = listOf("name"),
    )

    override suspend fun execute(args: JSONObject): String {
        val wanted = args.optString("name").trim()
        if (wanted.isEmpty()) return "ERROR bad_args: 没有给技能名字 name。"
        if (skills.isEmpty()) return "ERROR unknown_skill: 你现在没有学会任何技能，照平常的办法做就好。"
        // 先按原样找；模型偶尔会大小写、空格不一致，再宽松地找一次。
        val skill = skills.firstOrNull { it.name == wanted }
            ?: skills.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }
            ?: return "ERROR unknown_skill: 没有叫「$wanted」的技能。你会的技能有：" +
                skills.joinToString("、") { "「${it.name}」" } + "。"
        return "技能「${skill.name}」的说明：\n\n${skill.instructions.trim()}"
    }

    companion object {
        const val NAME = "read_skill"
    }
}
