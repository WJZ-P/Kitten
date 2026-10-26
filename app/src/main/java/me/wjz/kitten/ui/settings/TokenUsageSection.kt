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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.data.stats.DailyTokens
import me.wjz.kitten.data.stats.TokenStatsStore
import me.wjz.kitten.ui.component.GlassContentPadding
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
 * 数据见 [TokenStatsStore]。
 */
@Composable
fun TokenUsageSection(backdrop: Backdrop) {
    val context = LocalContext.current
    val revision by TokenStatsStore.revision.collectAsState()
    val days = remember(revision) { TokenStatsStore.load(context) }
    // 每次重读时顺手对一下今天是哪天：开着设置页跨过零点，下一次有用量进来就换到新的一格。
    val today = remember(revision) { LocalDate.now() }
    TokenBoard(backdrop = backdrop, title = "Token 看板", days = days, today = today)
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

/** 点了一格之后 Toast 说的话。短一点、俏皮一点。 */
private fun dayMessage(date: LocalDate, day: DailyTokens?, today: LocalDate): String {
    val label = when {
        date == today -> "今天"
        date == today.minusDays(1) -> "昨天"
        date.year == today.year -> "${date.monthValue}月${date.dayOfMonth}日"
        else -> "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
    }
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
private fun formatTokens(count: Long): String = when {
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

/** 下面那行"近 N 天"的 N。 */
private const val RecentDays = 30
