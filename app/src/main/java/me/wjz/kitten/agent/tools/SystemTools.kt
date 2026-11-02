package me.wjz.kitten.agent.tools

import android.content.Context
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.enumParam
import me.wjz.kitten.agent.intParam
import me.wjz.kitten.agent.noParams
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import me.wjz.kitten.system.Alarms
import me.wjz.kitten.system.AppUsage
import me.wjz.kitten.system.CalendarEvents
import me.wjz.kitten.system.DeviceStatus
import me.wjz.kitten.system.SystemPermissions
import me.wjz.kitten.system.reminder.Reminder
import me.wjz.kitten.system.reminder.ReminderStore
import me.wjz.kitten.system.reminder.Reminders
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Calendar
import java.util.Locale

/*
 * 「系统能力」的几个工具：闹钟、提醒、日程、手机状态、应用使用记录。
 *
 * 这里只管三件事：读参数、把结果写成给模型看的一句话、没权限时怎么说。真正调系统接口的在 system/ 那一层
 * （和 a11y/ 之于读屏、点按是一个分法），任务页列提醒、以后的定时任务也直接用那一层。
 *
 * 按类合并成五个工具、用 action 区分，而不是拆成十几个：每个工具的定义每次请求都要带着，拆太碎每轮
 * 白花几千 token。
 *
 * 时间都按手机的本地时间、`yyyy-MM-dd HH:mm` 写。系统提示词里不放"现在几点"（会让缓存失效），模型要先
 * 用 device_status 拿准今天几号、星期几再算；每次定好了都把落到的具体时间连星期一起说回去，算错了一眼
 * 看得出来。
 */

/** 五个工具的名字。界面要认出"这些不算操作手机"（见 ToolRegistry 的 isPhoneOperation），所以单独拿出来。 */
const val SET_ALARM = "set_alarm"
const val REMINDER = "reminder"
const val CALENDAR = "calendar"
const val DEVICE_STATUS = "device_status"
const val APP_USAGE = "app_usage"

/** 「系统能力」的全部工具，见 ChatViewModel 的 toolsFor。 */
fun systemTools(context: Context, sessionId: String?): List<AgentTool> = listOf(
    SetAlarmTool(context),
    ReminderTool(context, sessionId),
    CalendarTool(context),
    DeviceStatusTool(context),
    AppUsageTool(context),
)

/** 定闹钟、开倒计时：交给系统时钟应用，见 [Alarms]。 */
class SetAlarmTool(private val context: Context) : AgentTool {

    override val name = SET_ALARM

    override val description =
        "在系统时钟里定闹钟、开倒计时，和用户自己在时钟应用里定的一样，用户在那里看得到、关得掉。" +
            "用户说「定个闹钟」「X 点叫我起床」「计时 5 分钟」时用。要的是「提醒我做某件事」的话用 reminder。"

    override val parameters = objectSchema(
        "kind" to enumParam("alarm 闹钟，timer 倒计时，show 打开闹钟列表给用户看", listOf("alarm", "timer", "show")),
        "time" to stringParam("闹钟几点响，HH:mm，比如 07:30。kind=alarm 时要给。"),
        "repeat" to enumParam(
            "闹钟重复：once 只响一次（默认），everyday 每天，workdays 周一到周五，weekends 周六周日",
            listOf("once", "everyday", "workdays", "weekends"),
        ),
        "seconds" to intParam("倒计时多少秒。kind=timer 时要给。"),
        "label" to stringParam("闹钟或倒计时的名字，比如「起床」「煮面」，可以不给。"),
        required = listOf("kind"),
    )

    override suspend fun execute(args: JSONObject): String {
        val label = args.optString("label").trim().takeIf { it.isNotEmpty() }
        return when (args.optString("kind")) {
            "alarm" -> {
                val time = parseTime(args.optString("time"))
                    ?: return "ERROR bad_args: time 要写成 HH:mm，比如 07:30。"
                val repeat = args.optString("repeat").ifBlank { "once" }
                val days = when (repeat) {
                    "everyday" -> AllDays
                    "workdays" -> WorkDays
                    "weekends" -> WeekendDays
                    else -> emptyList()
                }
                Alarms.setAlarm(context, time.hour, time.minute, label, days)
                    ?.let { "ERROR alarm_failed: $it" }
                    ?: "OK 已在系统时钟里定好 ${time.format(ClockFormat)} 的闹钟${label?.let { "「$it」" }.orEmpty()}，" +
                        "${RepeatText[repeat] ?: "只响一次"}。"
            }
            "timer" -> {
                val seconds = args.optInt("seconds", 0)
                if (seconds <= 0) return "ERROR bad_args: seconds 要给一个正数，倒计时多少秒。"
                Alarms.setTimer(context, seconds, label)
                    ?.let { "ERROR timer_failed: $it" }
                    ?: "OK 已开始倒计时 ${durationText(Duration.ofSeconds(seconds.toLong()))}${label?.let { "「$it」" }.orEmpty()}。"
            }
            "show" -> Alarms.showAlarms(context)?.let { "ERROR show_failed: $it" } ?: "OK 已打开闹钟列表。"
            else -> "ERROR bad_args: kind 只能是 alarm、timer、show。"
        }
    }
}

