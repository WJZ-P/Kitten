package me.wjz.kitten.ui.chat

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.wjz.kitten.agent.AgentProgress
import me.wjz.kitten.agent.AgentResult
import me.wjz.kitten.agent.AgentQuestion
import me.wjz.kitten.agent.AgentRunner
import me.wjz.kitten.agent.AgentTaskHandle
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.ChatCaller
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.SystemPrompt
import me.wjz.kitten.agent.ToolRegistry
import me.wjz.kitten.agent.tools.AskUserTool
import me.wjz.kitten.agent.tools.InputTextTool
import me.wjz.kitten.agent.tools.LaunchAppTool
import me.wjz.kitten.agent.tools.ListAppsTool
import me.wjz.kitten.agent.tools.LongPressTool
import me.wjz.kitten.agent.tools.MainScreenshotTool
import me.wjz.kitten.agent.tools.PressKeyTool
import me.wjz.kitten.agent.tools.ReadScreenTool
import me.wjz.kitten.agent.tools.ScreenshotTool
import me.wjz.kitten.agent.tools.ScrollTool
import me.wjz.kitten.agent.tools.SwipeTool
import me.wjz.kitten.agent.tools.TapTool
import me.wjz.kitten.agent.tools.UserAsker
import me.wjz.kitten.agent.tools.WaitTool
import me.wjz.kitten.agent.withInterruptedResults
import me.wjz.kitten.data.attachment.AttachmentStore
import me.wjz.kitten.data.session.SessionRepository
import me.wjz.kitten.data.session.sqlite.SqliteSessionRepository
import me.wjz.kitten.data.stats.TokenStatsStore
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.EndpointRepair
import me.wjz.kitten.llm.LlmConfigStore
import me.wjz.kitten.llm.LlmResult
import me.wjz.kitten.llm.createProvider
import me.wjz.kitten.service.AgentForegroundService
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

