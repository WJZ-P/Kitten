package me.wjz.kitten.agent.tools

import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringArrayParam
import me.wjz.kitten.agent.stringParam
import org.json.JSONObject

/**
 * 问用户一个问题，等他回答。
 *
 * 实现由调用方给（见 [me.wjz.kitten.ui.chat.ChatViewModel]）：问题会显示成对话里的一张选项
 * 卡片；用户这会儿不在 Kitten 界面里的话，常驻通知上也会出现这个问题，选项做成按钮，在通知
 * 上直接点就能答。
 */
fun interface UserAsker {
    /**
     * 挂起直到用户回答，返回他选的那一项（或者自己打的字）。
     *
     * [options] 为空表示让用户自己打字。
     */
    suspend fun ask(question: String, options: List<String>): String
}

/**
 * 卡在"不知道用户想要哪个"的时候用它，别自己替用户拿主意。
 *
 * 典型场景：用户说"在淘宝再买一次那个米酒"，但手机上装了淘宝、京东、拼多多，而他之前到底在
 * 哪家买的不一定；或者历史订单里翻出两笔相似的，不确定是哪一笔。
 */
class AskUserTool(private val asker: UserAsker) : AgentTool {

    override val name = "ask_user"

    override val description =
        "问用户一个问题并等他回答。不确定该选哪个、信息不够没法往下做的时候用它，" +
            "不要自己猜一个就动手。" +
            "能列出选项时一定要给 options，用户点一下就答完了，比打字快得多。" +
            "问题要具体——把你已经查到的信息放进去（\"找到两笔米酒订单：3 月 12 日 ¥89、" +
            "6 月 2 日 ¥95，要再买哪一笔？\"），而不是干问一句\"买哪个？\"。" +
            "用户可能不在 Kitten 界面里，这时问题会以通知的形式弹给他，所以一次只问一件事。"

    override val parameters = objectSchema(
        "question" to stringParam("要问的问题，一句话说清楚"),
        "options" to stringArrayParam(
            "备选项。能列就一定要列，用户点一下就答完了。实在没法列（比如要用户输入收货地址）才留空。",
        ),
        required = listOf("question"),
    )

    override suspend fun execute(args: JSONObject): String {
        val question = args.optString("question").trim()
        if (question.isEmpty()) return "ERROR bad_args: 没有给 question。"
        val options = args.optJSONArray("options")
            ?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).trim().takeIf(String::isNotEmpty) } }
            .orEmpty()
            .distinct()
        val answer = asker.ask(question, options)
        return "用户的回答：$answer"
    }
}