/**
 * Kitten 自己的提醒：到点一直响，也往排它的这段对话（[sessionId]）里写一条，见 [Reminders]。
 *
 * @param sessionId 这一轮在哪个会话里办。
 */
class ReminderTool(private val context: Context, private val sessionId: String?) : AgentTool {

    override val name = REMINDER

    override val description =
        "排一个提醒：到点 Kitten 自己一直响，直到用户点掉，通知上写着标题和内容。" +
            "用户说「X 点提醒我……」「半小时后叫我……」时用。排好的提醒列在 Kitten 的任务页里。" +
            "action=add 时给 title（简短的标题）和 note（到点要说的完整内容，没什么好补充的可以不给），" +
            "再给 at（具体时间）或者 in_minutes（多少分钟后）二选一；" +
            "action=list 列出还没响的；action=cancel 按 list 里的编号取消。" +
            "at 要写具体日期，不知道今天几号的话先调 device_status。"

    override val parameters = objectSchema(
        "action" to enumParam("add 排提醒，list 列出还没响的，cancel 取消一个", listOf("add", "list", "cancel")),
        "title" to stringParam(
            "提醒的标题，$MAX_TITLE_LENGTH 个字以内，一眼看出是什么事，比如「交周报」「给妈妈打电话」「讲个程序员笑话」。",
        ),
        "note" to stringParam("到点要说的完整内容，比如具体要带什么、笑话的全文。标题已经说清楚了就不用给。"),
        "at" to stringParam("什么时候提醒，本地时间 yyyy-MM-dd HH:mm，比如 2026-11-01 15:00。"),
        "in_minutes" to intParam("多少分钟后提醒，和 at 二选一。"),
        "id" to stringParam("要取消的那条的编号，list 结果里方括号中的那串。"),
        required = listOf("action"),
    )

    override suspend fun execute(args: JSONObject): String = when (args.optString("action")) {
        "add" -> add(args)
        "list" -> list()
        "cancel" -> cancel(args.optString("id").trim().removePrefix("[").removeSuffix("]"))
        else -> "ERROR bad_args: action 只能是 add、list、cancel。"
    }

    private fun add(args: JSONObject): String {
        var title = args.optString("title").trim()
        var note = args.optString("note").trim()
        if (title.isEmpty()) return "ERROR bad_args: 没有给 title，要提醒什么事。"
        if (title.length > MAX_TITLE_LENGTH) {
            // 模型把整句话塞进了标题：完整的挪进内容，标题截一段，任务页的卡片上才放得下。
            note = if (note.isEmpty()) title else "$title\n$note"
            title = title.take(MAX_TITLE_LENGTH - 1) + "…"
        }
        if (!SystemPermissions.canScheduleExactAlarms(context)) return SystemPermissions.hint("闹钟和提醒")
        if (!SystemPermissions.canNotify(context)) return SystemPermissions.hint("通知")
        val now = System.currentTimeMillis()
        val triggerAt = when {
            args.optInt("in_minutes", 0) > 0 -> now + args.optInt("in_minutes") * 60_000L
            args.optString("at").isNotBlank() -> parseDateTime(args.optString("at"))?.toMillis()
                ?: return "ERROR bad_args: at 要写成 yyyy-MM-dd HH:mm，比如 2026-11-01 15:00。"
            else -> return "ERROR bad_args: at 和 in_minutes 至少给一个。"
        }
        if (triggerAt <= now) return "ERROR in_past: ${formatMillis(triggerAt)} 已经过了，换一个以后的时间。"
        val reminder = Reminder.create(title, note, triggerAt, sessionId)
        Reminders.schedule(context, reminder)?.let { return "ERROR schedule_failed: $it" }
        return "OK 已排好：${formatMillis(triggerAt)}（${durationText(Duration.ofMillis(triggerAt - now))}后）提醒「$title」，" +
            "到点会一直响，用户点掉才停。编号 [${shortId(reminder.id)}]。"
    }

    private fun list(): String {
        val reminders = ReminderStore.all(context)
        if (reminders.isEmpty()) return "现在没有排着的提醒。"
        return "还没响的提醒：\n" + reminders.joinToString("\n") {
            "[${shortId(it.id)}] ${formatMillis(it.triggerAtMillis)}　${it.title}"
        }
    }

