package me.wjz.kitten.data.skills

import java.util.UUID

/**
 * Kitten 自带的技能：一组工具，加上系统提示词里教它怎么用这组工具的那一段。在设置 → 技能里能逐个
 * 关掉，关掉的那一组工具不交给模型，提示词里那一段也不写（见 SystemPrompt、ChatViewModel）。
 *
 * [id] 是存开关用的，定下来就别改：改了，用户关掉的会重新变回开着。
 */
enum class BuiltinSkill(val id: String) {
    /**
     * 操作手机：读屏、截图、点按、滑动、输入、打开应用……Kitten 的本职。开任务（start_task）也归它：
     * 任务卡片收的就是操作手机的步骤，没有单独的开关——模型忘了开，界面数到几步也会自动收进卡片
     * （见 ChatScreen 的 buildChatItems），关掉 start_task 卡片照样有，开关只会骗人。
     */
    Phone("phone"),

    /** 向你提问：拿不准时弹出带选项的问题卡片（ask_user）。 */
    Ask("ask"),

    /** 记忆：关于用户的小本子，换了对话也还在（recall / save / forget_memory）。 */
    Memory("memory"),

    /** 编写技能：用户在对话里让它写、让它改技能时，写好存进"自定义"（save_skill）。 */
    SkillWriting("skill_writing"),
}

/**
 * 用户教给 Kitten 的一个技能：一份"说明书"。
 *
 * 模型平时只看得到 [name] 和 [whenToUse]（列在系统提示词里），碰到对得上的事，才用 read_skill 把
 * [instructions] 全文读出来照着做。这样技能写得再长，没用到的时候也不占地方。
 *
 * 说明书是用户自己写的，算用户的话，模型照着做没问题；它和"屏幕上读到的字"不是一回事。
 *
 * @param id 内部用的编号，用户看不到。
 * @param name 名字，用户看得到，模型也按它来读（read_skill 的参数），所以不能重名。
 * @param whenToUse 什么时候用：模型靠它判断这件事该不该翻这本说明书。
 * @param instructions 具体怎么做：打开哪个应用、点哪里、选什么……
 * @param enabled 开着才交给模型。
 */
data class UserSkill(
    val id: String,
    val name: String,
    val whenToUse: String,
    val instructions: String,
    val enabled: Boolean = true,
) {
    companion object {
        /** 一份新的、空白的技能，给"添加技能"用。 */
        fun create(): UserSkill = UserSkill(
            id = UUID.randomUUID().toString(),
            name = "",
            whenToUse = "",
            instructions = "",
        )

        /** 名字最长几个字：要列在提示词里、也要在设置页一行放得下。 */
        const val MAX_NAME_LENGTH = 30

        /** "什么时候用"最长几个字：每个开着的技能都要写进提示词，一两句就够。 */
        const val MAX_WHEN_TO_USE_LENGTH = 200

        /** 说明书最长几个字：用到时才读，可以写得细一点，但一次读进来也不能太长。 */
        const val MAX_INSTRUCTIONS_LENGTH = 8000

        /** 最多存几个自定义技能：每个开着的都要在提示词里占一行，太多了模型也挑不过来。 */
        const val MAX_COUNT = 50
    }
}

/**
 * 这一轮任务开着的技能：哪些自带技能开着，和开着的用户技能。每轮开始时读一次，整轮用同一份，
 * 和系统提示词一样（中途变了会让 thinking 块失效，见 SystemPrompt）。
 */
data class SkillSet(
    val builtins: Set<BuiltinSkill>,
    val userSkills: List<UserSkill>,
) {
    operator fun contains(skill: BuiltinSkill): Boolean = skill in builtins
}
