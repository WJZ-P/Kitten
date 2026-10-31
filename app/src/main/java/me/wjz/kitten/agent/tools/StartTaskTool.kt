package me.wjz.kitten.agent.tools

import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.llm.ToolCall
import org.json.JSONObject

/**
 * 开一个任务：对话里出现一张任务卡片，接下来的操作都收进卡片里，用户点开才看完整过程。
 *
 * ## 为什么让模型自己开
 *
 * 一个任务动辄二十多步，全摊在对话里，用户要的结果被淹在一堆"读取屏幕内容"里。但不是每次调
 * 工具都值得一张卡片：查一下记忆、看一眼屏幕，一两步就完，套张卡片反而更繁琐。什么时候算"开始
 * 干一件事"，模型比规则判断得准，所以交给它，顺带让它起个人话标题（"找米酒订单"）。
 *
 * 工具本身什么都不做，只是在历史里留下一个记号：界面从这次调用开始，把后面的话和工具收进卡片，
 * 一直到这一轮结束（见 ChatScreen 的 buildChatItems）。所以卡片不用另外存，翻历史会话照样显示。
 * 模型没开任务的话，界面退回普通的工具清单。
 */
class StartTaskTool : AgentTool {

    override val name = NAME

    override val description =
        "开始一个任务。要连着操作手机好几步才能办完的事（找订单、比价、帮用户回消息、改设置……），" +
            "动手之前先调它，给任务起一个简短的人话标题，比如「找米酒订单」「比较两家的价格」。" +
            "之后的操作和你边做边说的话都会收进一张任务卡片，用户想看过程时点开看。" +
            "只是聊天、回答问题，或者一两步就能办完的事（查一下记忆、看一眼屏幕），不用开任务。" +
            "一轮里要办两件不相干的事，可以先后开两个任务。" +
            "任务办完以后，最后那段话直接告诉用户结果，它会显示在卡片外面。"

    override val parameters = objectSchema(
        "title" to stringParam("任务标题，${MAX_TITLE_LENGTH} 字以内的一句人话，说清楚要办什么。"),
        required = listOf("title"),
    )

    override suspend fun execute(args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return "ERROR bad_args: 没有给任务标题 title。"
        return "任务「${title.take(MAX_TITLE_LENGTH)}」开始了，接着办吧。步骤多的时候边做边简短说一句在干什么；" +
            "办完以后，最后那段话直接告诉用户结果。"
    }

    companion object {
        const val NAME = "start_task"

        /** 标题最多多少字。卡片上一行要放得下，长了会被截断。 */
        const val MAX_TITLE_LENGTH = 20

        /** 这次调用开的任务叫什么。模型没给标题（参数坏了）时退回一个泛泛的说法。 */
        fun titleOf(call: ToolCall): String =
            call.argumentsJson().optString("title").trim().take(MAX_TITLE_LENGTH).ifEmpty { "办一件事" }

        /**
         * 模型没开任务时用什么标题：用户那句话的第一行，太长截断。只发了图片的退回一句泛泛的。
         * 对话里自动开的任务卡片、任务页上正在办的那件事、复盘的那件事都用它，几处叫法一样。
         */
        fun autoTitle(userText: String): String {
            val firstLine = userText.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (firstLine.isEmpty()) return DEFAULT_AUTO_TITLE
            return if (firstLine.length > MAX_TITLE_LENGTH) firstLine.take(MAX_TITLE_LENGTH) + "…" else firstLine
        }

        /** 用户只发了图片、没打字时，自动开的任务叫什么。 */
        const val DEFAULT_AUTO_TITLE = "办你交代的事"
    }
}
