package me.wjz.kitten.ui.task

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import me.wjz.kitten.agent.AgentTask
import me.wjz.kitten.agent.AgentTaskHandle
import me.wjz.kitten.agent.Reflection
import me.wjz.kitten.agent.SkillReflector
import me.wjz.kitten.system.reminder.Reminder
import me.wjz.kitten.system.reminder.ReminderStore
import me.wjz.kitten.system.reminder.Reminders
import me.wjz.kitten.ui.chat.ChatCardPadding
import me.wjz.kitten.ui.chat.RowFadeSpec
import me.wjz.kitten.ui.chat.RowSlideSpec
import me.wjz.kitten.ui.chat.chatCardGlass
import me.wjz.kitten.ui.chat.markdownPreview
import me.wjz.kitten.ui.chat.rememberShimmerProgress
import me.wjz.kitten.ui.chat.shimmer
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDivider
import me.wjz.kitten.ui.component.LocalFrostedBackdrop
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.Alarm
import me.wjz.kitten.ui.icon.Delete
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.settings.RowAction
import me.wjz.kitten.ui.settings.SettingsGroupLabel
import me.wjz.kitten.ui.settings.SettingsPageHeader
import me.wjz.kitten.ui.theme.KittenTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 任务页：Kitten 正在办、和以后定点要办的事。
 *
 * ## 正在进行（上面）
 *
 * - 对话里正在办的那件事（[AgentTaskHandle]）：标题、走了几步、最近说的一句、这会儿在干什么，和对话里
 *   那张任务卡片同步。只是聊天、查个记忆的那一轮不算（见 [AgentTask.started]）。点了回到对话里它所在
 *   的那个会话。
 * - 办完之后的复盘（[SkillReflector]）：复盘哪件事、这会儿在干什么。复盘完就从这里消失，学没学到
 *   看对话里任务卡片下面那一行。
 *
 * 正在办的卡片带"loading 态"：标题前一颗呼吸的圆点，最下面那一行扫流光，和对话里任务卡片上正在执行
 * 的那一行一个样子。
 *
 * ## 定时任务（下面）
 *
 * 现在列的是 Kitten 排着的提醒（[ReminderStore]，"系统能力"里的 reminder 排的）：什么事、几点响、还有多久，
 * 每条能取消。以后让 Kitten 每天定点去做的事（签到、打卡……）也放这里。一条都没有时放一张空卡片。
 *
 * ## 玻璃
 *
 * 卡片用对话里任务卡片那层玻璃（[chatCardGlass]）：只模糊不折射，页面滚动时不用每帧重算折射；而且
 * 两边是同一件事，长得一样用户一眼认得出。
 *
 * @param onOpenTask 点了正在办的那张卡片：回到对话里它所在的会话（会话 id，还没建好是 null）。
 * @param contentPadding 滚动内容四周额外留的空白。底部栏浮在页面上面，靠它让最后一张卡片能滚到栏上方。
 * @param isCurrentTab 任务是不是当前的 tab。三页常驻（见 KittenApp），切走了这页还组合着，圆点的呼吸、
 *   字上的流光就停下：看不见的东西不动，不白占每一帧。
 */
