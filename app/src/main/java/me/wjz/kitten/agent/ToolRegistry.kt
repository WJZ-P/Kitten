package me.wjz.kitten.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.wjz.kitten.llm.ToolCall
import me.wjz.kitten.llm.ToolSpec
import kotlin.coroutines.cancellation.CancellationException

/**
 * 这一轮任务里模型能用的全部工具。
 *
 * ## 为什么执行时把异常也吃掉
 *
 * 工具里抛出来的异常（空指针、系统接口变了）是**我们的 bug，不是模型的**，但直接让它冒出去
 * 会把整轮任务打断，用户看到的只是"失败了"。转成一句文本交给模型，它至少还能换条路走完，
 * 或者如实告诉用户这一步做不到。日志里照样打全栈，排查不受影响。
 *
 * 协程取消是例外，必须原样抛出去：用户点了"停止"、页面销毁了，这时候再"继续想办法"就是
 * 不听话了。
 */
class ToolRegistry(private val tools: List<AgentTool>) {

    /** 发给模型的工具定义。一轮任务里不能变——改了会让之前的 thinking 块失效，见 [AgentRunner]。 */
    val specs: List<ToolSpec> = tools.map { ToolSpec(it.name, it.description, it.parameters) }

    private val byName = tools.associateBy { it.name }

    /**
     * 执行一次调用，返回给模型看的文本。
     *
     * 统一切到 IO 线程：抓屏是一串跨进程调用，无障碍手势还要用 CountDownLatch 等派发完成
     * （见 [me.wjz.kitten.a11y.A11yActuator]），都是实打实的阻塞。Agent 循环本身跑在主线程
     * 上（ViewModel 的协程），在这里切一次，每个工具就不用各自操心了。
     */
    suspend fun execute(call: ToolCall): ToolOutput = withContext(Dispatchers.IO) {
        val tool = byName[call.name]
            ?: return@withContext ToolOutput(
                "ERROR unknown_tool: 没有名为 ${call.name} 的工具。" +
                    "可以用的是：${tools.joinToString("、") { it.name }}。",
            )
        try {
            val args = call.argumentsJson()
            if (tool is VisualTool) tool.executeVisual(args) else ToolOutput(tool.execute(args))
        } catch (e: CancellationException) {
            // 用户点了停止、页面销毁了：这时候再"想别的办法"就是不听话。原样抛出去。
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "工具 ${call.name} 执行出错", e)
            ToolOutput(
                "ERROR tool_crashed: ${call.name} 执行时出错了（${e.message}）。这是 Kitten 自己的问题，" +
                    "换一种方式试试，或者告诉用户这一步做不了。",
            )
        }
    }

    private companion object {
        const val TAG = "KittenAgent"
    }
}

/**
 * 一句话说明这次调用在干什么，给人看的（常驻通知上的进度、对话里的工具清单），不是给模型看的。
 *
 * 只从参数里挑用户看得懂的放进来（输入了什么、往哪边滚），节点编号这种只对模型有意义的不放。
 * 参数是模型给的，不保证齐全，缺了就退回不带参数的说法。
 */
fun ToolCall.describe(): String {
    val args = argumentsJson()
    return when (name) {
        "list_apps" -> args.optString("keyword").trim().takeIf { it.isNotEmpty() }
            ?.let { "查找应用「$it」" }
            ?: "查看已安装的应用"
        "launch_app" -> "打开 ${args.optString("package_name")}"
        "read_screen" -> "读取屏幕内容"
        "tap" -> "点击界面元素"
        "long_press" -> "长按界面元素"
        "input_text" -> args.optString("text").takeIf { it.isNotEmpty() }
            ?.let { "输入「${it.take(MaxQuotedChars)}${if (it.length > MaxQuotedChars) "…" else ""}」" }
            ?: "输入文字"
        "scroll" -> if (args.optString("direction").lowercase() == "backward") "往上滚动" else "往下滚动"
        "swipe" -> "滑动屏幕"
        "press_key" -> when (args.optString("key").lowercase()) {
            "back" -> "返回"
            "home" -> "回到桌面"
            "enter" -> "按回车"
            "delete" -> "按删除键"
            else -> "按 ${args.optString("key")} 键"
        }
        "screenshot" -> "截取屏幕画面"
        "screenshot_main_screen" -> "看一眼你的屏幕"
        "wait" -> "等待 ${args.optInt("seconds", 1)} 秒"
        "ask_user" -> "向你提问"
        else -> name
    }
}

/** 说明里引用用户输入的内容时最多带几个字，再长就省略：一行放不下，也没必要全写出来。 */
private const val MaxQuotedChars = 20
