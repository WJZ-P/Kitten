package me.wjz.kitten.data.memory

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * Kitten 关于用户的记忆：偏好、忌口、常用地址、账号信息……换了对话也还在。
 *
 * 模型通过三个工具读写（见 agent/tools/MemoryTools），用户在设置页里查看、改、删、手动添加。
 *
 * ## 为什么单独一个数据库文件
 *
 * 会话存在 kitten.db 里，它的升级策略是整库重建（见 KittenOpenHelper.onUpgrade）：聊天的表结构
 * 还在定型，改一次就清一次。记忆不能跟着它说没就没，所以单独放在 kitten_memory.db。
 *
 * ## 为什么是单例
 *
 * 对话页（模型调工具写进来）和设置页（用户在列表里改）是两个地方，但要看到同一份数据：模型
 * 刚记下一条，设置页的列表就该多一行。两边共用一个实例，共用一个变更信号（[changes]）。
 *
 * ## 存的是明文
 *
 * 用户要求密码、卡号也照记，以方便为主。数据在 App 自己的沙箱里，别的应用读不到。另一面要心里
 * 有数：记忆会随请求发给模型服务商——放进系统提示词的那些（[forPrompt]）每次请求都带着，没放进去的
 * 在模型查到时带上。
 *
 * ## 最近用到
 *
 * 每条记着最近一次被用到的时间（记下、改过、被 recall_memory 查到，[markUsed]）。系统提示词里只放得下
 * 一部分时，按它挑最近用到的那些，最久没用的让出位置——一个 LRU。
 *
 * 记忆内容不打日志，和用户消息一个规矩。
 */
class MemoryStore private constructor(context: Context) {

    private val helper = MemoryOpenHelper(context.applicationContext)

    /**
     * 数据变更信号，订阅方收到就重查一次。做法和 SqliteSessionRepository 一样。
     *
     * DROP_OLDEST：信号只表示"有东西变了"，连着来几个合成一个就行，这样 [notifyChanged] 用
     * tryEmit 永远发得出去，不会因为订阅方还没处理完上一个就把这一个丢掉。
     */
    private val changes = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** 全部记忆，最近改过的在前面。有变化就推一份新的。 */
    fun observe(): Flow<List<MemoryEntry>> =
        changes
            .onStart { emit(Unit) }
            .map { withContext(Dispatchers.IO) { queryAll() } }

    /** 一共记了几条。 */
    suspend fun count(): Int = withContext(Dispatchers.IO) { countBlocking() }

    /** 最近改过的 [limit] 条。 */
    suspend fun recent(limit: Int): List<MemoryEntry> = withContext(Dispatchers.IO) {
        query(selection = null, args = null, limit = limit)
    }

    /**
     * 按关键词找：内容里包含任意一个关键词就算，最近改过的在前面。
     *
     * 用 LIKE 子串匹配，不用全文索引：记忆最多几百条，扫一遍很快；而且中文不按空格分词，
     * FTS 的默认分词器对中文基本没用，要接分词器反而麻烦。
     */
    suspend fun search(keywords: List<String>, limit: Int): List<MemoryEntry> = withContext(Dispatchers.IO) {
        if (keywords.isEmpty()) return@withContext emptyList()
        val selection = keywords.joinToString(" OR ") { "$COL_CONTENT LIKE ? ESCAPE '\\'" }
        val args = keywords.map { "%${it.escapeLike()}%" }.toTypedArray()
        query(selection, args, limit)
    }

    /**
     * 放进系统提示词的那几条：按最近用到排，从最近的开始放，放到下一条会超过 [budgetChars] 个字就停，
     * 剩下的（最久没用的那些）不放，见 [PromptMemories]。
     *
     * 严格按先后截断，不跳过长的去塞后面短的：塞进去的就该是"最近用到的那一批"，挑着塞的话，一条很久
     * 没用的短记忆反倒占着位置。
     */
    suspend fun forPrompt(budgetChars: Int): PromptMemories = withContext(Dispatchers.IO) {
        val all = helper.readableDatabase.query(
            TABLE, null, null, null, null, null,
            "$COL_LAST_USED_AT DESC, $COL_ID DESC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toEntry()) } }
        var used = 0
        val taken = all.takeWhile { entry ->
            (used + entry.content.length <= budgetChars).also { fits -> if (fits) used += entry.content.length }
        }
        PromptMemories(entries = taken, omitted = all.size - taken.size)
    }

    /**
     * 这几条刚被用到了（recall_memory 查到了它们），记下时间。不发变更信号：设置页的列表不显示这个，
     * 每查一次就让它重读一遍没有意义。
     */
    suspend fun markUsed(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        helper.writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_LAST_USED_AT = ? WHERE $COL_ID IN (${ids.joinToString(",") { "?" }})",
            arrayOf<Any>(System.currentTimeMillis(), *ids.toTypedArray()),
        )
    }

