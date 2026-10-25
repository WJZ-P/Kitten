package me.wjz.kitten.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Agent 要用户回答的一个问题。[options] 为空时表示要用户自己打字回答。 */
data class AgentQuestion(
    val question: String,
    val options: List<String> = emptyList(),
)

/** 正在跑的那一轮任务，给常驻通知看。 */
data class AgentTask(
    /** 当前这一步在干什么，一句人话（见 [describe]）。刚开始、正在等模型时是 null。通知的标题。 */
    val detail: String? = null,
    /**
     * 模型最近说的那段话（边做边念叨的那种，见默认人设）。还没开口时是 null。通知的正文：用户
     * 不在 Kitten 里时，下拉通知栏也看得到它在想什么。
     */
    val lastSaid: String? = null,
    /** 正在等用户回答的问题。没有就是 null。 */
    val question: AgentQuestion? = null,
)

/**
 * 正在跑的那一轮任务的"把手"：常驻通知靠它知道该显示什么，用户点通知上的按钮时靠它把动作
 * 送回正在跑的协程。
 *
 * ## 为什么是进程级单例
 *
 * 通知的按钮点下去，进来的是 Service 的 onStartCommand，那里既拿不到 ViewModel 也拿不到
 * 正在跑的协程。中间需要一个两边都够得着的地方，这就是它。
 *
 * 同一时刻只会有一轮任务（界面上不允许同时发两条），所以不做多任务管理——真要支持了再说，
 * 现在加 id 和表只是凭空的复杂度。
 */
object AgentTaskHandle {

    private val _task = MutableStateFlow<AgentTask?>(null)

    /** 不为 null 表示有任务在跑。Service 盯着它更新通知，变回 null 就自己停掉。 */
    val task: StateFlow<AgentTask?> = _task.asStateFlow()

    private var onStop: (() -> Unit)? = null
    private var onAnswer: ((String) -> Unit)? = null

    /**
     * 一轮任务开始。
     *
     * @param onStop 用户点了通知上的"停止"。
     * @param onAnswer 用户在通知上选了某个选项。
     */
    fun begin(onStop: () -> Unit, onAnswer: (String) -> Unit) {
        this.onStop = onStop
        this.onAnswer = onAnswer
        _task.value = AgentTask()
    }

    /** 换了一步。[detail] 见 [AgentTask.detail]。 */
    fun update(detail: String?) {
        _task.update { it?.copy(detail = detail) }
    }

    /**
     * 模型说了一段话，见 [AgentTask.lastSaid]。
     *
     * 只在一整段说完时调，不跟着流式一个字一个字地调：系统对一个应用刷通知的频率有上限，刷太快
     * 的更新会被直接丢掉。
     */
    fun say(text: String) {
        _task.update { it?.copy(lastSaid = text) }
    }

    fun ask(question: AgentQuestion) {
        _task.update { it?.copy(question = question) }
    }

    fun clearQuestion() {
        _task.update { it?.copy(question = null) }
    }

    /** 一轮任务结束（正常结束、失败、被停掉都算）。 */
    fun end() {
        _task.value = null
        onStop = null
        onAnswer = null
    }

    fun requestStop() {
        onStop?.invoke()
    }

    fun answer(option: String) {
        onAnswer?.invoke(option)
    }
}
