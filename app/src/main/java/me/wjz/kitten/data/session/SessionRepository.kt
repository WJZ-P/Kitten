package me.wjz.kitten.data.session

import kotlinx.coroutines.flow.Flow
import me.wjz.kitten.llm.ChatMessage

/**
 * 会话的存取。**上层（ViewModel / UI）只认这个接口，不知道底下是什么。**
 *
 * ## 为什么要这层接口
 *
 * 当前的实现是手写 SQLite（见 `sqlite` 子包），这是个**被迫的选择**而不是偏好：
 *
 * - 业界标准做法是 Room，它能在编译期检查 SQL、自动把查询变成 Flow；
 * - 但 Room 依赖 KSP 做注解处理，而 **KSP 最新只支持到 Kotlin 2.3.x**
 *   （2.3.12 依赖 kotlin-stdlib 2.3.20），本项目用的是 Kotlin 2.4.21；
 * - 降 Kotlin 也走不通：液体玻璃库 backdrop 是用 Kotlin 2.4 编译的
 *   （class 文件里 metadata version = 2.4），而编译器读不了比自己新的 metadata，
 *   降到 2.3 会直接编译失败。
 *
 * 所以在 KSP 跟上 Kotlin 2.4 之前，只能自己写 SQL。等它跟上了，**换掉实现类即可，
 * 这个接口和所有上层代码一行都不用动**——这正是这层抽象存在的意义。
 *
 * 接口形态也是照着 Room 的习惯设计的：查询返回 [Flow]，写操作是挂起函数。
 * 这样将来换成 Room 的 DAO 时签名能直接对上，不会因为"内存版当初图省事用了同步
 * 返回"而被迫改调用方。
 */
interface SessionRepository {

    /**
     * 订阅会话列表，按最近更新排序。
     *
     * 返回 Flow 而不是一次性的 List：任何地方改了会话（新建、追加消息、改标题、删除），
     * 侧边栏都该跟着变，而不是等用户手动刷新。
     */
    fun observeSessions(): Flow<List<SessionSummary>>

    /** 新建一个空会话并返回它。 */
    suspend fun create(title: String): Session

    /** 读取完整会话（含全部消息）；不存在返回 null。 */
    suspend fun load(id: String): Session?

    /**
     * 往会话末尾追加一条消息。
     *
     * 单条追加而不是"保存整个会话"：Agent 一轮下来会产生好几条消息（模型回复、
     * 工具结果、再回复），每条一出现就该落盘，中途崩了也不至于整轮丢光。
     */
    suspend fun appendMessage(sessionId: String, message: ChatMessage)

    /** 改标题。第一轮对话后通常会用首句话自动命名。 */
    suspend fun rename(id: String, title: String)

    suspend fun delete(id: String)

    /** 清空某个会话的消息但保留它本身，用于"重新开始"。 */
    suspend fun clearMessages(id: String)
}