/**
 * 对话页的 ViewModel。
 *
 * ## 它为什么存在
 *
 * 界面只管画 [ChatUiState]，所有"怎么来的"都在这里：读写会话、调模型、处理失败。
 * 这样旋转屏幕、切到别的 tab 再回来，对话不会丢——ViewModel 活得比 Composable 久。
 *
 * ## 依赖的获取方式
 *
 * 现在是直接 new 出 [SqliteSessionRepository]。等依赖多起来（Agent 执行器、工具注册表）
 * 该换成依赖注入，但现在只有一个依赖，引 Hilt 不划算。
 *
 * 注意它只认 [SessionRepository] 接口——底层是手写 SQLite 还是将来的 Room，
 * 这里不知道也不关心。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SessionRepository = SqliteSessionRepository(application)
    private val attachmentStore = AttachmentStore(application)

    /** 正在等的那个回答。Agent 调 ask_user 时挂上，用户答了或者任务被取消时清掉。 */
    private var pendingAnswer: CancellableContinuation<String>? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        observeSessions()
        refreshModelConfig()
    }

    /** 侧边栏列表跟着库走，任何地方改了会话这里都会自动更新。 */
    private fun observeSessions() {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _uiState.update { it.copy(sessions = sessions) }
            }
        }
    }

    /**
     * 重新读模型配置：配没配好，以及输入栏上模型菜单里列哪些。
     *
     * 设置页改完配置切回来要调一次——配置存在 SharedPreferences 里，没有变更通知。
     */
    fun refreshModelConfig() {
        val profiles = LlmConfigStore.loadProfiles(getApplication())
        val usable = profiles.active?.takeIf { it.isConfigured }
        _uiState.update { state ->
            state.copy(
                isModelConfigured = usable != null,
                modelGroups = profiles.profiles
                    .filter { it.apiKey.isNotBlank() && it.modelChoices.isNotEmpty() }
                    .map { ModelGroup(profileId = it.id, name = it.displayName, models = it.modelChoices) },
                activeProfileId = usable?.id,
                activeModel = usable?.model,
            )
        }
    }

    /**
     * 输入栏上切换模型：[profileId] 那一家设为当前，它选中的模型换成 [model]。
     *
     * 写的是和设置页同一份配置，设置页看着 [LlmConfigStore.revision]，会跟着重读。
     *
     * 下一次发送才用新模型：一轮任务开始时就把配置读好了，中途不换（切换按钮在回复期间也是灰的）。
     */
    fun selectModel(profileId: String, model: String) {
        val context = getApplication<Application>()
        val current = LlmConfigStore.loadProfiles(context)
        if (current.profiles.none { it.id == profileId }) return
        LlmConfigStore.saveProfiles(
            context,
            current.copy(
                profiles = current.profiles.map { if (it.id == profileId) it.copy(model = model) else it },
                activeId = profileId,
            ),
        )
        refreshModelConfig()
    }

    /** 开一个新会话。当前会话不会被删，只是不再显示。 */
    fun startNewSession() {
        _uiState.update {
            it.copy(
                sessionId = null,
                title = "",
                messages = emptyList(),
                streamingReply = null,
                agentProgress = null,
                error = null,
            )
        }
    }

    /** 从侧边栏打开一个历史会话。 */
    fun openSession(id: String) {
        viewModelScope.launch {
            val session = repository.load(id)
            if (session == null) {
                _uiState.update { it.copy(error = "这个会话已经不存在了") }
                return@launch
            }
            _uiState.update {
                it.copy(
                    sessionId = session.id,
                    title = session.title,
                    messages = session.messages,
                    streamingReply = null,
                    agentProgress = null,
                    error = null,
                )
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.delete(id)
            // 删掉的正好是当前打开的，就退回空白状态。
            if (_uiState.value.sessionId == id) startNewSession()
        }
    }

    /**
     * 删掉当前会话里的一条消息。
     *
     * 消息在领域模型里没有 id，按位置删：[index] 是它在 messages 里的位置，[message] 是长按
     * 那一刻看到的那条，对不上就不删（比如这期间换了会话），宁可不删也不能删错。
     *
     * 模型那条带着工具调用的话，对应的工具结果一起删：留着没人认领的工具结果，下次发给模型
     * 会被拒（tool_result 找不到对应的 tool_use）。
     *
     * 删完以后对话可能以模型的回复开头、或者两条同一方的消息挨在一起，这些在组请求时处理，
     * 见 AnthropicProvider 的 buildMessages。
     */
    fun deleteMessage(index: Int, message: ChatMessage) {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        if (state.messages.getOrNull(index) != message) return

        val callIds = (message as? ChatMessage.Assistant)?.toolCalls?.map { it.id }?.toSet().orEmpty()
        val doomed = state.messages.indices.filter { i ->
            i == index || (state.messages[i] as? ChatMessage.ToolResult)?.callId in callIds
        }.toSet()
        _uiState.update { it.copy(messages = it.messages.filterIndexed { i, _ -> i !in doomed }) }
        viewModelScope.launch { repository.deleteMessages(sessionId, doomed) }
    }

    /**
     * 发送一条用户消息，并把模型的回复接回来。
     *
     * 这里还是**纯对话**，没有接工具调用——Agent 循环（模型要求执行工具 → 我们执行 →
     * 结果回灌 → 模型继续）是下一步的事。现在先把"输入 → 模型 → 显示"这条链路跑通。
     *
     * ## 流式
     *
     * 回复是流式拿的：字一段段冒出来，先攒在 [ChatUiState.streamingReply] 里，界面把它画成一条
     * 临时的回复；整条回复完了，在同一次状态更新里清掉它、换成真正的消息（这时才落库），界面上
     * 是同一个位置的同一个气泡，不会闪。失败了临时的那条直接丢掉，和以前一样不入库。
     *
     * 回复期间用户切到了别的会话：字照样收完、照样存进原来那个会话，只是不往眼前这个会话里画。
     *
     * ## 图片
     *
     * [images] 是相册给的地址，只是临时授权。发之前先复制、压缩一份进应用私有目录（见
     * [AttachmentStore]），消息里存的是这一份。读不出来的那几张不带，用一条提示告诉用户。
     * 可以只发图片不打字。
     */
    fun send(text: String, images: List<Uri> = emptyList()) {
        val content = text.trim()
        val state = _uiState.value
        // 被拦下时都打一行，说清楚是哪一关、当时的状态：发不出去的时候，光看界面分不出原因。
        // 只记状态，不记用户打的内容。
        if (content.isEmpty() && images.isEmpty()) {
            Log.d(TAG, "send 被拦下：内容是空的")
            return
        }
        if (!state.canSend) {
            Log.w(
                TAG,
                "send 被拦下：isModelConfigured=${state.isModelConfigured} isResponding=${state.isResponding} " +
                    "pendingQuestion=${state.pendingQuestion != null}",
            )
            return
        }

        // Agent 正等着用户回答时，输入框里打的就是答案，不是一条新消息。
        if (state.pendingQuestion != null) {
            if (content.isNotEmpty()) answerQuestion(content)
            return
        }
        Log.i(TAG, "开始一轮：历史 ${state.messages.size} 条，带 ${images.size} 张图")

        // 常驻通知和"停止"按钮要在任务真正开始之前就备好——用户可能第一秒就想喊停。
        // 停止回调里要用的 Job 这会儿还没有，先留个可变引用，launch 之后再填上。
        var running: Job? = null
        AgentTaskHandle.begin(onStop = { running?.cancel() }, onAnswer = ::answerQuestion)
        AgentForegroundService.start(getApplication())

        val job = viewModelScope.launch {
            val attachments = images.mapNotNull { attachmentStore.importImage(it) }
            val dropped = images.size - attachments.size
            if (dropped > 0) {
                _uiState.update { it.copy(error = "有 $dropped 张图片读不出来，没有发出去") }
            }
            if (content.isEmpty() && attachments.isEmpty()) return@launch

            val userMessage = ChatMessage.User(content, attachments)
            // 先落库再请求：请求可能失败或超时，但用户说过的话不该因此丢掉。
            val sessionId = ensureSession(content.ifEmpty { IMAGE_ONLY_TITLE })
            repository.appendMessage(sessionId, userMessage)
            _uiState.update {
                it.copy(
                    sessionId = sessionId,
                    messages = it.messages + userMessage,
                    isResponding = true,
                    error = null,
                )
            }

            // 这一段回复已经冒出来、还没成为正式消息的字。用户中途喊停时把它留下来（见下面的 catch），
            // 单独记一份而不是读界面状态：用户可能已经切到别的会话去了。
            var streamed = ""

            // 新冒出来的一段字接到临时回复后面。用户切到了别的会话就不画。
            fun onTextDelta(delta: String) {
                streamed += delta
                _uiState.update {
                    if (it.sessionId != sessionId) it else it.copy(streamingReply = (it.streamingReply ?: "") + delta)
                }
            }

            // 上次被喊停时没等到结果的工具调用，补上"被中断"的结果再发，不然请求会被拒，见
            // withInterruptedResults。
            val history = _uiState.value.messages.withInterruptedResults()
            // 这一轮的工具和现场。用完要 release，副屏得收掉。
            val device = DeviceSession(getApplication())
            val registry = ToolRegistry(deviceTools(device))

            var config = LlmConfigStore.load(getApplication())
            // 这一轮开始时读一次，整轮用同一份：中途变了会让 thinking 块失效，见 SystemPrompt。
            val systemPrompt = SystemPrompt.load(getApplication())
            var repairedUrl: String? = null
            var firstCall = true

            // 发请求这件事交给这里，Agent 循环不用管配置和补 /v1。
            val caller = ChatCaller { messages, tools, onDelta ->
                var result = config.createProvider().chat(systemPrompt, messages, tools, onDelta)
                // 地址像是少拼了 /v1：补上试一下，通了就改掉设置、重发。只在第一次调用时试——
                // 后面几步地址已经是修好的了，再失败就是别的原因。
                val failed = result as? LlmResult.Failed
                if (firstCall && failed != null && EndpointRepair.isWrongPath(failed.error)) {
                    EndpointRepair.tryAppendV1(config)?.let { repaired ->
                        LlmConfigStore.updateActiveBaseUrl(getApplication(), repaired)
                        config = config.copy(baseUrl = repaired)
                        repairedUrl = repaired
                        // 重发之前把已经冒出来的半截字清掉，不然会接在后面重复一遍。
                        streamed = ""
                        _uiState.update { it.copy(streamingReply = null) }
                        result = config.createProvider().chat(systemPrompt, messages, tools, onDelta)
                    }
                }
                firstCall = false
                // 记进每天的用量账（设置页的看板）。服务没报用量的就记不上。
                (result as? LlmResult.Ok)?.response?.usage?.let { TokenStatsStore.record(getApplication(), it) }
                result
            }

            val outcome = try {
                AgentRunner(registry).run(
                    caller = caller,
                    history = history,
                    onTextDelta = ::onTextDelta,
                    onMessage = { message ->
                        // 冒出来的字已经变成正式消息了，下一段从头记。
                        if (message is ChatMessage.Assistant) streamed = ""
                        // 模型说完一段话，常驻通知的正文换成它，用户不在 Kitten 里也看得到。
                        (message as? ChatMessage.Assistant)?.text?.trim()?.takeIf { it.isNotEmpty() }
                            ?.let(AgentTaskHandle::say)
                        // 存进历史的是"瘦身版"：thinking 块不落库、超长的工具结果截短，
                        // 见 forHistory。正在跑的这一轮用的是 AgentRunner 自己那份全量的。
                        val stored = message.forHistory()
                        repository.appendMessage(sessionId, stored)
                        _uiState.update {
                            if (it.sessionId != sessionId) {
                                it
                            } else {
                                it.copy(
                                    // 临时回复和真正的消息在同一次更新里交接，界面上不会空一下再冒出来。
                                    messages = it.messages + stored,
                                    streamingReply = null,
                                )
                            }
                        }
                    },
                    onProgress = { progress ->
                        val label = when (progress) {
                            is AgentProgress.Thinking -> null
                            is AgentProgress.RunningTool -> progress.detail
                        }
                        AgentTaskHandle.update(label)
                        _uiState.update {
                            if (it.sessionId != sessionId) it else it.copy(agentProgress = label)
                        }
                    },
                )
            } catch (e: CancellationException) {
                // 用户喊停了。已经冒出来的半截回复留下来当一条正式消息，和别的聊天应用一样——用户
                // 看着字一个个冒出来再点的停止，点完字全没了会以为出了错。协程已经被取消，落库
                // 得放进 NonCancellable，不然一挂起就直接抛出来。
                val partial = streamed.trim()
                if (partial.isNotEmpty()) {
                    withContext(NonCancellable) {
                        val stored = ChatMessage.Assistant(text = partial)
                        repository.appendMessage(sessionId, stored)
                        _uiState.update {
                            if (it.sessionId != sessionId) it else it.copy(messages = it.messages + stored, streamingReply = null)
                        }
                    }
                }
                throw e
            } finally {
                // 不管成功、失败还是被取消，副屏都要收掉。
                device.release()
            }

            val repaired = repairedUrl
            val failure = when (outcome) {
                is AgentResult.Done -> null
                is AgentResult.Failed -> {
                    Log.w(TAG, "模型调用失败：${outcome.error.describe()}")
                    // 补上 /v1 之后还是失败（比如 Key 的问题）：地址已经改了，要在同一条提示里说，
                    // 不然另弹一条会被这条错误马上顶掉。
                    if (repaired != null) {
                        "已把 API 地址自动补上 /v1，但请求还是失败了：${outcome.error.describe()}"
                    } else {
                        outcome.error.describe()
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isResponding = false,
                    // 失败时冒出来的那半截字一起丢掉，它没有落库。
                    streamingReply = null,
                    agentProgress = null,
                    error = failure,
                    notice = repaired?.takeIf { failure == null }?.let { url -> "API 地址少了 /v1，已经自动补上：$url" },
                )
            }
        }

        running = job
        // 不管是正常跑完、出错，还是被用户在通知上喊停，都要收掉常驻通知、把界面从"回复中"
        // 放出来。被取消时上面那段代码根本走不到，只能在这里兜。
        job.invokeOnCompletion {
            AgentTaskHandle.end()
            _uiState.update {
                it.copy(
                    isResponding = false,
                    streamingReply = null,
                    agentProgress = null,
                    pendingQuestion = null,
                )
            }
        }
    }

    /**
     * Agent 问用户问题的通道，[AskUserTool] 用它。
     *
     * 挂起等回答：问题既显示成对话里的一张卡片，也显示在常驻通知上（用户可能正在别的应用里），
     * 两边答都算数，先到先得。
     */
    private val asker = UserAsker { question, options ->
        suspendCancellableCoroutine { continuation ->
            pendingAnswer = continuation
            val pending = AgentQuestion(question, options)
            _uiState.update { it.copy(pendingQuestion = pending) }
            AgentTaskHandle.ask(pending)
            // 任务被取消（用户喊停、页面销毁）时，问题也要跟着收掉，不然卡片会一直挂着。
            continuation.invokeOnCancellation {
                pendingAnswer = null
                _uiState.update { it.copy(pendingQuestion = null) }
                AgentTaskHandle.clearQuestion()
            }
        }
    }

    /**
     * 停下正在跑的这一轮：输入栏上的停止按钮。和常驻通知上的"停止"走同一条路——取消这一轮的
     * 协程（见 send 里交给 AgentTaskHandle 的 onStop），后面的收尾（副屏收掉、通知收掉、界面
     * 放出来、半截回复留下）都在 send 里。
     */
    fun stop() {
        AgentTaskHandle.requestStop()
    }

    /** 用户回答了 Agent 的提问：对话里点了选项、通知上点了按钮、或者直接在输入框里打字。 */
    fun answerQuestion(answer: String) {
        val continuation = pendingAnswer ?: run {
            // 界面上以为在等回答、这里却没有在等的：两边对不上了，打出来方便查。
            Log.w(TAG, "answerQuestion 被丢掉：没有正在等的问题（pendingQuestion=${_uiState.value.pendingQuestion != null}）")
            return
        }
        pendingAnswer = null
        _uiState.update { it.copy(pendingQuestion = null) }
        AgentTaskHandle.clearQuestion()
        continuation.resume(answer)
    }

    /**
     * 确保有一个会话可以写入，没有就用首句话建一个。
     *
     * 会话不是一进页面就建的——那样用户每次打开 app 都会多出一条空会话。
     * 真正发出第一条消息时才建，标题也顺手用那句话。
     */
    private suspend fun ensureSession(firstMessage: String): String {
        _uiState.value.sessionId?.let { return it }
        val title = firstMessage.take(TITLE_MAX_LENGTH).replace('\n', ' ')
        val session = repository.create(title)
        _uiState.update { it.copy(title = session.title) }
        return session.id
    }

    /**
     * 这一轮能用的工具。顺序就是给模型看的顺序，按"先查、再开、再看、最后动手"排。
     *
     * 每轮任务新建一份：它们共享同一个 [DeviceSession]（在哪块屏、最近看到的界面）。
     */
    private fun deviceTools(device: DeviceSession): List<AgentTool> = listOf(
        ListAppsTool(getApplication()),
        LaunchAppTool(device),
        ReadScreenTool(device),
        TapTool(device),
        LongPressTool(device),
        InputTextTool(device),
        ScrollTool(device),
        SwipeTool(device),
        PressKeyTool(device),
        ScreenshotTool(device),
        MainScreenshotTool(device),
        WaitTool(),
        AskUserTool(asker),
    )

    /**
     * 存进历史的形态。
     *
     * 两件事：thinking 块不落库（它绑定着产生它的那段历史，而用户可以删消息，留着迟早失效，
     * 见 [me.wjz.kitten.llm.ReasoningBlock]）；超长的工具结果截短（见 [MAX_STORED_TOOL_RESULT]）。
     *
     * 界面状态里存的也是这一份，和下次从数据库读出来的保持一致——不然同一个会话"这次"和
     * "下次"发给模型的内容会不一样，出了问题很难查。
     */
    private fun ChatMessage.forHistory(): ChatMessage = when (this) {
        is ChatMessage.Assistant -> if (reasoning.isEmpty()) this else copy(reasoning = emptyList())
        is ChatMessage.ToolResult -> {
            val text = if (content.length <= MAX_STORED_TOOL_RESULT) {
                content
            } else {
                content.take(MAX_STORED_TOOL_RESULT) + "\n…（这次结果很长，历史里只留了开头）"
            }
            // 截图存在缓存目录，任务一结束就删了，历史里留着路径只会指向一个不存在的文件。
            copy(content = text, images = emptyList())
        }
        is ChatMessage.User -> this
    }

    /** 错误提示展示过就清掉，否则切回这个页面会再弹一次。 */
    fun consumeError() {
        _uiState.update { it.copy(error = null) }
    }

    /** 普通提示同理。 */
    fun consumeNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    private companion object {
        const val TAG = "KittenChat"
        const val TITLE_MAX_LENGTH = 20

        /** 第一条消息只有图片、没有字时，会话的标题。 */
        const val IMAGE_ONLY_TITLE = "图片"

        /**
         * 工具结果存进历史时最多留多少字。
         *
         * 界面树一抓就是几千字符，十几步下来几十 KB。这些在**当前这一轮**里有用（模型要照着
         * 它决定下一步），但下次打开这个会话再全量发给模型就纯属烧钱——昨天那个页面长什么样
         * 对今天的问题没有意义。所以落库时截短，正在跑的那一轮用的是全量的那份。
         */
        const val MAX_STORED_TOOL_RESULT = 400
    }
}