    private fun cancel(id: String): String {
        if (id.isEmpty()) return "ERROR bad_args: 没有给 id，先用 action=list 看编号。"
        val reminder = ReminderStore.all(context).firstOrNull { it.id.startsWith(id) }
            ?: return "ERROR not_found: 没有编号是 [$id] 的提醒，用 action=list 看看。"
        Reminders.cancel(context, reminder.id)
        return "OK 已取消 ${formatMillis(reminder.triggerAtMillis)} 的提醒「${reminder.title}」。"
    }

    /** 给模型看的编号：UUID 太长，取前几位，取消时按前缀找。 */
    private fun shortId(id: String) = id.take(SHORT_ID_LENGTH)

    companion object {
        private const val SHORT_ID_LENGTH = 6

        /** 标题最多几个字：任务页卡片上一行放得下，通知的标题也不会被截。 */
        private const val MAX_TITLE_LENGTH = 16
    }
}

/** 日程：直接读写系统日历，见 [CalendarEvents]。 */
class CalendarTool(private val context: Context) : AgentTool {

    override val name = CALENDAR

    override val description =
        "读写手机的日历：加一个日程，或者查某几天有哪些安排。用户说「周五下午两点开会，记一下」" +
            "「我明天有什么安排」时用。时间是本地时间，不知道今天几号的话先调 device_status。"

    override val parameters = objectSchema(
        "action" to enumParam("add 加日程，list 查日程", listOf("add", "list")),
        "title" to stringParam("日程的名字。action=add 时要给。"),
        "start" to stringParam("开始时间 yyyy-MM-dd HH:mm；全天的日程只写日期 yyyy-MM-dd。action=add 时要给。"),
        "end" to stringParam("结束时间，格式同 start，可以不给（默认一小时，全天的就是当天）。"),
        "location" to stringParam("地点，可以不给。"),
        "notes" to stringParam("备注，可以不给。"),
        "remind_minutes" to intParam("提前几分钟让日历提醒，可以不给。"),
        "from" to stringParam("action=list 时从哪天查起，yyyy-MM-dd，默认今天。"),
        "days" to intParam("action=list 时查几天，默认 7，最多 31。"),
        required = listOf("action"),
    )

    override suspend fun execute(args: JSONObject): String {
        if (!SystemPermissions.hasCalendar(context)) return SystemPermissions.hint("日历")
        return when (args.optString("action")) {
            "add" -> add(args)
            "list" -> list(args)
            else -> "ERROR bad_args: action 只能是 add、list。"
        }
    }

    private fun add(args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return "ERROR bad_args: 没有给 title。"
        val startText = args.optString("start").trim()
        val endText = args.optString("end").trim()
        val zone = ZoneId.systemDefault()
        val allDay = parseDateTime(startText) == null && parseDate(startText) != null
        val (begin, end) = if (allDay) {
            // 全天的日程：从当天零点到结束那天的第二天零点，按 UTC 存（日历的规矩）。
            val startDate = parseDate(startText)!!
            val endDate = parseDate(endText) ?: startDate
            startDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli() to
                endDate.plusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        } else {
            val start = parseDateTime(startText)
                ?: return "ERROR bad_args: start 要写成 yyyy-MM-dd HH:mm（全天的写 yyyy-MM-dd）。"
            val finish = parseDateTime(endText) ?: start.plusHours(1)
            if (!finish.isAfter(start)) return "ERROR bad_args: end 要比 start 晚。"
            start.atZone(zone).toInstant().toEpochMilli() to finish.atZone(zone).toInstant().toEpochMilli()
        }
        val remind = args.optInt("remind_minutes", -1).takeIf { it >= 0 }
        CalendarEvents.add(
            context = context,
            title = title,
            beginMillis = begin,
            endMillis = end,
            allDay = allDay,
            location = args.optString("location").trim(),
            notes = args.optString("notes").trim(),
            remindMinutes = remind,
        )?.let { return "ERROR calendar_failed: $it" }
        val whenText = if (allDay) formatDate(parseDate(startText)!!) + "（全天）" else formatMillis(begin)
        return "OK 已在日历里加上：$whenText「$title」" + (remind?.let { "，提前 $it 分钟提醒" }.orEmpty()) + "。"
    }

    private fun list(args: JSONObject): String {
        val zone = ZoneId.systemDefault()
        val from = parseDate(args.optString("from").trim()) ?: LocalDate.now(zone)
        val days = args.optInt("days", DEFAULT_DAYS).coerceIn(1, MAX_DAYS)
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = from.plusDays(days.toLong()).atStartOfDay(zone).toInstant().toEpochMilli()
        val events = CalendarEvents.list(context, begin, end, MAX_EVENTS) ?: return SystemPermissions.hint("日历")
        val range = "${formatDate(from)} 起 $days 天"
        if (events.isEmpty()) return "$range 没有日程。"
        return "$range 的日程：\n" + events.joinToString("\n") { event ->
            val time = if (event.allDay) "${formatDate(Instant.ofEpochMilli(event.beginMillis).atZone(ZoneId.of("UTC")).toLocalDate())} 全天"
            else "${formatMillis(event.beginMillis)}–${formatMillis(event.endMillis, timeOnly = true)}"
            "$time　${event.title}" + (event.location?.let { "（$it）" }.orEmpty())
        }
    }

