package me.wjz.kitten.data.session.sqlite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import me.wjz.kitten.data.attachment.AttachmentStore
import me.wjz.kitten.data.session.Session
import me.wjz.kitten.data.session.SessionRepository
import me.wjz.kitten.data.session.SessionSummary
import me.wjz.kitten.data.session.sqlite.KittenOpenHelper.Columns
import me.wjz.kitten.data.session.sqlite.KittenOpenHelper.Tables
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.ImageAttachment
import java.util.UUID

/**
 * [SessionRepository] 的 SQLite 实现。
 *
 * 为什么是手写而不是 Room，见 [SessionRepository] 的注释——一句话：KSP 还没支持
 * Kotlin 2.4，而降 Kotlin 会和 backdrop 库的 metadata 版本冲突。
 *
 * ## 自己模拟 Room 的 Flow
 *
 * Room 的查询能返回 Flow 是因为它在底层挂了表变更的观察者。裸 SQLite 没有这个机制，
 * 所以这里用 [changes] 这个信号流顶上：每次写操作后发一个信号，订阅方收到就重查一次。
 *
 * 粒度比 Room 粗——Room 能精确到"哪张表变了"，这里是"有东西变了，全部重查"。
 * 对会话列表（几十条）完全够用；真到了需要精细失效的规模，那时候 KSP 多半也跟上了，
 * 换成 Room 即可。
 *
 * [onStart] 那下很关键：新订阅者要立刻拿到当前数据，而不是傻等到下一次写操作。
 *
 * ## 线程
 *
 * SQLite 的读写都是阻塞 IO，全部包在 [io] 调度器里。`SQLiteDatabase` 本身是线程安全的
 * （内部有锁），所以不需要额外同步。
 */
class SqliteSessionRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SessionRepository {

    private val helper = KittenOpenHelper(context.applicationContext)

    /** 消息里的图片文件。删消息、删会话时跟着删，不然越积越多。 */
    private val attachments = AttachmentStore(context)

    /**
     * 数据变更信号。
     *
     * replay=1 让后来的订阅者也能立刻触发一次查询；用 Unit 而不是具体内容，
     * 是因为订阅方本来就要重新查库，传什么都用不上。
     */
    private val changes = MutableSharedFlow<Unit>(replay = 1)

    override fun observeSessions(): Flow<List<SessionSummary>> =
        changes
            .onStart { emit(Unit) }
            .map { withContext(io) { querySummaries() } }

    override suspend fun create(title: String): Session = withContext(io) {
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = title,
            messages = emptyList(),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        helper.writableDatabase.insert(
            Tables.SESSIONS,
            null,
            ContentValues().apply {
                put(Columns.ID, session.id)
                put(Columns.TITLE, session.title)
                put(Columns.CREATED_AT, now)
                put(Columns.UPDATED_AT, now)
            },
        )
        notifyChanged()
        session
    }

    override suspend fun load(id: String): Session? = withContext(io) {
        val db = helper.readableDatabase
        db.query(
            Tables.SESSIONS,
            null,
            "${Columns.ID} = ?",
            arrayOf(id),
            null, null, null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@withContext null
            Session(
                id = id,
                title = cursor.getStringOrNull(Columns.TITLE).orEmpty(),
                messages = queryMessages(db, id),
                createdAtMillis = cursor.getLongOrZero(Columns.CREATED_AT),
                updatedAtMillis = cursor.getLongOrZero(Columns.UPDATED_AT),
            )
        }
    }

    override suspend fun appendMessage(sessionId: String, message: ChatMessage) {
        withContext(io) {
            val now = System.currentTimeMillis()
            val db = helper.writableDatabase
            // 插消息和更新会话时间要么都成要么都不成，否则列表的排序会和内容对不上。
            db.transaction {
                insert(
                    Tables.MESSAGES,
                    null,
                    MessageMapper.toContentValues(sessionId, message, now),
                )
                update(
                    Tables.SESSIONS,
                    ContentValues().apply { put(Columns.UPDATED_AT, now) },
                    "${Columns.ID} = ?",
                    arrayOf(sessionId),
                )
            }
            notifyChanged()
        }
    }

    override suspend fun deleteMessages(sessionId: String, indices: Set<Int>) {
        if (indices.isEmpty()) return
        withContext(io) {
            val db = helper.writableDatabase
            val doomed = db.transaction {
                // 和 load() 走同一个查询、同一套跳过坏行的规则，下标才对得上。
                val rows = queryMessageRows(this, sessionId).filterIndexed { i, _ -> i in indices }
                rows.forEach { row ->
                    delete(Tables.MESSAGES, "$ROW_ID = ?", arrayOf(row.rowId.toString()))
                }
                rows.map { it.message }
            }
            // 库里删成功了再删文件：反过来的话事务失败，消息还在、图片却没了。
            attachments.delete(doomed.imagesOf())
            notifyChanged()
        }
    }

