package me.wjz.kitten.ui.task

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import me.wjz.kitten.system.reminder.Reminder
import me.wjz.kitten.ui.chat.ChatCardPadding
import me.wjz.kitten.ui.chat.chatCardGlass
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.LocalFrostedBackdrop
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.Alarm
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 任务页「定时任务」里排着的提醒：一条一张卡片。
 *
 * 卡片照着闹钟应用里的一条闹钟来：左边大大的几点几分，下面一行小字是哪天（今天、明天、11月3日 周一）；右边是
 * 提醒的标题和还有多久。整张卡能点，按下微微鼓起、松手才打开详情（[ReminderDetail]）：完整的时间、到点要说的
 * 内容、取消这个提醒。
 *
 * 取消放进详情里，不在卡片上常驻一个红色垃圾桶：卡片上只放"什么事、几点响"，删东西的按钮摆在列表上既扎眼又
 * 容易误点。
 *
 * "还有多久"每分钟走一次，对齐到整分；[animating] 为 false（任务页不是当前 tab）就不走，切回来立刻按现在重算。
 * 响过的、取消了的从列表里拿掉，下面的卡片缓缓挪上来。
 */
@Composable
internal fun ReminderList(
    reminders: List<Reminder>,
    animating: Boolean,
    onCancel: (Reminder) -> Unit,
    backdrop: Backdrop,
) {
    val now by produceState(System.currentTimeMillis(), animating) {
        value = System.currentTimeMillis()
        while (animating) {
            delay(MinuteMillis - value % MinuteMillis)
            value = System.currentTimeMillis()
        }
    }
    // 正在看哪条的详情。那条响了、被取消了，它就不在列表里了，详情跟着收起来。
    var openId by remember { mutableStateOf<String?>(null) }
    val open = reminders.firstOrNull { it.id == openId }
    // 收起的动画还在放时 open 已经是 null 了，窗里的内容不能跟着没：记着最后看的那一条。
    var lastOpen by remember { mutableStateOf<Reminder?>(null) }
    SideEffect { if (open != null) lastOpen = open }

    Column(
        modifier = Modifier.animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(CardGap),
    ) {
        reminders.forEach { reminder ->
            key(reminder.id) {
                ReminderCard(reminder = reminder, now = now, onOpen = { openId = reminder.id }, backdrop = backdrop)
            }
        }
    }

    ReminderDetail(
        visible = open != null,
        reminder = open ?: lastOpen,
        now = now,
        onCancel = { reminder ->
            onCancel(reminder)
            openId = null
        },
        onDismiss = { openId = null },
        backdrop = backdrop,
    )
}

@Composable
private fun ReminderCard(reminder: Reminder, now: Long, onOpen: () -> Unit, backdrop: Backdrop) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnOpen by rememberUpdatedState(onOpen)
    val time = remember(reminder.triggerAtMillis) { reminder.triggerAtMillis.toLocalTime() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "查看提醒") {
                    currentOnOpen()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnOpen() } }
            .chatCardGlass(
                backdrop,
                colors,
                frosted = LocalFrostedBackdrop.current,
                scale = { lerp(1f, PressedScale, press.pressProgress) },
            )
            .padding(horizontal = ChatCardPadding, vertical = CardVerticalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CardInnerGap),
    ) {
        // 几点几分要一眼看到：字大一号，下面一行小字是哪天。宽度给个下限，几张卡片的竖线对得齐。
        Column(modifier = Modifier.widthIn(min = TimeColumnMinWidth)) {
            Text(
                text = time.format(ClockFormat),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
                maxLines = 1,
            )
            Text(
                text = remember(reminder.triggerAtMillis, now) { dayLabel(time, now) },
                style = MaterialTheme.typography.labelMedium,
                color = colors.textTertiary,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(DividerHeight)
                .background(colors.hairline),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = remember(reminder.triggerAtMillis, now) { countdown(reminder.triggerAtMillis - now) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.accentStrong,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(ArrowSize),
        )
    }
}

/**
 * 一条提醒的详情：哪天几点、还有多久，到点要说的内容，最下面一个取消。取消前先点一下变成确认，再点一次才真的
 * 取消，和删技能、删人设一样。
 */
@Composable
private fun ReminderDetail(
    visible: Boolean,
    reminder: Reminder?,
    now: Long,
    onCancel: (Reminder) -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = reminder?.title,
        icon = KittenIcons.Alarm,
    ) { modalBackdrop ->
        if (reminder != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlassContentPadding)
                    .padding(top = 2.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = remember(reminder.triggerAtMillis) { reminder.triggerAtMillis.toLocalTime().format(FullFormat) },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = remember(reminder.triggerAtMillis, now) { countdown(reminder.triggerAtMillis - now) },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.accentStrong,
                    )
                }
                if (reminder.note.isNotBlank()) {
                    Text(
                        text = reminder.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                    )
                }
                Text(
                    text = "到点会一直响，点「知道了」才停～",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
                var confirming by remember(reminder.id) { mutableStateOf(false) }
                GlassButton(
                    text = if (confirming) "再点一次，确认取消" else "取消这个提醒",
                    onClick = {
                        if (confirming) onCancel(reminder) else confirming = true
                    },
                    backdrop = modalBackdrop,
                    style = GlassButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun Long.toLocalTime(): ZonedDateTime = Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault())

/** 哪天：近三天说今天、明天、后天，再远写日期和星期。 */
private fun dayLabel(time: ZonedDateTime, now: Long): String {
    val today = Instant.ofEpochMilli(now).atZone(time.zone).toLocalDate()
    return when (ChronoUnit.DAYS.between(today, time.toLocalDate())) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> time.format(DateFormat)
    }
}

/** 还有多久。往上取整到分钟：还差 30 秒时说"马上就响"，不说"0 分钟后"。 */
private fun countdown(millis: Long): String {
    val minutes = (millis + MinuteMillis - 1) / MinuteMillis
    return when {
        minutes <= 1 -> "马上就响"
        minutes < 60 -> "$minutes 分钟后"
        minutes < 24 * 60 -> "${minutes / 60} 小时" + (if (minutes % 60 > 0) " ${minutes % 60} 分" else "") + "后"
        else -> "${minutes / (24 * 60)} 天后"
    }
}

/** 卡片和卡片之间空多少，和"正在进行"里一样。 */
private val CardGap = 10.dp

/** 卡片上下留白：比任务卡片略小，一条提醒内容少，太高显得空。 */
private val CardVerticalPadding = 12.dp

/** 时间、竖线、标题之间空多少。 */
private val CardInnerGap = 14.dp

/** 时间那一列至少多宽：放得下"22:06"，几张卡片的竖线对齐。字号调大了放不下就跟着变宽。 */
private val TimeColumnMinWidth = 60.dp

/** 时间和标题之间那道竖线多高：比两行字略矮，看着是分隔，不是框。 */
private val DividerHeight = 32.dp

/** 末尾那个"›"多大，和任务卡片一样。 */
private val ArrowSize = 18.dp

/** 按下时卡片鼓到多大，和任务卡片一样。 */
private const val PressedScale = 1.02f

private const val MinuteMillis = 60_000L
private val ClockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val DateFormat = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
private val FullFormat = DateTimeFormatter.ofPattern("M月d日 EEE HH:mm", Locale.CHINA)
