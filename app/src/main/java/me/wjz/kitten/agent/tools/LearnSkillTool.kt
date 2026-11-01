package me.wjz.kitten.agent.tools

import android.content.Context
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.data.skills.SkillSource
import me.wjz.kitten.data.skills.SkillStore
import me.wjz.kitten.data.skills.UserSkill
import org.json.JSONObject

/**
 * 复盘 Agent 用的：新建一个"Kitten 自己学的"技能，或者改写它以前学的那个的说明（见 SkillReflector）。
 * 对话里的模型拿不到它，用户让写技能走的是 [SaveSkillTool]；自动流程走的是 [SaveFlowTool]。
 *
 * 技能按应用分（见 [UserSkill] 的类注释）：新建时带上 app（包名），同一个应用以后办的新事都往这个
 * 技能里加，复盘的提示词里交代了。
 *
 * 和 save_skill 的区别都是为了防记忆投毒（见 [UserSkill] 的类注释）：
 *
 * - 存进去的一律标成 [SkillSource.Learned]：提示词里单独列、只当参考，不算用户的话。
 * - 用户写的技能它改不了说明，不然经过里一句话就能改掉用户信任的说明书；要给用户的技能加流程，用
 *   save_flow，那只加能重放的操作，不碰说明。
 * - 一次复盘最多动 [MAX_SKILLS] 个技能：一件事最多跨两三个应用（比价），再多是在凑数。
 *
 * 改写时原来的流程、应用都留着，只换说明。新学的默认开着，下一轮对话起就用得上；用户在 设置 → 技能
 * 里看得到、能关、能删。
 *
 * @param onLearned 存好了，告诉复盘那边动了哪个技能（任务卡片下面那一行要写它的名字）。
 */
class LearnSkillTool(
    private val context: Context,
    private val onLearned: (UserSkill) -> Unit,
) : AgentTool {

    /** 这次复盘动过的技能，见 [MAX_SKILLS]。 */
    private val touched = mutableSetOf<String>()

    override val name = NAME

    override val description =
        "新建一个技能，或者改写你以前总结的那个技能的说明（同名就是改写，整份覆盖说明，原来的流程留着）。" +
            "技能按应用分：一个应用一个，新建时 app 填那个应用的包名，名字用应用的名字。" +
            "要改它，先用 read_skill 读出原来的说明，在原来的基础上补充好再整份存。" +
            "用户自己写的技能你不能改说明，要给它加流程直接用 save_flow。不值得记就不用调它。"

    override val parameters = skillParameters().apply {
        getJSONObject("properties").put(
            "app",
            stringParam("这个技能管哪个应用，包名，比如 com.example.shop。新建时要给；改写时不给就保持原来的。"),
        )
    }

    override suspend fun execute(args: JSONObject): String {
        val skillName = args.optString("name").trim()
        val whenToUse = args.optString("when_to_use").trim()
        val instructions = args.optString("instructions").trim()
        val app = args.optString("app").trim().takeIf { it.isNotEmpty() }
        skillArgsProblem(skillName, whenToUse, instructions)?.let { return it }

        val existing = SkillStore.findByName(context, skillName)
        if (existing != null && !existing.isLearned) {
            return "ERROR user_skill: 「${existing.name}」是用户自己写的技能，说明你不能改。" +
                "要给它加流程，直接用 save_flow 存进它。"
        }
        val alreadyTouched = existing != null && existing.id in touched
        if (!alreadyTouched && touched.size >= MAX_SKILLS) {
            return "ERROR too_many: 这次复盘已经动了 $MAX_SKILLS 个技能，够了，收尾吧。"
        }
        if (existing == null && SkillStore.userSkills(context).size >= UserSkill.MAX_COUNT) {
            return "ERROR skills_full: 技能已经存满了，这次不记了。"
        }
        val skill = (existing ?: UserSkill.create()).copy(
            name = skillName,
            whenToUse = whenToUse,
            instructions = instructions,
            source = SkillSource.Learned,
            app = app ?: existing?.app,
        )
        SkillStore.saveUserSkill(context, skill)
        touched += skill.id
        onLearned(skill)
        return if (existing == null) "已建好技能「$skillName」。" else "已更新技能「$skillName」的说明。"
    }

    companion object {
        const val NAME = "learn_skill"

        /** 一次复盘最多动几个技能。 */
        private const val MAX_SKILLS = 3
    }
}