    override suspend fun rename(id: String, title: String) {
        withContext(io) {
            helper.writableDatabase.update(
                Tables.SESSIONS,
                ContentValues().apply { put(Columns.TITLE, title) },
                "${Columns.ID} = ?",
                arrayOf(id),
            )
            notifyChanged()
        }
    }

    override suspend fun delete(id: String) {
        withContext(io) {
            val db = helper.writableDatabase
            // 消息靠外键 ON DELETE CASCADE 跟着删——前提是 onConfigure 里开了外键约束。图片文件
            // 不在库里，删之前先记下来。
            val images = queryMessages(db, id).imagesOf()
            db.delete(Tables.SESSIONS, "${Columns.ID} = ?", arrayOf(id))
            attachments.delete(images)
            notifyChanged()
        }
    }

    override suspend fun clearMessages(id: String) {
        withContext(io) {
            val db = helper.writableDatabase
            val images = queryMessages(db, id).imagesOf()
            db.delete(
                Tables.MESSAGES,
                "${Columns.SESSION_ID} = ?",
                arrayOf(id),
            )
            attachments.delete(images)
            notifyChanged()
        }
    }

    private fun List<ChatMessage>.imagesOf(): List<ImageAttachment> =
        flatMap { (it as? ChatMessage.User)?.images.orEmpty() }

    /**
     * 会话列表。
     *
     * 消息数和预览用子查询一次取出，避免"先查会话再逐个查消息"的 N+1——
     * 侧边栏每次刷新都要跑这个查询，会话多了那种写法会肉眼可见地卡。
     */
    private fun querySummaries(): List<SessionSummary> {
        val sql = """
            SELECT s.${Columns.ID}, s.${Columns.TITLE}, s.${Columns.UPDATED_AT},
                   (SELECT COUNT(*) FROM ${Tables.MESSAGES} m
                     WHERE m.${Columns.SESSION_ID} = s.${Columns.ID}) AS msg_count,
                   (SELECT COALESCE(
                               NULLIF(m.${Columns.CONTENT}, ''),
                               CASE WHEN m.${Columns.IMAGES} IS NOT NULL THEN '${MessageMapper.IMAGE_PREVIEW}' END
                           )
                      FROM ${Tables.MESSAGES} m
                     WHERE m.${Columns.SESSION_ID} = s.${Columns.ID}
                     ORDER BY m.${Columns.CREATED_AT} DESC, m.rowid DESC LIMIT 1) AS last_content
              FROM ${Tables.SESSIONS} s
             ORDER BY s.${Columns.UPDATED_AT} DESC
        """.trimIndent()
        return runCatching {
            helper.readableDatabase.rawQuery(sql, null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            SessionSummary(
                                id = cursor.getStringOrNull(Columns.ID).orEmpty(),
                                title = cursor.getStringOrNull(Columns.TITLE).orEmpty(),
                                messageCount = cursor.getIntOrZero("msg_count"),
                                updatedAtMillis = cursor.getLongOrZero(Columns.UPDATED_AT),
                                preview = cursor.getStringOrNull("last_content")
                                    ?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() },
                            ),
                        )
                    }
                }
            }
        }.onFailure {
            // 手写 SQL 没有编译期检查，查询写错只能在这里现形。打出来好定位。
            Log.e(TAG, "查询会话列表失败", it)
        }.getOrDefault(emptyList())
    }

    private fun queryMessages(db: SQLiteDatabase, sessionId: String): List<ChatMessage> =
        queryMessageRows(db, sessionId).map { it.message }

    /** 一个会话的全部消息，带着各自的 rowid（按位置删消息时要用）。 */
    private fun queryMessageRows(db: SQLiteDatabase, sessionId: String): List<MessageRow> =
        db.query(
            Tables.MESSAGES,
            null,
            "${Columns.SESSION_ID} = ?",
            arrayOf(sessionId),
            null,
            null,
            // 同一毫秒内插入的多条消息靠 rowid 保证顺序——Agent 一轮里连续追加
            // 好几条消息时，时间戳可能完全相同。
            "${Columns.CREATED_AT} ASC, rowid ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    // 坏行跳过，不让一条脏数据毁掉整个会话。
                    MessageMapper.fromCursor(cursor)?.let { add(MessageRow(cursor.getLongOrZero(ROW_ID), it)) }
                }
            }
        }

    private class MessageRow(val rowId: Long, val message: ChatMessage)

    private suspend fun notifyChanged() {
        changes.emit(Unit)
    }

    /** `SQLiteDatabase` 没有现成的事务 DSL，补一个，免得每处都写 try/finally。 */
    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val TAG = "KittenDb"

        /** 消息表的自增主键。建表时显式声明了这一列（见 KittenOpenHelper），SELECT * 会带上它。 */
        const val ROW_ID = "rowid"
    }
}
