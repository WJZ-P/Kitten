package me.wjz.kitten.data.session.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

/**
 * 数据库的建表与升级。
 *
 * ## 为什么是手写而不是 Room
 *
 * Room 需要 KSP 做注解处理，而 KSP 最新只支持到 Kotlin 2.3.x，本项目是 2.4.21；
 * 降 Kotlin 又会和用 2.4 编译的 backdrop 库冲突。详见 `SessionRepository` 的注释。
 *
 * 手写的代价是**失去编译期 SQL 检查**：字段名打错要等运行时才炸。为此做了两件事
 * 兜底——所有列名集中在 [Columns] 里当常量用，不在 SQL 字符串里裸写；
 * 以及建库后跑一次冒烟查询（见 [SqliteSessionRepository]），把低级错误暴露在启动时
 * 而不是用户点进某个页面时。
 *
 * ## 关于外键
 *
 * SQLite 默认**不启用**外键约束，必须每条连接都显式打开（见 [onConfigure]）。
 * 不开的话 `ON DELETE CASCADE` 是摆设，删会话时消息会变成孤儿留在表里。
 */
internal class KittenOpenHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // 必须在这里开：onCreate/onUpgrade 里设会在事务中失败。
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Log.i(TAG, "建库 v$DATABASE_VERSION")
        db.execSQL(CREATE_SESSIONS)
        db.execSQL(CREATE_MESSAGES)
        db.execSQL(CREATE_MESSAGES_INDEX)
    }

    /**
     * 升级策略：**直接重建**。
     *
     * 现在还在把消息格式定型的阶段（工具调用怎么存、Agent 执行记录要不要进来都没定），
     * 这时候写 Migration 等于给一个随时会变的结构做版本管理，不划算。
     *
     * **发布之前必须改掉**——到那时用户的聊天记录不能说丢就丢。这条留在这里是个
     * 明确的待办，不是疏忽。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "数据库从 v$oldVersion 升到 v$newVersion，当前策略是重建，历史会话会丢失")
        db.execSQL("DROP TABLE IF EXISTS ${Tables.MESSAGES}")
        db.execSQL("DROP TABLE IF EXISTS ${Tables.SESSIONS}")
        onCreate(db)
    }

    internal object Tables {
        const val SESSIONS = "sessions"
        const val MESSAGES = "messages"
    }

    /**
     * 所有列名。
     *
     * 集中成常量是手写 SQL 的必要纪律：拼字符串时写错列名，编译器不会管，
     * 要等运行时 `no such column` 才发现。用常量至少能让 IDE 的重命名和
     * 「查找引用」起作用。
     */
    internal object Columns {
        const val ID = "id"
        const val TITLE = "title"
        const val CREATED_AT = "created_at"
        const val UPDATED_AT = "updated_at"

        const val SESSION_ID = "session_id"
        const val ROLE = "role"
        const val CONTENT = "content"
        const val TOOL_CALLS = "tool_calls"
        const val TOOL_CALL_ID = "tool_call_id"
        const val TOOL_NAME = "tool_name"
    }

    private companion object {
        const val TAG = "KittenDb"
        const val DATABASE_NAME = "kitten.db"
        const val DATABASE_VERSION = 1

        val CREATE_SESSIONS = """
            CREATE TABLE ${Tables.SESSIONS} (
                ${Columns.ID} TEXT PRIMARY KEY NOT NULL,
                ${Columns.TITLE} TEXT NOT NULL,
                ${Columns.CREATED_AT} INTEGER NOT NULL,
                ${Columns.UPDATED_AT} INTEGER NOT NULL
            )
        """.trimIndent()

        /**
         * 消息表。
         *
         * 三种消息（用户/模型/工具结果）合在一张表里，靠 [Columns.ROLE] 区分，
         * 各自用不到的列留空。拆成三张表在查询时要做三路 UNION 再按时间排序，
         * 而对话本来就是一条线性的时间序列，合表更贴合它的本质。
         *
         * tool_calls 存 JSON 字符串：模型一轮可以并发调用多个工具，数量不定，
         * 而我们从不按工具名去查消息——这种"只整存整取、不参与查询"的结构，
         * 塞一列 JSON 比专门开一张表合适。
         */
        val CREATE_MESSAGES = """
            CREATE TABLE ${Tables.MESSAGES} (
                rowid INTEGER PRIMARY KEY AUTOINCREMENT,
                ${Columns.SESSION_ID} TEXT NOT NULL,
                ${Columns.ROLE} TEXT NOT NULL,
                ${Columns.CONTENT} TEXT,
                ${Columns.TOOL_CALLS} TEXT,
                ${Columns.TOOL_CALL_ID} TEXT,
                ${Columns.TOOL_NAME} TEXT,
                ${Columns.CREATED_AT} INTEGER NOT NULL,
                FOREIGN KEY (${Columns.SESSION_ID})
                    REFERENCES ${Tables.SESSIONS}(${Columns.ID}) ON DELETE CASCADE
            )
        """.trimIndent()

        /** 按会话取消息是最高频的查询，没有索引的话每次都要全表扫。 */
        val CREATE_MESSAGES_INDEX = """
            CREATE INDEX idx_messages_session
                ON ${Tables.MESSAGES}(${Columns.SESSION_ID}, ${Columns.CREATED_AT})
        """.trimIndent()
    }
}
