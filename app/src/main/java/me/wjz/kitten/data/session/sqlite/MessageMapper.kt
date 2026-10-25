package me.wjz.kitten.data.session.sqlite

import android.content.ContentValues
import android.database.Cursor
import me.wjz.kitten.data.session.sqlite.KittenOpenHelper.Columns
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.ImageAttachment
import me.wjz.kitten.llm.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * [ChatMessage] 和数据库行之间的转换。
 *
 * 单独拎出来是因为这是手写 SQLite 最容易出错的地方：读写两端的列名和类型必须严格
 * 对应，而编译器不会帮你检查。放在一个文件里，读写逻辑挨着写，对不上一眼就能看出来。
 *
 * [ChatMessage] 是 sealed interface，三个变体各自用到的列不同——把 role 当判别字段，
 * 存的时候按类型填，读的时候按 role 还原。
 */
internal object MessageMapper {

    private const val ROLE_USER = "user"
    private const val ROLE_ASSISTANT = "assistant"
    private const val ROLE_TOOL = "tool"

    /** 只发了图片、没有字的那条消息，在会话列表里的副标题。 */
    const val IMAGE_PREVIEW = "[图片]"

    fun toContentValues(
        sessionId: String,
        message: ChatMessage,
        createdAtMillis: Long,
    ): ContentValues = ContentValues().apply {
        put(Columns.SESSION_ID, sessionId)
        put(Columns.CREATED_AT, createdAtMillis)
        when (message) {
            is ChatMessage.User -> {
                put(Columns.ROLE, ROLE_USER)
                put(Columns.CONTENT, message.text)
                // 同工具调用：没有图片存 null。
                put(Columns.IMAGES, message.images.takeIf { it.isNotEmpty() }?.imagesToJson())
            }

            is ChatMessage.Assistant -> {
                put(Columns.ROLE, ROLE_ASSISTANT)
                put(Columns.CONTENT, message.text)
                // 没有工具调用时存 null 而不是 "[]"：读回来时少一次解析，
                // 而且 null 在 SQLite 里不占空间。
                put(Columns.TOOL_CALLS, message.toolCalls.takeIf { it.isNotEmpty() }?.toJson())
            }

            is ChatMessage.ToolResult -> {
                put(Columns.ROLE, ROLE_TOOL)
                put(Columns.CONTENT, message.content)
                put(Columns.TOOL_CALL_ID, message.callId)
                put(Columns.TOOL_NAME, message.name)
            }
        }
    }

    /**
     * 从游标当前行还原消息。
     *
     * 返回 null 表示这一行坏了（role 不认识、或该有的字段是空的）。调用方应该跳过它
     * 而不是整个查询失败——一条坏数据不该让用户打不开整个会话。
     */
    fun fromCursor(cursor: Cursor): ChatMessage? {
        val role = cursor.getStringOrNull(Columns.ROLE) ?: return null
        val content = cursor.getStringOrNull(Columns.CONTENT)
        return when (role) {
            ROLE_USER -> content?.let {
                ChatMessage.User(
                    text = it,
                    images = cursor.getStringOrNull(Columns.IMAGES)?.toImages().orEmpty(),
                )
            }

            ROLE_ASSISTANT -> ChatMessage.Assistant(
                text = content,
                toolCalls = cursor.getStringOrNull(Columns.TOOL_CALLS)?.toToolCalls().orEmpty(),
            )

            ROLE_TOOL -> {
                val callId = cursor.getStringOrNull(Columns.TOOL_CALL_ID) ?: return null
                ChatMessage.ToolResult(
                    callId = callId,
                    name = cursor.getStringOrNull(Columns.TOOL_NAME).orEmpty(),
                    content = content.orEmpty(),
                )
            }

            else -> null
        }
    }

    /** 列表页的副标题：取最后一条消息的可读摘要。 */
    fun preview(message: ChatMessage): String? = when (message) {
        is ChatMessage.User -> message.text.ifBlank { if (message.images.isNotEmpty()) IMAGE_PREVIEW else "" }
        is ChatMessage.Assistant -> message.text
            // 纯工具调用那轮没有文字，显示在干什么比显示空白有用。
            ?: message.toolCalls.firstOrNull()?.let { "正在${it.name}…" }
        is ChatMessage.ToolResult -> null
    }?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }

    private fun List<ToolCall>.toJson(): String = JSONArray().apply {
        this@toJson.forEach { call ->
            put(
                JSONObject()
                    .put("id", call.id)
                    .put("name", call.name)
                    .put("arguments", call.arguments),
            )
        }
    }.toString()

    private fun List<ImageAttachment>.imagesToJson(): String = JSONArray().apply {
        this@imagesToJson.forEach { image ->
            put(
                JSONObject()
                    .put("path", image.path)
                    .put("width", image.width)
                    .put("height", image.height),
            )
        }
    }.toString()

    /** 解析失败就当没有图片，同工具调用：不要因为一条坏数据整个会话打不开。 */
    private fun String.toImages(): List<ImageAttachment> = runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ImageAttachment(
                path = item.optString("path").ifBlank { return@mapNotNull null },
                width = item.optInt("width"),
                height = item.optInt("height"),
            )
        }
    }.getOrDefault(emptyList())

    /** 解析失败就当没有工具调用——宁可少显示一轮细节，也不要整个会话打不开。 */
    private fun String.toToolCalls(): List<ToolCall> = runCatching {
        val array = JSONArray(this)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ToolCall(
                id = item.optString("id"),
                name = item.optString("name"),
                arguments = item.optString("arguments").ifBlank { "{}" },
            )
        }
    }.getOrDefault(emptyList())
}

/** 按列名取字符串，空值返回 null。手写 SQL 没有生成代码帮忙，这类小工具得自己备。 */
internal fun Cursor.getStringOrNull(columnName: String): String? {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return null
    return getString(index)
}

internal fun Cursor.getLongOrZero(columnName: String): Long {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return 0L
    return getLong(index)
}

internal fun Cursor.getIntOrZero(columnName: String): Int {
    val index = getColumnIndex(columnName)
    if (index < 0 || isNull(index)) return 0
    return getInt(index)
}
