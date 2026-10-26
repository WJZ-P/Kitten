package me.wjz.kitten.data.stats

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import me.wjz.kitten.llm.TokenUsage
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * 某一天用掉的 token，[TokenStatsStore] 按天累加。
 *
 * 四个数的口径同 [TokenUsage]：[inputTokens] 是按原价算的那部分输入，命中和写入缓存的另算。
 */
data class DailyTokens(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    /** 这一天发了几次请求。Agent 一轮任务要发十几次，所以它比"对话次数"大得多。 */
    val requests: Int = 0,
) {
    /** 这一天一共用了多少：输入（原价 + 缓存读写）加输出。看板上的格子按它上色。 */
    val total: Long get() = inputTokens + cacheReadTokens + cacheWriteTokens + outputTokens

    /** 输入里命中缓存的比例，0..1。没有输入时为 0。 */
    val cacheHitRate: Float
        get() {
            val prompt = inputTokens + cacheReadTokens + cacheWriteTokens
            return if (prompt == 0L) 0f else cacheReadTokens.toFloat() / prompt
        }

    operator fun plus(usage: TokenUsage): DailyTokens = DailyTokens(
        inputTokens = inputTokens + usage.inputTokens,
        outputTokens = outputTokens + usage.outputTokens,
        cacheReadTokens = cacheReadTokens + usage.cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens + usage.cacheWriteTokens,
        requests = requests + 1,
    )
}

/**
 * 每天用了多少 token，给设置页的用量看板用（见 TokenUsageSection）。
 *
 * 存在 SharedPreferences 里，一天一条，键是 `2026-10-26` 这样的日期。一年也就几百条，整份读出来
 * 足够快，不值得为它建一张表。按本地时区分天：用户心里的"今天"就是手机上的日期。
 *
 * 只是给用户看个大概，不是账单：各家报用量的口径不一（见 [TokenUsage]），有的兼容服务干脆不报，
 * 那几次请求就记不上。
 */
object TokenStatsStore {

    private const val TAG = "KittenStats"
    private const val PREFS = "kitten_token_stats"

    private val _revision = MutableStateFlow(0)

    /** 每记一笔就加一。看板盯着它，变了就重读，任务跑着的时候格子会跟着变深。 */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * 把一次请求的用量加到 [date] 那天的账上。
     *
     * 加锁：读出来、加上、写回去是三步，两次请求的回调挤在一起时不能互相覆盖。
     */
    fun record(context: Context, usage: TokenUsage, date: LocalDate = LocalDate.now()) {
        val key = date.toString()
        synchronized(this) {
            val prefs = prefs(context)
            val today = prefs.getString(key, null)?.let(::decode) ?: DailyTokens()
            prefs.edit { putString(key, encode(today + usage)) }
        }
        _revision.update { it + 1 }
    }

    /** 读出全部记录。解析不了的那一条直接跳过，不让一条坏数据把看板拖垮。 */
    fun load(context: Context): Map<LocalDate, DailyTokens> =
        prefs(context).all.mapNotNull { (key, value) ->
            val date = try {
                LocalDate.parse(key)
            } catch (e: DateTimeParseException) {
                Log.w(TAG, "跳过一条认不出日期的记录：$key")
                return@mapNotNull null
            }
            val day = (value as? String)?.let(::decode) ?: return@mapNotNull null
            date to day
        }.toMap()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 字段名缩短：一天一条、存一年，没必要每条都带完整的英文名。 */
    private fun encode(day: DailyTokens): String = JSONObject()
        .put("in", day.inputTokens)
        .put("out", day.outputTokens)
        .put("cr", day.cacheReadTokens)
        .put("cw", day.cacheWriteTokens)
        .put("n", day.requests)
        .toString()

    private fun decode(raw: String): DailyTokens? = runCatching {
        val json = JSONObject(raw)
        DailyTokens(
            inputTokens = json.optLong("in"),
            outputTokens = json.optLong("out"),
            cacheReadTokens = json.optLong("cr"),
            cacheWriteTokens = json.optLong("cw"),
            requests = json.optInt("n"),
        )
    }.getOrNull()
}
