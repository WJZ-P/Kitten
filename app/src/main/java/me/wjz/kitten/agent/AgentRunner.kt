package me.wjz.kitten.agent

import android.util.Log
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.LlmError
import me.wjz.kitten.llm.LlmResult
import me.wjz.kitten.llm.ToolSpec

/** 一轮任务跑完之后的结果。 */
sealed interface AgentResult {

    /** 模型给出了最终回复，没有再要求调工具。 */
    data object Done : AgentResult

    /** 某一步模型调用失败了。前面已经产出的消息照样留着。 */
    data class Failed(val error: LlmError) : AgentResult
}

/** 跑到哪一步了，给常驻通知和界面显示用——不是给模型看的。 */
sealed interface AgentProgress {
    /** 正在等模型想。 */
    data class Thinking(val step: Int) : AgentProgress

    /** 正在执行某个工具。[detail] 是一句人话，见 [describe]。 */
    data class RunningTool(val step: Int, val detail: String) : AgentProgress
}

/**
 * 发一次请求。由调用方提供，这样补 /v1、切配置这些事不用塞进循环里。
 *
 * [onTextDelta] 透传给流式：模型边说边往外冒字。
 */
fun interface ChatCaller {
    suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<ToolSpec>,
        onTextDelta: (String) -> Unit,
    ): LlmResult
}

/**
 * Agent 循环：模型 → 要求调工具 → 我们执行 → 结果回灌 → 模型接着想，直到它给出最终回复。
 *
 * ## thinking 块是什么、在哪
 *
 * Claude 开了思考时，每次回复里除了正文（界面上那个气泡）和工具调用，还有一段 thinking 块：
 * 模型自己的推理过程，带着服务端的签名。它**不在界面上显示**，用户看不到、也删不到——气泡里
 * 只有正文，见 MessageBubble。它存在的唯一意义是发回给模型，让下一步接着这段推理往下想。
 *
 * 麻烦在于签名**绑定在"它产生时看到的那段历史"上**：之前的消息、系统提示词、工具列表，改了
 * 任何一处，后面每个 thinking 块的签名就失效，下一次请求直接 400。
 *
 * ## 所以历史是只追加的
 *
 * [run] 自己维护这一轮的消息列表，不回头读界面状态：
 *
 * - 这一轮里 system 和 tools 都不许变，消息只往后加。
 * - 用户能长按删掉对话里的消息（正文气泡、自己发的话），任务进行中删也不会影响正在跑的这一轮，
 *   它用的是自己这份。
 *
 * thinking 块只活在这一轮的内存里，不写进数据库（见 [ChatMessage.Assistant.reasoning]）。下次
 * 打开会话时历史里一个 thinking 块都没有，也就没有能失效的东西——用户之后随便删消息，下一次
 * 请求都不会报错。代价是跨轮次的推理连不上，这正是我们要的取舍。
 *
 * ## 不设步数上限
 *
 * 以前一轮最多 24 步，实际用下来不合理：有的任务就是很长（翻好几页订单、在聊天里来回输入），
 * 做到一半被硬停，用户只看到"没做完"。所以不设上限，模型说完了才停。
 *
 * 代价是模型卡在"点了没反应 → 再抓一次 → 再点"的循环里时会一直跑、一直花钱。兜底靠用户喊停：
 * 常驻通知上的"停止"会取消这一轮的协程（见 [AgentTaskHandle]）。
 */
class AgentRunner(private val registry: ToolRegistry) {

    /**
     * 跑完一轮。
     *
     * @param history 这一轮开始时的历史，已经包含用户刚发的那条。
     * @param onMessage 每产生一条消息（模型的回复、工具的结果）就回调一次，调用方据此更新
     *        界面和落库。挂起函数：落库是 IO，让调用方自己决定在哪个线程做。
     */
    suspend fun run(
        caller: ChatCaller,
        history: List<ChatMessage>,
        onTextDelta: (String) -> Unit,
        onMessage: suspend (ChatMessage) -> Unit,
        onProgress: (AgentProgress) -> Unit,
    ): AgentResult {
        val working = history.toMutableList()
        var step = 0

        // 没有上限，见类注释。出口只有三个：模型说完了、模型调用失败、协程被取消（用户喊停）。
        while (true) {
            onProgress(AgentProgress.Thinking(step))
            val result = caller.chat(working, registry.specs, onTextDelta)
            val response = when (result) {
                is LlmResult.Failed -> return AgentResult.Failed(result.error)
                is LlmResult.Ok -> result.response
            }

            val reply = ChatMessage.Assistant(
                text = response.text,
                toolCalls = response.toolCalls,
                reasoning = response.reasoning,
            )
            working += reply
            onMessage(reply)

            // 没有要调的工具 = 这轮说完了。
            if (response.toolCalls.isEmpty()) return AgentResult.Done

            // 一轮里可能并发调好几个工具，按模型给的顺序逐个执行。
            response.toolCalls.forEach { call ->
                onProgress(AgentProgress.RunningTool(step, call.describe()))
                val output = registry.execute(call)
                Log.i(TAG, "工具 ${call.name} 完成，返回 ${output.text.length} 字、${output.images.size} 张图")
                val toolResult = ChatMessage.ToolResult(
                    callId = call.id,
                    name = call.name,
                    content = output.text,
                    images = output.images,
                )
                working += toolResult
                onMessage(toolResult)
            }
            step++
        }
    }

    private companion object {
        const val TAG = "KittenAgent"
    }
}

/**
 * 给没等到结果的工具调用补一个"被中断"的结果，插在那条调用后面。发请求之前对历史调一次。
 *
 * ## 为什么要补
 *
 * 用户喊停（输入栏上的停止、通知上的停止）时，模型要求的工具可能还没跑完，历史里就留下一条有
 * 调用、没结果的消息。两家接口都要求每个工具调用后面跟着它的结果，缺了整个请求直接 400——
 * 用户喊停之后，下一句话就再也发不出去了。
 *
 * ## 为什么不落库
 *
 * 只补在发出去的那份历史上。界面上这些调用显示成"已中断"本来就对（见 ToolCallList）；补的结果是
 * 给模型看的：告诉它上次是用户停掉的，别当成工具出错了自己接着重做。
 *
 * 插的位置不要求紧挨着：同一方挨着的消息组请求时会合并（见 AnthropicProvider 的 buildMessages），
 * OpenAI 那边工具结果只要在调用之后就行。
 */
fun List<ChatMessage>.withInterruptedResults(): List<ChatMessage> {
    val answered = filterIsInstance<ChatMessage.ToolResult>().mapTo(HashSet()) { it.callId }
    val hasDangling = any { message ->
        message is ChatMessage.Assistant && message.toolCalls.any { it.id !in answered }
    }
    if (!hasDangling) return this
    return buildList {
        this@withInterruptedResults.forEach { message ->
            add(message)
            if (message is ChatMessage.Assistant) {
                message.toolCalls.filter { it.id !in answered }.forEach { call ->
                    add(ChatMessage.ToolResult(callId = call.id, name = call.name, content = INTERRUPTED_RESULT))
                }
            }
        }
    }
}

/**
 * 补给被中断的调用的结果。说清楚"结果未知"：喊停的时候这一步可能已经做了一半（点下去了、字输进去了）。
 * 再叮嘱一句别自己接着做，等用户的新指示——用户喊停多半就是不想让它继续。
 */
private const val INTERRUPTED_RESULT =
    "STOPPED 用户中途停止了任务，这一步没有执行完，结果未知。不要自己接着做下去，按用户接下来说的办。"
