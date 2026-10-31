package me.wjz.kitten.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.data.stats.DailyTokens
import me.wjz.kitten.data.stats.TokenStatsStore
import me.wjz.kitten.ui.chat.ModelBrandIcon
import me.wjz.kitten.ui.chat.modelBrandOf
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.LocalToast
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 设置页上的"Token 看板"：一天一个小格子，用得越多颜色越深，和 GitHub 的贡献图一个样子。点一格，
 * 用 Toast 说那天用了多少。表头右边是一共用了多少。
 *
 * ## 格子怎么排
 *
 * 从左上角开始，一天一格往下排，一列排满 7 格换到右边一列。第一格是最早有记录的那天——第一次用
 * 的话就是今天，今天在左上角，以后一天天往下、往右长。格子都是纯色块，不描边。
 *
 * 格子铺满整张卡片的宽度：卡片多宽，就按"每格至少 [MinCellSize]"算出能放几列，再把每格放大到正好
 * 铺满。太小手指点不准。
 *
 * 天数多到一屏放不下时，整张图能左右滑，默认停在最右边（最新的几天）：新的一天进来，最老的那一列
 * 就被挤到左边看不见的地方，往左滑能翻回去。还没到的日子画成很淡的空格，图一开始也是满的。
 *
 * 格子图下面还有三张卡片，从"多少"到"谁用的"再到"用在哪"：概览（[OverviewCard]）、按模型
 * （[ModelUsageCard]）、花在哪了（[BreakdownCard]）。
 *
 * 数据见 [TokenStatsStore]。
 */
@Composable
fun TokenUsageSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val revision by TokenStatsStore.revision.collectAsState()
    val days = remember(revision) { TokenStatsStore.load(context) }
    val models = remember(revision) { TokenStatsStore.loadModels(context) }
    // 每次重读时顺手对一下今天是哪天：开着设置页跨过零点，下一次有用量进来就换到新的一格。
    val today = remember(revision) { LocalDate.now() }
    val allTime = remember(days) { days.values.sumOf { it.total } }
    // 卡片放在"Token 看板"二级页里，页面标题已经叫 Token 看板了，卡片表头换个说法，不重复。
    TokenBoard(backdrop = backdrop, title = "每日用量", days = days, today = today)
    // 下面三张卡片：先看几个数，再看是哪个模型用的，最后看用在了哪儿。
    OverviewCard(backdrop = backdrop, days = days, today = today)
    ModelUsageCard(backdrop = backdrop, models = models, allTime = allTime)
    BreakdownCard(backdrop = backdrop, days = days)
}

/** 一共用了多少 token，设置首页那一行的说明用。有新用量进来会跟着变。 */
@Composable
internal fun rememberAllTimeTokens(): Long {
    val context = LocalContext.current
    val revision by TokenStatsStore.revision.collectAsState()
    return remember(revision) { TokenStatsStore.load(context).values.sumOf { it.total } }
}

