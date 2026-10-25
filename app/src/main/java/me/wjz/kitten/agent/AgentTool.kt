package me.wjz.kitten.agent

import me.wjz.kitten.llm.ImageAttachment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 能调用的一个工具。
 *
 * ## 为什么失败也返回文本
 *
 * [execute] 不抛异常、也不返回成功/失败：**返回值整个是给模型看的**。工具失败时模型要据此
 * 决定下一步（重新抓屏？换个节点？告诉用户做不到？），一个异常或者 false 它什么都推不出来。
 * 所以失败也要写成一句说得清的话，和 [me.wjz.kitten.a11y.ScreenError.describe] 一个路子。
 *
 * 真正的程序错误（空指针之类）由 [ToolRegistry] 兜住，同样转成文本交给模型，不让一个工具的
 * bug 把整轮任务打断。
 */
interface AgentTool {

    /** 工具名，模型按这个名字调用。小写加下划线，和两家接口的惯例一致。 */
    val name: String

    /**
     * 这个工具是干什么的、什么时候该用、有什么前提。模型只能看到这段话，写含糊了它就用不对。
     */
    val description: String

    /** 参数的 JSON Schema。没有参数时是一个空的 object schema，见 [noParams]。 */
    val parameters: JSONObject

    /**
     * 执行。[args] 是模型给的参数，**不保证符合 schema**——模型会漏字段、给错类型，所以取
     * 参数时一律给默认值或者自己检查，别直接 getString。
     */
    suspend fun execute(args: JSONObject): String
}

/** 工具的产出：给模型看的文字，外加可能的图片。 */
data class ToolOutput(
    val text: String,
    val images: List<ImageAttachment> = emptyList(),
)

/**
 * 会连图片一起返回的工具。
 *
 * 绝大多数工具只吐文字，实现 [AgentTool] 就够了；只有截图这类要把画面交给模型的才实现这个。
 * 分成两个接口是为了不让十来个纯文字工具都被迫把返回值包一层 [ToolOutput]——那层包装在它们
 * 身上没有任何信息量，只会把代码变吵。
 */
interface VisualTool : AgentTool {

    suspend fun executeVisual(args: JSONObject): ToolOutput

    /** 只要文字的场合（理论上不会走到，[ToolRegistry] 认得出 [VisualTool]）。 */
    override suspend fun execute(args: JSONObject): String = executeVisual(args).text
}

// ---- 写 JSON Schema 的几个小工具。手拼 JSONObject 太吵，schema 本身的结构会被淹没。----

/** 没有参数的工具用它。两家接口都要求 parameters 是个合法的 object schema，不能省。 */
fun noParams(): JSONObject = objectSchema()

/**
 * 一个 object schema。
 *
 * @param required 必填的参数名。不在这里面的就是可选的，模型可以不传。
 */
fun objectSchema(
    vararg properties: Pair<String, JSONObject>,
    required: List<String> = emptyList(),
): JSONObject = JSONObject().apply {
    put("type", "object")
    put("properties", JSONObject().apply { properties.forEach { (key, schema) -> put(key, schema) } })
    put("required", JSONArray(required))
}

fun stringParam(description: String): JSONObject =
    JSONObject().put("type", "string").put("description", description)

fun intParam(description: String): JSONObject =
    JSONObject().put("type", "integer").put("description", description)

fun boolParam(description: String): JSONObject =
    JSONObject().put("type", "boolean").put("description", description)

/** 一串字符串。 */
fun stringArrayParam(description: String): JSONObject =
    JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string"))

/** 取值只能是 [values] 里的几个之一。模型乱传参数最常见的就是这类字符串，用 enum 收住。 */
fun enumParam(description: String, values: List<String>): JSONObject =
    JSONObject()
        .put("type", "string")
        .put("description", description)
        .put("enum", JSONArray(values))