    /** 记下一条，返回记下的这条；已经满了（[MAX_ENTRIES]）返回 null。 */
    suspend fun add(content: String): MemoryEntry? = withContext(Dispatchers.IO) {
        if (countBlocking() >= MAX_ENTRIES) return@withContext null
        val now = System.currentTimeMillis()
        val id = helper.writableDatabase.insert(
            TABLE,
            null,
            ContentValues().apply {
                put(COL_CONTENT, content)
                put(COL_CREATED_AT, now)
                put(COL_UPDATED_AT, now)
                // 刚记下的算刚用到：下一个会话的提示词里先放它。
                put(COL_LAST_USED_AT, now)
            },
        )
        if (id == -1L) {
            Log.e(TAG, "写入记忆失败")
            return@withContext null
        }
        notifyChanged()
        MemoryEntry(id, content, now, now)
    }

    /** 把 [id] 那条改成 [content]，返回改之前的那条；没有这条返回 null。 */
    suspend fun update(id: Long, content: String): MemoryEntry? = withContext(Dispatchers.IO) {
        val old = findBlocking(id) ?: return@withContext null
        val now = System.currentTimeMillis()
        helper.writableDatabase.update(
            TABLE,
            ContentValues().apply {
                put(COL_CONTENT, content)
                put(COL_UPDATED_AT, now)
                // 改过的也算刚用到。
                put(COL_LAST_USED_AT, now)
            },
            "$COL_ID = ?",
            arrayOf(id.toString()),
        )
        notifyChanged()
        old
    }

    /** 删掉 [id] 那条，返回被删的那条；没有这条返回 null。 */
    suspend fun delete(id: Long): MemoryEntry? = withContext(Dispatchers.IO) {
        val old = findBlocking(id) ?: return@withContext null
        helper.writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id.toString()))
        notifyChanged()
        old
    }

    /** 内容一字不差的那条，用来防止重复记。没有返回 null。 */
    suspend fun findExact(content: String): MemoryEntry? = withContext(Dispatchers.IO) {
        query("$COL_CONTENT = ?", arrayOf(content), limit = 1).firstOrNull()
    }

    private fun queryAll(): List<MemoryEntry> = query(selection = null, args = null, limit = null)

    private fun findBlocking(id: Long): MemoryEntry? =
        query("$COL_ID = ?", arrayOf(id.toString()), limit = 1).firstOrNull()

    private fun countBlocking(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun query(selection: String?, args: Array<String>?, limit: Int?): List<MemoryEntry> =
        helper.readableDatabase.query(
            TABLE,
            null,
            selection,
            args,
            null,
            null,
            "$COL_UPDATED_AT DESC, $COL_ID DESC",
            limit?.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toEntry())
            }
        }

    private fun Cursor.toEntry() = MemoryEntry(
        id = getLong(getColumnIndexOrThrow(COL_ID)),
        content = getString(getColumnIndexOrThrow(COL_CONTENT)).orEmpty(),
        createdAtMillis = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAtMillis = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
        lastUsedAtMillis = getLong(getColumnIndexOrThrow(COL_LAST_USED_AT)),
    )

    /** LIKE 里 % 和 _ 是通配符，关键词里本来就带的话要转义，不然"100%"会匹配到一切。 */
    private fun String.escapeLike(): String =
        replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun notifyChanged() {
        changes.tryEmit(Unit)
    }

    private class MemoryOpenHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE (
                    $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                    $COL_CONTENT TEXT NOT NULL,
                    $COL_CREATED_AT INTEGER NOT NULL,
                    $COL_UPDATED_AT INTEGER NOT NULL,
                    $COL_LAST_USED_AT INTEGER NOT NULL
                )
                """.trimIndent(),
            )
        }

        /**
         * 还没上线，表结构说改就改，不写迁移：版本号加一，整张表重建，手机上测试时记的那些清掉。
         * 上线以后再改表结构，就得写真正的迁移了——那时记忆是用户攒下来的，丢了就没了。
         */
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val TAG = "KittenMemory"
        private const val DATABASE_NAME = "kitten_memory.db"
        private const val DATABASE_VERSION = 2

        private const val TABLE = "memories"
        private const val COL_ID = "id"
        private const val COL_CONTENT = "content"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_LAST_USED_AT = "last_used_at"

        /**
         * 一条记忆最多多少字。地址、卡号、一句偏好都远远用不到；再长多半是把一大段东西整个塞了
         * 进来，该拆成几条。
         */
        const val MAX_CONTENT_LENGTH = 300

        /**
         * 最多记多少条。设个上限是怕模型（或者被页面上的字骗了）一直往里塞，列表和查出来的结果
         * 都会被撑爆。真满了，模型会收到"先删掉过时的"的提示。
         */
        const val MAX_ENTRIES = 200

        @Volatile
        private var instance: MemoryStore? = null

        fun get(context: Context): MemoryStore =
            instance ?: synchronized(this) {
                instance ?: MemoryStore(context).also { instance = it }
            }
    }
}