@Composable
fun TaskScreen(
    backdrop: Backdrop,
    onOpenTask: (sessionId: String?) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    isCurrentTab: Boolean = true,
) {
    val context = LocalContext.current
    val task by AgentTaskHandle.task.collectAsState()
    val reflections by SkillReflector.reflections.collectAsState()
    val reminders by remember(context) { ReminderStore.observe(context) }.collectAsState()
    val running = task?.takeIf { it.started }
    val reflecting = remember(reflections) {
        reflections.mapNotNull { (anchor, reflection) -> (reflection as? Reflection.Running)?.let { anchor to it } }
    }

    CompositionLocalProvider(LocalAnimating provides isCurrentTab) {
        TaskList(
            running = running,
            reflecting = reflecting,
            reminders = reminders.orEmpty(),
            onCancelReminder = { Reminders.cancel(context, it.id) },
            backdrop = backdrop,
            onOpenTask = onOpenTask,
            modifier = modifier,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun TaskList(
    running: AgentTask?,
    reflecting: List<Pair<String, Reflection.Running>>,
    reminders: List<Reminder>,
    onCancelReminder: (Reminder) -> Unit,
    backdrop: Backdrop,
    onOpenTask: (sessionId: String?) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = PageGutter)
            .padding(top = PageTopPadding, bottom = PageTopPadding),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        SettingsPageHeader(title = "任务", backdrop = backdrop)

        Column {
            SettingsGroupLabel("正在进行")
            Column(
                // 一张卡片办完收掉、复盘卡片冒出来，下面的"定时任务"跟着挪，不跳。
                modifier = Modifier.animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                if (running == null && reflecting.isEmpty()) {
                    EmptyCard(text = "现在没有在办的事～去对话里吩咐 Kitten 吧", backdrop = backdrop)
                }
                if (running != null) {
                    key("running") {
                        RunningTaskCard(task = running, backdrop = backdrop, onOpen = { onOpenTask(running.sessionId) })
                    }
                }
                reflecting.forEach { (anchor, reflection) ->
                    key(anchor) { ReflectionCard(reflection = reflection, backdrop = backdrop) }
                }
            }
        }

        Column {
            SettingsGroupLabel("定时任务")
            if (reminders.isEmpty()) {
                EmptyCard(text = "还没有定时任务～跟 Kitten 说一句「明早八点提醒我开会」试试", backdrop = backdrop)
            } else {
                ReminderCard(reminders = reminders, onCancel = onCancelReminder, backdrop = backdrop)
            }
        }
    }
}

/**
 * 排着的提醒：一张卡片里一条一行，闹钟图标、要提醒的事、几点响和还有多久，右边一个取消。响过的、取消的
 * 从 [ReminderStore] 里拿掉，这里跟着少一行，下面的行缓缓挪上来。
 *
 * "还有多久"每分钟走一次，对齐到整分；页面切走了就不走（见 [LocalAnimating]），切回来立刻按现在重算。
 */
@Composable
private fun ReminderCard(reminders: List<Reminder>, onCancel: (Reminder) -> Unit, backdrop: Backdrop) {
    val colors = KittenTheme.colors
    val animating = LocalAnimating.current
    val now by produceState(System.currentTimeMillis(), animating) {
        value = System.currentTimeMillis()
        while (animating) {
            delay(MinuteMillis - value % MinuteMillis)
            value = System.currentTimeMillis()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .chatCardGlass(backdrop, colors, frosted = LocalFrostedBackdrop.current)
            .animateContentSize()
            .padding(vertical = ReminderListPadding),
    ) {
        reminders.forEachIndexed { index, reminder ->
            key(reminder.id) {
                if (index > 0) GlassDivider(modifier = Modifier.padding(start = ReminderIconSize + ReminderIconGap))
                ReminderRow(reminder = reminder, now = now, onCancel = { onCancel(reminder) })
            }
        }
    }
}

@Composable
private fun ReminderRow(reminder: Reminder, now: Long, onCancel: () -> Unit) {
    val colors = KittenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = GlassContentPadding, end = ReminderEndPadding, top = ReminderRowPadding, bottom = ReminderRowPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReminderIconGap),
    ) {
        Icon(
            imageVector = KittenIcons.Alarm,
            contentDescription = null,
            tint = colors.iconTeal,
            modifier = Modifier.size(ReminderIconSize),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = reminder.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = ReminderTitleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = remember(reminder.triggerAtMillis, now) { reminderWhen(reminder.triggerAtMillis, now) },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
                maxLines = 1,
            )
        }
        RowAction(icon = KittenIcons.Delete, label = "取消提醒", tint = colors.danger, onClick = onCancel)
    }
}

/** "今天 15:00 · 2 小时 5 分后"：近三天说今天、明天、后天，再远写日期和星期。 */
private fun reminderWhen(at: Long, now: Long): String {
    val zone = ZoneId.systemDefault()
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = when (ChronoUnit.DAYS.between(today, time.toLocalDate())) {
        0L -> "今天"
        1L -> "明天"
        2L -> "后天"
        else -> time.format(ReminderDateFormat)
    }
    return "$day ${time.format(ReminderClockFormat)} · ${countdown(at - now)}"
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

/**
 * 正在办的那件事：圆点 + 标题 + "进行中 · N 步"，下面是最近说的一句和这会儿在干什么。整张卡点了回到
 * 对话里，按下微微鼓起，松手才跳，和对话里的任务卡片手感一样。
 */
@Composable
private fun RunningTaskCard(task: AgentTask, backdrop: Backdrop, onOpen: () -> Unit) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnOpen by rememberUpdatedState(onOpen)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "回到对话") {
                    currentOnOpen()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnOpen() } }
            .chatCardGlass(backdrop, colors, frosted = LocalFrostedBackdrop.current, scale = { lerp(1f, PressedScale, press.pressProgress) })
            .padding(ChatCardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardTitleRow(title = task.title, status = "进行中 · ${task.steps} 步", showArrow = true)
        task.lastSaid?.let { said ->
            Text(
                // 卡片上只放得下两行纯文字：Markdown 的符号去掉，和对话里的任务卡片一样。
                text = remember(said) { said.markdownPreview() },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = LastSaidMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val progress = when {
            task.question != null -> ProgressText("在等你回答…", shimmering = false)
            task.detail != null -> ProgressText(task.detail, shimmering = true)
            else -> ProgressText("正在思考…", shimmering = true)
        }
        ProgressLine(progress)
    }
}

/** 正在复盘的一件事：圆点 + "复盘「标题」" + "复盘中"，下面是这会儿在干什么。不能点：复盘没什么可看的。 */
@Composable
private fun ReflectionCard(reflection: Reflection.Running, backdrop: Backdrop) {
    val colors = KittenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .chatCardGlass(backdrop, colors, frosted = LocalFrostedBackdrop.current)
            .padding(ChatCardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardTitleRow(title = "复盘「${reflection.title}」", status = "复盘中", showArrow = false)
        ProgressLine(ProgressText(reflection.detail, shimmering = true))
    }
}

/** 卡片的标题行：呼吸的圆点、标题、状态，能点的卡片末尾一个"›"。 */
@Composable
private fun CardTitleRow(title: String, status: String, showArrow: Boolean) {
    val colors = KittenTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BreathingDot(color = colors.accentStrong)
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelMedium,
                color = colors.accentStrong,
                maxLines = 1,
            )
            if (showArrow) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(ArrowSize),
                )
            }
        }
    }
}

