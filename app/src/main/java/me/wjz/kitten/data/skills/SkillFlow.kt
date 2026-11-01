package me.wjz.kitten.data.skills

import org.json.JSONArray
import org.json.JSONObject

/**
 * 技能里的一个自动流程：一串记下来的操作，调一下就能原样点完（见 FlowRunner），不用模型一步步想。
 *
 * 比如"京东"技能里的「搜索商品」：打开应用 → 点搜索框 → 输入 {keyword} → 点搜索。模型只要调一次
 * run_flow、给出关键词，剩下的由程序照着做，几秒钟、不花 token。
 *
 * ## 从哪来
 *
 * 不是模型凭空写的：平时 Agent 每做一步操作，都会记下这一步和它点的那个节点的选择器（见
 * ActionTrace）；一件事办完，复盘时从这份记录里挑跑通的那一段存成流程（见 SaveFlowTool）。所以
 * 流程里的每一步都是真点成功过的。
 *
 * ## 参数
 *
 * 每次会变的输入写成 `{名字}`（[FlowStep.Input.text] 里），运行时再填，见 [params]。
 *
 * @param name 流程名，一个技能里不重复，模型按它调。
 * @param description 这个流程办什么事、跑完停在哪，给模型挑流程时看。
 * @param runs 跑过几次（跑完和卡住的都算）。
 * @param failures 其中卡住了几次。复盘时看得到，老卡住的流程该修了。
 */
data class SkillFlow(
    val name: String,
    val description: String,
    val steps: List<FlowStep>,
    val runs: Int = 0,
    val failures: Int = 0,
) {
    /** 这个流程要哪些参数：输入步骤里的 `{名字}`，按出现的先后，不重复。 */
    val params: List<String>
        get() = steps.filterIsInstance<FlowStep.Input>().flatMap { placeholdersIn(it.text) }.distinct()

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("desc", description)
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
        .put("runs", runs)
        .put("fails", failures)

    companion object {
        /** 流程名最长几个字。 */
        const val MAX_NAME_LENGTH = 20

        /** 说明最长几个字：一两句话。 */
        const val MAX_DESCRIPTION_LENGTH = 100

        /** 一个流程最多几步。再长的事拆成几个流程，卡住时也好知道是哪一段。 */
        const val MAX_STEPS = 50

        /** 一个技能里最多几个流程：每个都要列进系统提示词。 */
        const val MAX_PER_SKILL = 20

        /** 解析不了的（没名字、一步都读不出来）返回 null。 */
        fun fromJson(json: JSONObject?): SkillFlow? {
            json ?: return null
            val name = json.optString("name").takeIf { it.isNotBlank() } ?: return null
            val array = json.optJSONArray("steps") ?: return null
            val steps = (0 until array.length()).mapNotNull { FlowStep.fromJson(array.optJSONObject(it)) }
            // 有一步读不出来（比如以后的版本加了新动作、又退回旧版本）整个流程就不能用了：缺一步照着
            // 跑只会点歪。
            if (steps.size != array.length() || steps.isEmpty()) return null
            return SkillFlow(
                name = name,
                description = json.optString("desc"),
                steps = steps,
                runs = json.optInt("runs"),
                failures = json.optInt("fails"),
            )
        }

        /** [text] 里的参数名：`{keyword}` 里的 keyword。 */
        fun placeholdersIn(text: String): List<String> =
            PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toList()

        /** 把 [text] 里的 `{名字}` 换成 [values] 里给的值。没给的原样留着，调用方先检查过参数齐不齐。 */
        fun fill(text: String, values: Map<String, String>): String =
            PLACEHOLDER.replace(text) { match -> values[match.groupValues[1]] ?: match.value }

        /** 参数占位符：花括号里一到二十个不是空白、不是花括号的字。 */
        private val PLACEHOLDER = Regex("""\{([^{}\s]{1,20})\}""")
    }
}

/**
 * 流程里的一步。动作只有这几种，都是 Agent 本来就有的工具能做的事（点、长按、输入、滚动、滑动、
 * 按键、打开应用、等一会儿），由程序解释执行；没有"执行一段代码"这种步骤，存进来的流程再怎么写
 * 也越不出这几个动作。
 *
 * 要找节点的那几步存的是选择器（[selector]，见 me.wjz.kitten.a11y.selector.Selector），不是节点编号：
 * 编号每抓一次屏都会变。[label] 是记录时那个节点上的字，只给人看（设置页、复盘），执行时不用。
 */
sealed interface FlowStep {

    /** 目标节点上的字，给人看。不找节点的步骤是 null。 */
    val label: String?

    /** 一句人话，设置页和复盘时显示。 */
    fun describe(): String

    fun toJson(): JSONObject

