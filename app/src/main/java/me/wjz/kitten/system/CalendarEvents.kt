package me.wjz.kitten.system

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import java.util.TimeZone

/** 日历里的一个日程。时间都是 UTC 毫秒。 */
data class CalendarEvent(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val location: String?,
)

/**
 * 读写系统日历（[CalendarContract]）：加一个日程、查某段时间里有哪些日程。直接写进日历的数据库，不弹
 * 日历应用的界面。要 READ_CALENDAR / WRITE_CALENDAR，增强模式连上时 Kitten 自己补上（见 privilege 模块的
 * SelfGrants）。
 *
 * ## 写进哪个日历
 *
 * 手机上可能有好几个日历（本机的、登录的账号同步来的）。挑能写的、看得见的那些里标了"主日历"的，没有就
 * 挑第一个；一个能写的都没有（有的手机没登任何账号），就自己建一个本机日历"Kitten"。
 */
object CalendarEvents {

    private const val TAG = "KittenCalendar"

    /**
     * 加一个日程。
     *
     * @param remindMinutes 提前几分钟提醒（日历应用自己到点弹通知），null 就不提醒。
     * @return 失败的原因，成功是 null。
     */
    fun add(
        context: Context,
        title: String,
        beginMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String?,
        notes: String?,
        remindMinutes: Int?,
    ): String? {
        return try {
            insert(context, title, beginMillis, endMillis, allDay, location, notes, remindMinutes)
        } catch (e: SecurityException) {
            "没有日历权限"
        }
    }

    private fun insert(
        context: Context,
        title: String,
        beginMillis: Long,
        endMillis: Long,
        allDay: Boolean,
        location: String?,
        notes: String?,
        remindMinutes: Int?,
    ): String? {
        val calendarId = writableCalendar(context) ?: return "手机上没有能写的日历，也建不出来"
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            // 全天的日程按规矩要用 UTC，不然在别的时区会错一天。
            put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
            location?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            notes?.takeIf { it.isNotBlank() }?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: return "日历没接受这个日程"
        val eventId = uri.lastPathSegment?.toLongOrNull()
        if (eventId != null && remindMinutes != null) {
            context.contentResolver.insert(
                CalendarContract.Reminders.CONTENT_URI,
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, remindMinutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                },
            )
        }
        return null
    }

    /**
     * [fromMillis] 到 [toMillis] 之间的日程，按开始时间排，最多 [limit] 条。重复的日程按每一次分开算（查的是
     * Instances 表）。返回 null 表示没有权限。
     */
    fun list(context: Context, fromMillis: Long, toMillis: Long, limit: Int): List<CalendarEvent>? = try {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMillis.toString())
            .appendPath(toMillis.toString())
            .build()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.EVENT_LOCATION,
            ),
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < limit) {
                    add(
                        CalendarEvent(
                            title = cursor.getString(0).orEmpty().ifBlank { "（没有标题）" },
                            beginMillis = cursor.getLong(1),
                            endMillis = cursor.getLong(2),
                            allDay = cursor.getInt(3) == 1,
                            location = cursor.getString(4)?.takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.orEmpty()
    } catch (e: SecurityException) {
        null
    }

    /** 往哪个日历里写，见类注释。 */
    private fun writableCalendar(context: Context): Long? {
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
            null,
        )?.use { cursor ->
            var first: Long? = null
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                if (cursor.getInt(1) == 1) return id
                if (first == null) first = id
            }
            if (first != null) return first
        }
        return createLocalCalendar(context)
    }

    /** 建一个只在本机的日历"Kitten"：按同步适配器的身份写，不需要任何账号。 */
    private fun createLocalCalendar(context: Context): Long? {
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_COLOR, LOCAL_COLOR)
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return runCatching { context.contentResolver.insert(uri, values)?.lastPathSegment?.toLongOrNull() }
            .onFailure { Log.w(TAG, "建本机日历失败", it) }
            .getOrNull()
    }

    /** 自己建的本机日历叫什么。 */
    private const val LOCAL_ACCOUNT = "Kitten"

    /** 自己建的本机日历的颜色：天依蓝。 */
    private val LOCAL_COLOR = 0xFF66CCFF.toInt()
}
