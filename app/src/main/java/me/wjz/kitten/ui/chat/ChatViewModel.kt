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
import kotlinx.coroutines.launch
import me.wjz.kitten.data.attachment.AttachmentStore
import me.wjz.kitten.data.session.SessionRepository
import me.wjz.kitten.data.session.sqlite.SqliteSessionRepository
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.EndpointRepair
import me.wjz.kitten.llm.LlmConfigStore
import me.wjz.kitten.llm.LlmResult
import me.wjz.kitten.llm.createProvider

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
     * 重新读模型配置。
     *
     * 设置页改完配置切回来要调一次——配置存在 SharedPreferences 里，没有变更通知。
     */
    fun refreshModelConfig() {
        val configured = LlmConfigStore.load(getApplication()).isConfigured
        _uiState.update { it.copy(isModelConfigured = configured) }
    }

    /** 开一个新会话。当前会话不会被删，只是不再显示。 */
    fun startNewSession() {
        _uiState.update {
            it.copy(sessionId = null, title = "", messages = emptyList(), streamingReply = null, error = null)
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
        if ((content.isEmpty() && images.isEmpty()) || !_uiState.value.canSend) return

        viewModelScope.launch {
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

            // 新冒出来的一段字接到临时回复后面。用户切到了别的会话就不画。
            fun onTextDelta(delta: String) {
                _uiState.update {
                    if (it.sessionId != sessionId) it else it.copy(streamingReply = (it.streamingReply ?: "") + delta)
                }
            }

            val history = _uiState.value.messages
            var config = LlmConfigStore.load(getApplication())
            var result = config.createProvider().chat(SYSTEM_PROMPT, history, onTextDelta = ::onTextDelta)

            // 地址像是少拼了 /v1：补上试一下，通了就直接改掉设置、用新地址重发这一条。见 EndpointRepair。
            // 这种失败发生在回复开始之前，还没有字冒出来，直接重发就行。
            val failed = result as? LlmResult.Failed
            val repairedUrl = if (failed != null && EndpointRepair.isWrongPath(failed.error)) {
                EndpointRepair.tryAppendV1(config)
            } else {
                null
            }
            if (repairedUrl != null) {
                LlmConfigStore.updateActiveBaseUrl(getApplication(), repairedUrl)
                config = config.copy(baseUrl = repairedUrl)
                _uiState.update { it.copy(streamingReply = null) }
                result = config.createProvider().chat(SYSTEM_PROMPT, history, onTextDelta = ::onTextDelta)
            }

            when (result) {
                is LlmResult.Ok -> {
                    val reply = ChatMessage.Assistant(
                        text = result.response.text,
                        toolCalls = result.response.toolCalls,
                    )
                    repository.appendMessage(sessionId, reply)
                    _uiState.update {
                        it.copy(
                            // 临时回复和真正的消息在同一次更新里交接，界面上不会空一下再冒出来。
                            messages = if (it.sessionId == sessionId) it.messages + reply else it.messages,
                            streamingReply = null,
                            isResponding = false,
                            notice = repairedUrl?.let { url -> "API 地址少了 /v1，已经自动补上：$url" },
                        )
                    }
                }

                is LlmResult.Failed -> {
                    Log.w(TAG, "模型调用失败：${result.error.describe()}")
                    // 补上 /v1 之后还是失败（比如 Key 的问题）：地址已经改了，要在同一条提示里说，
                    // 不然另弹一条会被这条错误马上顶掉。
                    val message = if (repairedUrl != null) {
                        "已把 API 地址自动补上 /v1，但请求还是失败了：${result.error.describe()}"
                    } else {
                        result.error.describe()
                    }
                    // 失败的回复不入库：下次重试时不该把一条错误消息当成上下文发给模型。回复到一半
                    // 断掉的也一样，冒出来的那半截一起丢掉。
                    _uiState.update {
                        it.copy(isResponding = false, streamingReply = null, error = message)
                    }
                }
            }
        }
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
         * 临时的系统提示词。
         *
         * 真正的那份要等 Agent 循环做好——得把工具怎么用、节点 id 每轮会变、
         * 错误码什么意思都讲清楚。现在只是让它别乱回答。
         */
        const val SYSTEM_PROMPT =
            "你是 Kitten，一个运行在用户 Android 手机上的助手。回答简洁，用中文。"
    }
}
