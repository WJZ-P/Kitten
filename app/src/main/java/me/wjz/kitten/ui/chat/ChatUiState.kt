package me.wjz.kitten.ui.chat

import me.wjz.kitten.data.session.SessionSummary
import me.wjz.kitten.llm.ChatMessage

/**
 * 对话页要渲染的全部状态。
 *
 * 做成一个不可变的数据类，而不是在 ViewModel 里摆一堆独立的 StateFlow：
 * 界面任一时刻看到的都是一份自洽的快照，不会出现"正在加载"和"已有消息"互相矛盾
 * 的中间态。改多个字段也只触发一次重组。
 */
data class ChatUiState(
    /** 当前会话 id；为 null 表示还没有会话（刚进来或刚清空）。 */
    val sessionId: String? = null,

    val title: String = "",

    val messages: List<ChatMessage> = emptyList(),

    /** 侧边栏的历史会话列表。 */
    val sessions: List<SessionSummary> = emptyList(),

    /** 模型正在生成。此时输入框禁用，并显示等待指示。 */
    val isResponding: Boolean = false,

    /**
     * 正在流式生成的那条回复，已经冒出来的字。还没冒出第一个字、或者不在生成时是 null。
     *
     * 它不在 [messages] 里：回复完之前不入库，也不该被当成上下文发给模型。界面把它画在列表
     * 最后，当成一条临时的回复。
     */
    val streamingReply: String? = null,

    /**
     * 模型是否已配置。
     *
     * 没配就别让用户对着输入框打半天字再被告知发不出去——直接在界面上提示去设置页。
     */
    val isModelConfigured: Boolean = false,

    /** 一次性的错误提示，展示过就该被 [ChatViewModel.consumeError] 清掉。 */
    val error: String? = null,

    /** 一次性的普通提示（比如"已自动补上 /v1"），展示过就该被 [ChatViewModel.consumeNotice] 清掉。 */
    val notice: String? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /** 能不能发送：模型配好了、不在生成中。 */
    val canSend: Boolean get() = isModelConfigured && !isResponding
}