/** 卡片最下面那一行写什么、要不要扫流光。 */
private data class ProgressText(val text: String, val shimmering: Boolean)

/**
 * 卡片最下面那一行：这会儿在干什么。换一步时旧的往上滑走、新的从下面升上来，和对话里任务卡片上
 * 那一行一个动法；还在做的那一步字上扫流光。
 */
@Composable
private fun ProgressLine(progress: ProgressText) {
    AnimatedContent(
        targetState = progress,
        contentKey = { it.text },
        transitionSpec = {
            (slideInVertically(RowSlideSpec) { it } + fadeIn(RowFadeSpec)) togetherWith
                (slideOutVertically(RowSlideSpec) { -it } + fadeOut(RowFadeSpec)) using
                SizeTransform(clip = true)
        },
        label = "taskTabProgress",
    ) { line ->
        val colors = KittenTheme.colors
        val shimmer = rememberShimmerProgress(active = line.shimmering && LocalAnimating.current)
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = if (shimmer != null) {
                Modifier.shimmer(progress = { shimmer.value }, color = colors.accent)
            } else {
                Modifier
            },
        )
    }
}

/**
 * 标题前那颗圆点：慢慢变大变亮、再变小变暗，像在呼吸，告诉用户"还在办"。只在绘制阶段读动画值，
 * 呼吸的时候不重组。
 */
@Composable
private fun BreathingDot(color: Color) {
    // 页面切走了就不呼吸，停在最亮那一下，见 TaskScreen 的 isCurrentTab。
    val breath = if (LocalAnimating.current) {
        rememberInfiniteTransition(label = "breathingDot").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BreathMillis, easing = LinearEasing), RepeatMode.Reverse),
            label = "breathingDotProgress",
        )
    } else {
        null
    }
    Box(
        modifier = Modifier
            .size(DotSize)
            .graphicsLayer {
                val t = breath?.value ?: 1f
                val s = lerp(DotMinScale, 1f, t)
                scaleX = s
                scaleY = s
                alpha = lerp(DotMinAlpha, 1f, t)
            }
            .background(color, CircleShape),
    )
}

/** 空着的时候那张卡片：一句淡淡的话。 */
@Composable
private fun EmptyCard(text: String, backdrop: Backdrop) {
    val colors = KittenTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .chatCardGlass(backdrop, colors, frosted = LocalFrostedBackdrop.current)
            .padding(ChatCardPadding),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textTertiary,
        )
    }
}

/** 这一页的动画该不该动：任务 tab 不是当前页时为 false，见 [TaskScreen] 的 isCurrentTab。 */
private val LocalAnimating = compositionLocalOf { true }

/** 页面左右留白、顶上留白、两组之间的空，和设置首页一样：两页挨着，换过去版式不跳。 */
private val PageGutter = 16.dp
private val PageTopPadding = 12.dp
private val SectionGap = 16.dp

/** "正在进行"里卡片和卡片之间空多少。卡片带阴影，比设置里的分组挨得近一点也分得开。 */
private val CardGap = 10.dp

/** 卡片上"最近说的一句"最多几行，和对话里的任务卡片一样。 */
private const val LastSaidMaxLines = 2

/** 按下时卡片鼓到多大，和对话里的任务卡片一样。 */
private const val PressedScale = 1.02f

/** 标题行末尾那个"›"多大。 */
private val ArrowSize = 18.dp

/**
 * 呼吸的圆点：多大、最小缩到几成、最暗到几成、吸一口气多久（毫秒，呼出同样长）。
 *
 * 一口气 900ms：再快像在报警，再慢看不出在动。最小缩到六成、最暗四成：变化够明显，又不会缩没了。
 */
private val DotSize = 8.dp
private const val DotMinScale = 0.6f
private const val DotMinAlpha = 0.4f
private const val BreathMillis = 900

/**
 * 提醒那张卡片：卡片上下留白、每行上下留白、图标多大、图标和字之间空多少、右边留白、标题最多几行。
 *
 * 图标、间距和设置页的行（SettingsRow）一样大，两页版式一致。右边留白比左边小：取消按钮的触摸区本来就比
 * 图标大一圈，自带了空。标题两行：一句提醒偶尔长一点，一行截断了看不出是哪件事。
 */
private val ReminderListPadding = 4.dp
private val ReminderRowPadding = 8.dp
private val ReminderIconSize = 22.dp
private val ReminderIconGap = 14.dp
private val ReminderEndPadding = 8.dp
private const val ReminderTitleMaxLines = 2

private const val MinuteMillis = 60_000L
private val ReminderClockFormat = DateTimeFormatter.ofPattern("HH:mm")
private val ReminderDateFormat = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINA)