    data class Launch(val packageName: String) : FlowStep {
        override val label: String? get() = null
        override fun describe() = "打开 $packageName"
        override fun toJson(): JSONObject = JSONObject().put("op", "launch").put("pkg", packageName)
    }

    data class Tap(val selector: String, override val label: String?) : FlowStep {
        override fun describe() = "点${quoted(label)}"
        override fun toJson(): JSONObject = node("tap", selector, label)
    }

    data class LongPress(val selector: String, override val label: String?) : FlowStep {
        override fun describe() = "长按${quoted(label)}"
        override fun toJson(): JSONObject = node("long_press", selector, label)
    }

    /**
     * @param text 要写进去的内容，可以带 `{参数}`。
     * @param secret 往密码框里输的：内容照样存、照样填（自动登录要用），但 [describe] 不写出来——这句话会
     *   发给复盘的模型、显示在设置页和常驻通知上，密码不能跟着出去。
     */
    data class Input(
        val selector: String,
        val text: String,
        override val label: String?,
        val secret: Boolean = false,
    ) : FlowStep {
        override fun describe() = if (secret) "在${quoted(label)}里输入密码（已隐藏）" else "在${quoted(label)}里输入「$text」"
        override fun toJson(): JSONObject =
            node("input", selector, label).put("text", text).apply { if (secret) put("secret", true) }
    }

    data class Scroll(val selector: String, val forward: Boolean, override val label: String?) : FlowStep {
        override fun describe() = "把${quoted(label)}往${if (forward) "后" else "前"}滚一屏"
        override fun toJson(): JSONObject = node("scroll", selector, label).put("forward", forward)
    }

    /** 按坐标滑。同一台手机上屏幕尺寸不变，坐标照样能用。 */
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Int) : FlowStep {
        override val label: String? get() = null
        override fun describe() = "从 ($fromX,$fromY) 滑到 ($toX,$toY)"
        override fun toJson(): JSONObject = JSONObject()
            .put("op", "swipe")
            .put("x1", fromX).put("y1", fromY).put("x2", toX).put("y2", toY)
            .put("ms", durationMs)
    }

    /** @param key back、home、enter、delete 之一，和 press_key 工具一样。 */
    data class Key(val key: String) : FlowStep {
        override val label: String? get() = null
        override fun describe() = when (key) {
            "back" -> "按返回"
            "home" -> "回到桌面"
            "enter" -> "按回车"
            "delete" -> "按删除键"
            else -> "按 $key"
        }
        override fun toJson(): JSONObject = JSONObject().put("op", "key").put("key", key)
    }

    data class Wait(val seconds: Int) : FlowStep {
        override val label: String? get() = null
        override fun describe() = "等 $seconds 秒"
        override fun toJson(): JSONObject = JSONObject().put("op", "wait").put("s", seconds)
    }

    companion object {
        /** 读不出来（动作不认识、少了字段）返回 null。 */
        fun fromJson(json: JSONObject?): FlowStep? {
            json ?: return null
            val selector = json.optString("sel")
            val label = json.optString("label").takeIf { it.isNotEmpty() }
            return when (json.optString("op")) {
                "launch" -> json.optString("pkg").takeIf { it.isNotBlank() }?.let(::Launch)
                "tap" -> selector.takeIf { it.isNotBlank() }?.let { Tap(it, label) }
                "long_press" -> selector.takeIf { it.isNotBlank() }?.let { LongPress(it, label) }
                "input" -> selector.takeIf { it.isNotBlank() && json.has("text") }
                    ?.let { Input(it, json.optString("text"), label, secret = json.optBoolean("secret")) }
                "scroll" -> selector.takeIf { it.isNotBlank() }?.let { Scroll(it, json.optBoolean("forward", true), label) }
                "swipe" -> Swipe(
                    json.optInt("x1"), json.optInt("y1"), json.optInt("x2"), json.optInt("y2"),
                    json.optInt("ms", 300),
                )
                "key" -> json.optString("key").takeIf { it.isNotBlank() }?.let(::Key)
                "wait" -> Wait(json.optInt("s", 1).coerceIn(1, 10))
                else -> null
            }
        }

        private fun node(op: String, selector: String, label: String?): JSONObject = JSONObject()
            .put("op", op)
            .put("sel", selector)
            .apply { if (label != null) put("label", label) }

        private fun quoted(label: String?): String = label?.let { "「$it」" } ?: "一个元素"
    }
}

/** 要找节点的那几步存的选择器，别的步骤是 null。 */
val FlowStep.targetSelector: String?
    get() = when (this) {
        is FlowStep.Tap -> selector
        is FlowStep.LongPress -> selector
        is FlowStep.Input -> selector
        is FlowStep.Scroll -> selector
        is FlowStep.Launch, is FlowStep.Swipe, is FlowStep.Key, is FlowStep.Wait -> null
    }
