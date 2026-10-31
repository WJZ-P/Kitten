package me.wjz.kitten.agent.tools

import android.content.Context
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.data.skills.SkillSource
import me.wjz.kitten.data.skills.SkillStore
import me.wjz.kitten.data.skills.UserSkill
import org.json.JSONObject

/**
 * 复盘 Agent 用的：把这次总结出的做法存成一个"Kitten 自己学的"技能（见 SkillReflector）。对话里的
 * 模型拿不到它，用户让写技能走的是 [SaveSkillTool]。
 *
 * 和 save_skill 的区别都是为了防记忆投毒（见 [UserSkill] 的类注释）：
 *
 * - 存进去的一律标成 [SkillSource.Learned]：提示词里单独列、只当参考，不算用户的话。
 * - 同名的只能覆盖它自己学的；用户写的技能它碰不了，不然经过里一句话就能改掉用户信任的说明书。
 * - 一次复盘最多存一个：复盘的是一件事，存出好几个多半是在拆着凑数。
 *
 * 新学的默认开着，下一轮对话起就用得上；用户在 设置 → 技能 里看得到、能关、能删。
 *
 * @param onLearned 存好了，告诉复盘那边学到了哪个（任务卡片下面那一行要写它的名字）。
 */
class LearnSkillTool(
    private val context: Context,
    private val onLearned: (UserSkill) -> Unit,
) : AgentTool {

    /** 这次复盘存过了没有。一个工具实例只活一次复盘。 */
    private var learned = false

    override val name = NAME

    override val description =
        "把这次总结出的做法存成一个技能，以后碰到对得上的事照着做。" +
            "已经有你自己总结的同名技能时会整份覆盖它：要改它，先用 read_skill 读出原来的内容，改好再整份存。" +
            "用户自己写的技能你不能改，也别存一个和它重复的。一次复盘最多存一个。不值得记就不用调它。"

    override val parameters = skillParameters()

    override suspend fun execute(args: JSONObject): String {
        if (learned) return "ERROR already_learned: 这次已经存过一个技能了，一次只存一个。"
        val skillName = args.optString("name").trim()
        val whenToUse = args.optString("when_to_use").trim()
        val instructions = args.optString("instructions").trim()
        skillArgsProblem(skillName, whenToUse, instructions)?.let { return it }

        val existing = SkillStore.findByName(context, skillName)
        if (existing != null && !existing.isLearned) {
            return "ERROR user_skill: 「${existing.name}」是用户自己写的技能，你不能改。换个名字，或者这次不用记。"
        }
        if (existing == null && SkillStore.userSkills(context).size >= UserSkill.MAX_COUNT) {
            return "ERROR skills_full: 技能已经存满了，这次不记了。"
        }
        val skill = (existing ?: UserSkill.create()).copy(
            name = skillName,
            whenToUse = whenToUse,
            instructions = instructions,
            source = SkillSource.Learned,
        )
        SkillStore.saveUserSkill(context, skill)
        learned = true
        onLearned(skill)
        return if (existing == null) "已存好技能「$skillName」。" else "已更新技能「$skillName」。"
    }

    companion object {
        const val NAME = "learn_skill"
    }
}
