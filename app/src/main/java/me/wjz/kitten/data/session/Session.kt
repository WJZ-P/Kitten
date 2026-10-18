package me.wjz.kitten.data.session

import me.wjz.kitten.llm.ChatMessage

/**
 * 一次对话会话。
 *
 * 这是**领域模型**，刻意不带任何存储痕迹——没有自增主键、没有外键、没有表名。
 * 换存储方案时它一行都不用改，受影响的只有 [SessionRepository] 的实现。
 *
 * 消息直接复用 [ChatMessage]，而不是另造一套"存储用的消息类"再来回转换。
 * 对话的内容本来就是要原样发给模型的，多一层映射只会多一个出错的地方。
 */
data class Session(
    val id: String,
    val title: String,
    val messages: List<ChatMessage>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * 会话列表用的轻量信息。
 *
 * 侧边栏要列出几十上百个会话，但只显示标题和时间——把每个会话的全部消息都读出来
 * 纯属浪费。真正打开某个会话时再用 [SessionRepository.load] 取完整内容。
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val messageCount: Int,
    val updatedAtMillis: Long,
    /** 最后一条消息的摘要，给列表当副标题用。 */
    val preview: String?,
)