/** 看板的卡片：表头带总数、格子图、底下一行"今天 · 近 N 天"和图例。 */
@Composable
private fun TokenBoard(
    backdrop: Backdrop,
    title: String,
    days: Map<LocalDate, DailyTokens>,
    today: LocalDate,
) {
    val toast = LocalToast.current
    // 第一格是哪天：最早有记录的那天；一条记录都没有（第一次用）就是今天。
    val firstDay = remember(days, today) { days.keys.minOrNull()?.let { minOf(it, today) } ?: today }
    val recent = remember(days, today) {
        days.filterKeys { !it.isBefore(today.minusDays(RecentDays - 1L)) && !it.isAfter(today) }
            .values.sumOf { it.total }
    }
    val allTime = remember(days) { days.values.sumOf { it.total } }

    GlassSection(
        backdrop = backdrop,
        title = title,
        icon = Icons.Filled.DateRange,
        // 表头右边是一共用了多少：最想知道的那个数，放在最显眼的地方。
        headerTrailing = {
            Text(
                text = "${formatTokens(allTime)} tokens",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = KittenTheme.colors.accentStrong,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TokenHeatmap(
                days = days,
                firstDay = firstDay,
                today = today,
                onDayClick = { date -> toast.show(dayMessage(date, days[date], today)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "今天 ${formatTokens(days[today]?.total ?: 0L)} · 近 $RecentDays 天 ${formatTokens(recent)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = KittenTheme.colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Legend()
            }
        }
    }
}

/**
 * 格子图本体。所有格子画在同一块画布上，按下的是哪一格由手指位置算出来：几百个格子各挂一个
 * 触摸处理太浪费，一块画布一个就够。
 *
 * 交互照老规矩：按下那一格就缩一下，在同一格上抬手才算点了它；按住滑出那一格、或者开始左右滑动
 * 整张图，都不算。
 */
@Composable
private fun TokenHeatmap(
    days: Map<LocalDate, DailyTokens>,
    firstDay: LocalDate,
    today: LocalDate,
    onDayClick: (LocalDate) -> Unit,
) {
    val colors = KittenTheme.colors
    val currentOnDayClick by rememberUpdatedState(onDayClick)
    val press = rememberGlassPressState()
    // 有日子的格子数：第一天到今天。再往后的是还没到的空格。
    val dayCount = (ChronoUnit.DAYS.between(firstDay, today) + 1).toInt().coerceAtLeast(1)
    val levels = remember(days, firstDay, dayCount) {
        val totals = days.values.map { it.total }
        val thresholds = levelThresholds(totals)
        val most = totals.maxOrNull() ?: 0L
        IntArray(dayCount) { levelOf(days[firstDay.plusDays(it.toLong())]?.total ?: 0L, thresholds, most) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // 按"每格至少多大"算一屏放几列，再把每格放大到正好铺满宽度。
        val visibleColumns = ((maxWidth + CellGap) / (MinCellSize + CellGap)).toInt().coerceAtLeast(1)
        val cellSize = (maxWidth - CellGap * (visibleColumns - 1)) / visibleColumns
        val columns = max(visibleColumns, (dayCount + Rows - 1) / Rows)
        val gridWidth = cellSize * columns + CellGap * (columns - 1)
        val gridHeight = cellSize * Rows + CellGap * (Rows - 1)
        // 一开始停在最右边：初始值给到最大，量完之后会被收回到真正能滚到的最远处。列数变了
        // （新的一天挤进来多了一列）就换一个，重新停到最右边。
        val scroll = remember(columns) { ScrollState(initial = Int.MAX_VALUE) }
        val density = LocalDensity.current
        val cellPx = with(density) { cellSize.toPx() }
        val stepPx = cellPx + with(density) { CellGap.toPx() }

        // 手指在哪一格。落在两格之间的缝里算前一格：手指是一块面，缝里不响应会让人觉得没点中。
        // 还没到的日子不能点，返回 null。
        fun indexAt(position: Offset): Int? {
            if (!position.isSpecified || position.x < 0f || position.y < 0f) return null
            val row = (position.y / stepPx).toInt().coerceAtMost(Rows - 1)
            val index = (position.x / stepPx).toInt() * Rows + row
            return index.takeIf { it < dayCount }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
        ) {
            Canvas(
                modifier = Modifier
                    .size(width = gridWidth, height = gridHeight)
                    .semantics { contentDescription = "最近 $dayCount 天的 token 用量，点格子看当天用了多少" }
                    .pointerInput(press, stepPx, dayCount, firstDay) {
                        detectGlassPress(press) {
                            val index = indexAt(press.downPosition) ?: return@detectGlassPress
                            // 在同一格上抬手才算：按住滑到别的格子再松手，不是点了哪一格。
                            if (indexAt(press.position) == index) {
                                currentOnDayClick(firstDay.plusDays(index.toLong()))
                            }
                        }
                    },
            ) {
                val pressed = indexAt(press.downPosition)
                val radius = cellPx * CellCornerFraction
                for (index in 0 until columns * Rows) {
                    val future = index >= dayCount
                    // 按下的那一格缩一下；松手时 pressProgress 会略冲过 0，正好弹回来。
                    val scale = if (index == pressed) lerp(1f, PressedScale, press.pressProgress) else 1f
                    val side = cellPx * scale
                    val topLeft = Offset(
                        x = (index / Rows) * stepPx + (cellPx - side) / 2f,
                        y = (index % Rows) * stepPx + (cellPx - side) / 2f,
                    )
                    // 纯色块，不描边，和 GitHub 的贡献图一样。今天也不另做标记：它就是还没到的
                    // 淡格子前面那一格。
                    drawRoundRect(
                        color = if (future) colors.futureCell() else colors.levelColor(levels[index]),
                        topLeft = topLeft,
                        size = Size(side, side),
                        cornerRadius = CornerRadius(radius * scale),
                    )
                }
            }
        }
    }
}

/** "少 ▢▢▢▢▢ 多"：右下角的图例，五档颜色从浅到深。 */
@Composable
private fun Legend() {
    val colors = KittenTheme.colors
    val shape = RoundedCornerShape(LegendSize * CellCornerFraction)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text("少", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
        for (level in 0..MaxLevel) {
            Box(
                modifier = Modifier
                    .size(LegendSize)
                    .background(colors.levelColor(level), shape),
            )
        }
        Text("多", style = MaterialTheme.typography.labelSmall, color = colors.textTertiary)
    }
}

/**
 * 分档的门槛：有用量的那些天按用量从少到多排好，取排在 1/4、1/2、3/4 处的那几天的用量。和 GitHub
 * 的贡献图一个路子。
 *
 * 不按"占最多那天的几成"分：只要有一天跑了个大任务（上亿很常见），别的日子都不到它的四分之一，
 * 全挤在最浅那一档，整张图一片白。按排名分，不管有没有特别大的一天，四档都分得开。
 */
private fun levelThresholds(totals: Collection<Long>): LongArray {
    val sorted = totals.filter { it > 0L }.sorted()
    if (sorted.isEmpty()) return LongArray(0)
    return longArrayOf(sorted[sorted.size / 4], sorted[sorted.size / 2], sorted[sorted.size * 3 / 4])
}

/**
 * 某天落在第几档：一点没用是 0；有用就从第 1 档起，每超过一道门槛（[levelThresholds]）深一档。
 *
 * 用得最多的那天直接给最深一档：刚开始用、只有一两天数据时门槛都一样高，不这样的话第一天就是最浅
 * 的那一格，看着像没怎么用。
 */
private fun levelOf(total: Long, thresholds: LongArray, most: Long): Int {
    if (total <= 0L) return 0
    if (total >= most) return MaxLevel
    return 1 + thresholds.count { total > it }
}

/** 第 [level] 档的颜色：0 档是空格子（和卡片里的控件同一种底色），往上是越来越浓的天依蓝。 */
private fun KittenColors.levelColor(level: Int): Color =
    if (level <= 0) controlTint else accent.copy(alpha = LevelAlphas[level - 1])

/** 还没到的日子：比空格子还淡，看得出格子在那儿，又不会被当成"那天没用"。 */
private fun KittenColors.futureCell(): Color = controlTint.copy(alpha = controlTint.alpha * FutureCellAlpha)

/**
 * "概览"：四个小格子，一格说一个数——近 7 天用了多少（和再往前 7 天比）、一共发了几次请求、每天
 * 平均用多少、最忙的是哪天。格子是卡片里亮一档的底色，和卡片里别的控件一样。
 */
@Composable
private fun OverviewCard(backdrop: Backdrop, days: Map<LocalDate, DailyTokens>, today: LocalDate) {
    val overview = remember(days, today) { overviewOf(days, today) }
    GlassSection(backdrop = backdrop, title = "概览", icon = Icons.Filled.Info, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(TileGap),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                StatTile(
                    label = "近 7 天",
                    value = formatTokens(overview.lastWeek),
                    note = weekNote(overview.lastWeek, overview.weekBefore),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = "请求次数",
                    value = formatTokens(overview.requests.toLong()),
                    unit = "次",
                    note = "今天 ${overview.todayRequests} 次",
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TileGap)) {
                StatTile(
                    label = "日均",
                    value = formatTokens(overview.dailyAverage),
                    note = "近 ${overview.averageDays} 天",
                    modifier = Modifier.weight(1f),
                )
                val busiest = overview.busiest
                StatTile(
                    label = "最忙的一天",
                    value = if (busiest != null) formatTokens(busiest.second) else "—",
                    note = if (busiest != null) dayLabel(busiest.first, today) else "还没有",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 概览里的几个数，见 [overviewOf]。 */
private class Overview(
    val lastWeek: Long,
    val weekBefore: Long,
    val requests: Int,
    val todayRequests: Int,
    val dailyAverage: Long,
    val averageDays: Int,
    /** 用得最多的那天和那天的用量。一天都没用过是 null。 */
    val busiest: Pair<LocalDate, Long>?,
)

/**
 * 算概览里的几个数。
 *
 * 日均按"近 30 天"算，但不早于第一次用的那天：才用了三天的话除以 3，不是除以 30，不然显得少得多。
 */
private fun overviewOf(days: Map<LocalDate, DailyTokens>, today: LocalDate): Overview {
    fun sumBetween(from: LocalDate, to: LocalDate) =
        days.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sumOf { it.total }

    val firstDay = days.keys.minOrNull()?.let { minOf(it, today) } ?: today
    val averageFrom = maxOf(firstDay, today.minusDays(RecentDays - 1L))
    val averageDays = (ChronoUnit.DAYS.between(averageFrom, today) + 1).toInt()
    return Overview(
        lastWeek = sumBetween(today.minusDays(6), today),
        weekBefore = sumBetween(today.minusDays(13), today.minusDays(7)),
        requests = days.values.sumOf { it.requests },
        todayRequests = days[today]?.requests ?: 0,
        dailyAverage = sumBetween(averageFrom, today) / averageDays,
        averageDays = averageDays,
        busiest = days.entries.maxByOrNull { it.value.total }
            ?.takeIf { it.value.total > 0L }
            ?.let { it.key to it.value.total },
    )
}

/** "近 7 天"那一格下面的小字：和再往前 7 天比，多了还是少了。 */
private fun weekNote(lastWeek: Long, weekBefore: Long): String {
    if (weekBefore == 0L) return if (lastWeek == 0L) "这周还没用" else "前 7 天没用"
    val percent = ((lastWeek - weekBefore) * 100.0 / weekBefore).roundToInt()
    return when {
        percent > 0 -> "比前 7 天多 $percent%"
        percent < 0 -> "比前 7 天少 ${-percent}%"
        else -> "和前 7 天差不多"
    }
}

/**
 * 概览里的一格：上面一行灰色的名字，中间一个大数，下面一行小字说明。
 *
 * @param unit 跟在大数后面的小字单位（"次"）。token 数不写单位：整页都在说 token。
 */
@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    note: String? = null,
) {
    val colors = KittenTheme.colors
    Column(
        modifier = modifier
            .clip(TileShape)
            .background(colors.controlTint)
            .padding(horizontal = TilePaddingHorizontal, vertical = TilePaddingVertical),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                modifier = Modifier.alignByBaseline(),
            )
            if (unit != null) {
                Text(
                    text = " $unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * "按模型"：每个模型用了多少，多的排前面。每行一个品牌图标（和输入栏切换模型的按钮同一套，按模型名认）、
 * 模型名、用量和占比，下面一根占比条。
 *
 * 只列前 [MaxModelRows] 个，再多的并成一行"其他"。按模型记账是后来才加的，更早的用量分不出是哪个
 * 模型，算成最后一行"更早的"：这样各行加起来正好是"每日用量"表头那个总数，占比条也是按它算的。
 */
@Composable
private fun ModelUsageCard(backdrop: Backdrop, models: Map<String, DailyTokens>, allTime: Long) {
    val rows = remember(models, allTime) { modelRowsOf(models, allTime) }
    GlassSection(backdrop = backdrop, title = "按模型", icon = Icons.Filled.Star, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(ModelRowGap),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = "还没有用量，聊几句就有啦～",
                    style = MaterialTheme.typography.bodySmall,
                    color = KittenTheme.colors.textTertiary,
                )
            }
            rows.forEach { row -> ModelUsageLine(row = row, allTime = allTime) }
        }
    }
}

/** "按模型"里的一行：一个模型，或者并起来的"其他"、分不出模型的"更早的"。 */
private sealed interface ModelUsageRow {
    val tokens: Long

    data class Model(val name: String, override val tokens: Long) : ModelUsageRow

    data class Others(val count: Int, override val tokens: Long) : ModelUsageRow

    data class Earlier(override val tokens: Long) : ModelUsageRow
}

private fun modelRowsOf(models: Map<String, DailyTokens>, allTime: Long): List<ModelUsageRow> {
    val sorted = models.map { (name, tally) -> name to tally.total }
        .filter { it.second > 0L }
        .sortedByDescending { it.second }
    val rows = mutableListOf<ModelUsageRow>()
    sorted.take(MaxModelRows).forEach { (name, tokens) -> rows += ModelUsageRow.Model(name, tokens) }
    val rest = sorted.drop(MaxModelRows)
    if (rest.isNotEmpty()) rows += ModelUsageRow.Others(rest.size, rest.sumOf { it.second })
    val earlier = allTime - sorted.sumOf { it.second }
    if (earlier > 0L) rows += ModelUsageRow.Earlier(earlier)
    return rows
}

@Composable
private fun ModelUsageLine(row: ModelUsageRow, allTime: Long) {
    val colors = KittenTheme.colors
    val share = if (allTime > 0L) (row.tokens.toFloat() / allTime).coerceIn(0f, 1f) else 0f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ModelIconGap),
    ) {
        when (row) {
            is ModelUsageRow.Model -> ModelBrandIcon(
                brand = remember(row.name) { modelBrandOf(preset = null, model = row.name) },
                modifier = Modifier.size(ModelIconSize),
            )
            is ModelUsageRow.Others -> Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(ModelIconSize),
            )
            is ModelUsageRow.Earlier -> Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(ModelIconSize),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (row) {
                        is ModelUsageRow.Model -> row.name
                        is ModelUsageRow.Others -> "其他 ${row.count} 个模型"
                        is ModelUsageRow.Earlier -> "更早的（那时还没按模型记）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row is ModelUsageRow.Model) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatTokens(row.tokens)} · ${formatShare(share)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            ShareBar(
                share = share,
                color = if (row is ModelUsageRow.Model) colors.accent else colors.textTertiary,
            )
        }
    }
}

/** 一根占比条：浅色的底，从左往右填 [share] 那么长。 */
@Composable
private fun ShareBar(share: Float, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BarHeight)
            .clip(CircleShape)
            .background(KittenTheme.colors.controlTint),
    ) {
        if (share > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** token 都用在哪了：按原价算的输入、命中缓存的输入、写进缓存的输入、输出，见 [BreakdownCard]。 */
private enum class TokenPart(val label: String) {
    Input("输入"),
    CacheRead("缓存命中"),
    CacheWrite("缓存写入"),
    Output("输出"),
}

/** 每一块的颜色：设置页图标那组分类色里挑四个分得开的。 */
private fun KittenColors.partColor(part: TokenPart): Color = when (part) {
    TokenPart.Input -> iconBlue
    TokenPart.CacheRead -> iconTeal
    TokenPart.CacheWrite -> iconViolet
    TokenPart.Output -> iconCoral
}

/**
 * "花在哪了"：一共用掉的 token 拆成四块（[TokenPart]），一根分成几段的条，下面一块一行写着用了多少、
 * 占几成。
 *
 * Agent 每走一步都要把整段对话重发一遍，输入天然占大头；命中缓存的那部分各家都按折扣算。看这张卡片
 * 能知道缓存有没有帮上忙：缓存命中那一段越长越好。有的服务不报缓存用量，那两行就一直是 0。
 */
@Composable
private fun BreakdownCard(backdrop: Backdrop, days: Map<LocalDate, DailyTokens>) {
    val colors = KittenTheme.colors
    val parts = remember(days) {
        listOf(
            TokenPart.Input to days.values.sumOf { it.inputTokens },
            TokenPart.CacheRead to days.values.sumOf { it.cacheReadTokens },
            TokenPart.CacheWrite to days.values.sumOf { it.cacheWriteTokens },
            TokenPart.Output to days.values.sumOf { it.outputTokens },
        )
    }
    val total = parts.sumOf { it.second }
    GlassSection(backdrop = backdrop, title = "花在哪了", icon = Icons.AutoMirrored.Filled.List, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (total == 0L) {
                Text(
                    text = "还没有用量～",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                )
            } else {
                // 分成几段的条：每段按用量占的比例分宽度，段和段之间留一道细缝。用量是 0 的那块不画。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(StackedBarHeight)
                        .clip(CircleShape),
                    horizontalArrangement = Arrangement.spacedBy(StackedBarGap),
                ) {
                    parts.filter { it.second > 0L }.forEach { (part, tokens) ->
                        Box(
                            modifier = Modifier
                                .weight(tokens.toFloat())
                                .fillMaxHeight()
                                .background(colors.partColor(part)),
                        )
                    }
                }
                parts.forEach { (part, tokens) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(PartDotSize)
                                .background(colors.partColor(part), CircleShape),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = part.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textPrimary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${formatTokens(tokens)} · ${formatShare(tokens.toFloat() / total)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.textSecondary,
                        )
                    }
                }
                Text(
                    text = "Kitten 每走一步都要把前面的对话重读一遍，所以输入占大头～命中缓存的那部分会便宜不少。",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/** 占比写成百分数，取整；有一点但不到 1% 的写"<1%"，不写成 0%。 */
private fun formatShare(share: Float): String {
    val percent = (share * 100).roundToInt()
    return if (percent == 0 && share > 0f) "<1%" else "$percent%"
}

/** 一天在界面上怎么叫：今天、昨天、几月几日，不是今年的带上年份。 */
private fun dayLabel(date: LocalDate, today: LocalDate): String = when {
    date == today -> "今天"
    date == today.minusDays(1) -> "昨天"
    date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
    else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}

/** 点了一格之后 Toast 说的话。短一点、俏皮一点。 */
private fun dayMessage(date: LocalDate, day: DailyTokens?, today: LocalDate): String {
    val label = dayLabel(date, today)
    if (day == null || day.total == 0L) {
        return if (date == today) "今天还没用 token 呢～" else "${label}一点 token 都没用，Kitten 在晒太阳～"
    }
    val cacheHit = (day.cacheHitRate * 100).roundToInt()
    return buildString {
        append("${label}用了 ${formatTokens(day.total)} token")
        if (cacheHit > 0) append("，缓存命中 $cacheHit%")
        append("～")
    }
}

/**
 * token 数换成好读的样子：一万以下照写；一万起按"万"、一亿起按"亿"，都写一位小数（整数就不写 .0），
 * 比如 8532、12.3 万、3 亿。Agent 每步都重发整段历史，累计上亿很常见。
 */
internal fun formatTokens(count: Long): String = when {
    count >= 100_000_000L -> String.format(Locale.ROOT, "%.1f 亿", count / 1e8)
    count >= 10_000L -> String.format(Locale.ROOT, "%.1f 万", count / 1e4)
    else -> count.toString()
}.replace(".0 ", " ")

/** 一列几格：一周七天，和 GitHub 的图一样高。 */
private const val Rows = 7

/**
 * 每格至少多大。按指尖大小给：再小就点不准了。实际会更大一点，好铺满卡片的宽度（一般手机上
 * 一屏 9 列，每格三十几 dp）。
 */
private val MinCellSize = 30.dp

/** 格子之间的缝。 */
private val CellGap = 4.dp

/**
 * 格子圆角占边长的比例。三十几 dp 的格子正好落在卡片里小控件那一档（8dp）；图例的小方块按同一个
 * 比例，两边的形状一样。
 */
private const val CellCornerFraction = 0.24f

/** 按下的那一格缩到多小。 */
private const val PressedScale = 0.82f

/** 有用量的四档天依蓝的浓度，从浅到深。 */
private val LevelAlphas = floatArrayOf(0.35f, 0.55f, 0.78f, 1f)
private const val MaxLevel = 4

/** 还没到的日子比空格子再淡多少（乘在空格子的不透明度上）。 */
private const val FutureCellAlpha = 0.4f

/** 图例的小方块多大。 */
private val LegendSize = 10.dp

/** 下面那行"近 N 天"的 N。概览里的日均也按这么多天算。 */
private const val RecentDays = 30

// ---- 概览 ----

/** 概览里格子之间的缝。 */
private val TileGap = 10.dp

/** 概览格子的圆角：和卡片里别的小控件同一档（8dp）。 */
private val TileShape = RoundedCornerShape(GlassRadius.Small)

/** 概览格子里字到格子边的距离。 */
private val TilePaddingHorizontal = 12.dp
private val TilePaddingVertical = 10.dp

// ---- 按模型 ----

/** 最多列几个模型，再多的并成一行"其他"。 */
private const val MaxModelRows = 5

/** 模型图标多大、和右边的字隔多远。 */
private val ModelIconSize = 24.dp
private val ModelIconGap = 12.dp

/** 模型和模型之间隔多远。 */
private val ModelRowGap = 14.dp

/** 占比条多粗。 */
private val BarHeight = 4.dp

// ---- 花在哪了 ----

/** 分成几段的那根条多粗、段和段之间的缝。 */
private val StackedBarHeight = 10.dp
private val StackedBarGap = 2.dp

/** 每一行前面的色点多大。 */
private val PartDotSize = 8.dp