    companion object {
        private const val DEFAULT_DAYS = 7
        private const val MAX_DAYS = 31

        /** 一次最多列多少个日程：一个月的日程可能很多，太多了模型也看不过来。 */
        private const val MAX_EVENTS = 50
    }
}

/** 手机此刻的状态，见 [DeviceStatus]。 */
class DeviceStatusTool(private val context: Context) : AgentTool {

    override val name = DEVICE_STATUS

    override val description =
        "看看手机此刻的状态：现在几号几点星期几、电量和充电、网络、存储空间、内存、响铃模式。" +
            "定闹钟、排提醒、加日程之前先用它拿准今天的日期；用户问「还剩多少电」「手机还有多少空间」时也用。"

    override val parameters = noParams()

    override suspend fun execute(args: JSONObject): String = DeviceStatus.describe(context)
}

/** 应用使用记录，见 [AppUsage]。 */
class AppUsageTool(private val context: Context) : AgentTool {

    override val name = APP_USAGE

    override val description =
        "查应用使用记录：最近几天每个应用用了多久、最近一次什么时候用的，按时长从长到短。" +
            "用户问「我今天玩了多久手机」「最近常用什么应用」时用。"

    override val parameters = objectSchema(
        "days" to intParam("查最近几天，1 是今天（默认），最多 $MAX_DAYS。"),
        "limit" to intParam("最多列几个应用，默认 $DEFAULT_LIMIT。"),
    )

    override suspend fun execute(args: JSONObject): String {
        val days = args.optInt("days", 1).coerceIn(1, MAX_DAYS)
        val limit = args.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        val entries = AppUsage.top(context, days, limit) ?: return SystemPermissions.hint("使用情况访问")
        val range = if (days == 1) "今天" else "最近 $days 天"
        if (entries.isEmpty()) return "$range 没有应用使用记录。"
        val total = entries.sumOf { it.foregroundMillis }
        return "$range 用得最多的应用（前 ${entries.size} 个一共 ${durationText(Duration.ofMillis(total))}）：\n" +
            entries.joinToString("\n") {
                "${it.label}：${durationText(Duration.ofMillis(it.foregroundMillis))}，最近一次 ${formatMillis(it.lastUsedMillis)}"
            }
    }

    companion object {
        private const val DEFAULT_LIMIT = 10
        private const val MAX_LIMIT = 30
        private const val MAX_DAYS = 7
    }
}

private fun parseTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim(), TimeInput) }.getOrNull()

/** 认 `yyyy-MM-dd HH:mm` 和中间用 T 的写法。 */
private fun parseDateTime(text: String): LocalDateTime? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return try {
        LocalDateTime.parse(trimmed.replace('T', ' '), DateTimeInput)
    } catch (e: DateTimeParseException) {
        null
    }
}

private fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim()) }.getOrNull()

private fun LocalDateTime.toMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** "11月1日 周六 15:00"：连星期一起说，日期算错了模型和用户都一眼看得出来。 */
private fun formatMillis(millis: Long, timeOnly: Boolean = false): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return time.format(if (timeOnly) ClockFormat else DateTimeOutput)
}

private fun formatDate(date: LocalDate): String = date.format(DateOutput)

/** "2 小时 57 分钟"这种说法。不到一分钟的按秒说。 */
private fun durationText(duration: Duration): String {
    val totalMinutes = duration.toMinutes()
    if (totalMinutes < 1) return "${duration.seconds.coerceAtLeast(0)} 秒"
    val days = totalMinutes / (24 * 60)
    val hours = (totalMinutes / 60) % 24
    val minutes = totalMinutes % 60
    return buildList {
        if (days > 0) add("$days 天")
        if (hours > 0) add("$hours 小时")
        if (minutes > 0) add("$minutes 分钟")
    }.joinToString(" ")
}

private val TimeInput = DateTimeFormatter.ofPattern("H:mm")
private val DateTimeInput = DateTimeFormatter.ofPattern("yyyy-M-d H:mm")
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val DateTimeOutput = DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA)
private val DateOutput = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)

private val AllDays = listOf(
    Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY,
    Calendar.SATURDAY, Calendar.SUNDAY,
)
private val WorkDays = AllDays.take(5)
private val WeekendDays = AllDays.drop(5)
private val RepeatText = mapOf("everyday" to "每天响", "workdays" to "周一到周五每天响", "weekends" to "周六周日响")
