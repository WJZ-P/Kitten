package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import me.wjz.kitten.agent.describe
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/** 任务走到哪了。 */
internal enum class TaskStatus {
    /** 还在办。 */
    Running,

    /** 办完了：模型说了讲结果的那段话，或者接着开了下一个任务。 */
    Done,

    /** 没办完就停了：用户喊停、模型调用失败、App 被关掉。 */
    Stopped,
}

/** 任务里的一件事，按发生的先后排。 */
@Immutable
internal sealed interface TaskEvent {
    /** 模型边做边说的一段话。 */
    class Said(val text: String) : TaskEvent

    /** 连着调的一串工具。 */
    class Tools(val calls: List<ToolCallItem>) : TaskEvent

    /** 问过用户的一个问题。[answer] 是 null 表示没等到回答就停了。 */
    class Asked(val question: String, val answer: String?) : TaskEvent
}

/**
 * 一个任务：从模型调 start_task 开始，到这一轮结束为止的全部过程（见 ChatScreen 的 buildChatItems）。
 *
 * @param streamingText 正在冒的那段话。还不知道它是边做边说、还是最后讲结果的那段，先算在卡片里；
 *   说完要是不带工具调用，它会作为结果出现在卡片外面。
 */
@Immutable
internal class TaskInfo(
    val title: String,
    val events: List<TaskEvent>,
    val status: TaskStatus,
    val streamingText: String?,
) {
    /** 走了几步：每次工具调用、每个问过的问题各算一步。 */
    val steps: Int = events.sumOf {
        when (it) {
            is TaskEvent.Tools -> it.calls.size
            is TaskEvent.Asked -> 1
            is TaskEvent.Said -> 0
        }
    }

    /** 最近一步工具调用。 */
    val latestTool: ToolCallItem? = events.lastOrNull { it is TaskEvent.Tools }
        ?.let { (it as TaskEvent.Tools).calls.lastOrNull() }

    /** 最近说的一句：正在冒的那段，或者最后一段话、最后问的问题。 */
    val latestLine: String? = streamingText?.takeIf { it.isNotBlank() }
        ?: events.lastOrNull { it is TaskEvent.Said || it is TaskEvent.Asked }?.let {
            when (it) {
                is TaskEvent.Said -> it.text
                is TaskEvent.Asked -> "问了你：${it.question}"
                is TaskEvent.Tools -> null
            }
        }
}

/**
 * 对话里的任务卡片：标题、状态和步数，下面是最近说的一句话和最近一步在干什么。整张卡点了打开
 * 完整过程（[TaskDetailDialog]）。
 *
 * 一个任务动辄二十多步，用户多半只关心结果：过程收在卡片里，结果（讲结果的那段话）在卡片外面。
 * 卡片上只留"现在走到哪了"，想看来龙去脉再点开。
 *
 * 最下面那一行随着任务往前走在换：新的一步从下面升上来、旧的往上滑走，和收起时的工具清单一个
 * 动法（[RowSlideSpec]）。还在办、但这会儿没在执行工具时，那一行是"正在思考…"或者"在等你回答…"。
 *
 * 按下整张卡微微鼓起，松手才打开，和气泡长按的手感一样。
 *
 * @param waitingForAnswer 这会儿在等用户回答（提问卡片压在对话最下面）。
 */
@Composable
internal fun TaskCard(
    task: TaskInfo,
    waitingForAnswer: Boolean,
    backdrop: Backdrop,
    onOpen: () -> Unit,
) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnOpen by rememberUpdatedState(onOpen)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "查看任务过程") {
                    currentOnOpen()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnOpen() } }
            .chatCardGlass(backdrop, colors, scale = { lerp(1f, PressedScale, press.pressProgress) })
            .padding(ChatCardPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = task.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${task.status.label()} · ${task.steps} 步",
                style = MaterialTheme.typography.labelMedium,
                color = task.status.color(),
                maxLines = 1,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(ArrowSize),
            )
        }
        task.latestLine?.let { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = LatestLineMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val progress = task.progressLine(waitingForAnswer)
        if (progress != null) {
            AnimatedContent(
                targetState = progress,
                contentKey = { it.key },
                transitionSpec = {
                    (slideInVertically(RowSlideSpec) { it } + fadeIn(RowFadeSpec)) togetherWith
                        (slideOutVertically(RowSlideSpec) { -it } + fadeOut(RowFadeSpec)) using
                        SizeTransform(clip = true)
                },
                label = "taskProgress",
            ) { line -> ProgressLineText(line) }
        }
    }
}

/**
 * 卡片最下面那一行写什么。
 *
 * [key] 决定换行时滑不滑：同一步只是状态变了（执行中 → 完成）原地更新；换了一步、或者从执行工具
 * 换成"正在思考…"，才滑一下。
 */
private sealed interface ProgressLine {
    val key: Any

    class Tool(val item: ToolCallItem) : ProgressLine {
        override val key: Any get() = item.call.id
    }

    data object Thinking : ProgressLine {
        override val key: Any get() = "thinking"
    }

    data object Waiting : ProgressLine {
        override val key: Any get() = "waiting"
    }
}

private fun TaskInfo.progressLine(waitingForAnswer: Boolean): ProgressLine? {
    val tool = latestTool
    if (status != TaskStatus.Running) return tool?.let(ProgressLine::Tool)
    return when {
        waitingForAnswer -> ProgressLine.Waiting
        tool?.status == ToolCallStatus.Running -> ProgressLine.Tool(tool)
        // 正在冒字时，上面那一行就是它在说的话，不用再写"正在思考"。
        streamingText != null -> tool?.let(ProgressLine::Tool)
        else -> ProgressLine.Thinking
    }
}

@Composable
private fun ProgressLineText(line: ProgressLine) {
    val colors = KittenTheme.colors
    val (text, color, shimmering) = when (line) {
        is ProgressLine.Tool -> Triple(
            line.item.call.describe(),
            line.item.status.textColor(),
            line.item.status == ToolCallStatus.Running,
        )
        ProgressLine.Thinking -> Triple("正在思考…", colors.textSecondary, true)
        ProgressLine.Waiting -> Triple("在等你回答…", colors.textSecondary, false)
    }
    val shimmer = rememberShimmerProgress(active = shimmering)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (shimmer != null) {
            Modifier.shimmer(progress = { shimmer.value }, color = colors.accent)
        } else {
            Modifier
        },
    )
}

/**
 * 任务的完整过程：按时间往下排，边做边说的话是正文段落，下面跟着那一步调的工具（全摊开，每行照样
 * 能点开看结果），问过的问题也按时间夹在中间。
 *
 * 说的话不做成气泡：一来省掉一堆玻璃，二来这里是看过程的，读起来更像一份记录。
 *
 * 任务还在办时，窗开着也会跟着往下长。
 *
 * @param task 要看的任务。窗收起的动画还在放时，调用方要继续给最后那一份，不能变成 null。
 */
@Composable
internal fun TaskDetailDialog(
    visible: Boolean,
    task: TaskInfo?,
    waitingForAnswer: Boolean,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = task?.title,
        icon = Icons.AutoMirrored.Filled.List,
    ) {
        if (task != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GlassContentPadding)
                    .padding(top = 2.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "${task.status.label()} · 一共 ${task.steps} 步",
                    style = MaterialTheme.typography.bodySmall,
                    color = task.status.color(),
                    modifier = Modifier.padding(horizontal = DetailTextInset),
                )
                task.events.forEachIndexed { index, event ->
                    key(index) {
                        when (event) {
                            is TaskEvent.Said -> Text(
                                text = event.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.padding(horizontal = DetailTextInset),
                            )
                            is TaskEvent.Tools -> ToolCallList(calls = event.calls, collapsible = false)
                            is TaskEvent.Asked -> AskedEntry(event)
                        }
                    }
                }
                task.streamingText?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(horizontal = DetailTextInset),
                    )
                }
                if (task.status == TaskStatus.Running) {
                    val progress = task.progressLine(waitingForAnswer)
                    // 正在执行的那一步已经在上面的清单里闪着了，这里只补"思考""等回答"两种。
                    if (progress != null && (progress == ProgressLine.Thinking || progress == ProgressLine.Waiting)) {
                        Row(modifier = Modifier.padding(horizontal = DetailTextInset)) { ProgressLineText(progress) }
                    }
                }
            }
        }
    }
}

/** 模态窗里问过的一个问题：问了什么，答了什么。 */
@Composable
private fun AskedEntry(event: TaskEvent.Asked) {
    val colors = KittenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = DetailTextInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "问了你：${event.question}",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
        )
        Text(
            text = event.answer?.let { "你的回答：$it" } ?: "没来得及回答",
            style = MaterialTheme.typography.bodySmall,
            color = if (event.answer != null) colors.accentStrong else colors.textTertiary,
        )
    }
}

private fun TaskStatus.label(): String = when (this) {
    TaskStatus.Running -> "进行中"
    TaskStatus.Done -> "完成"
    TaskStatus.Stopped -> "已中断"
}

@Composable
private fun TaskStatus.color(): Color {
    val colors = KittenTheme.colors
    return when (this) {
        TaskStatus.Running -> colors.accentStrong
        TaskStatus.Done -> colors.positive
        TaskStatus.Stopped -> colors.textTertiary
    }
}

/** 标题行末尾那个"›"多大，提示整张卡能点。 */
private val ArrowSize = 18.dp

/** 卡片上"最近说的一句"最多几行。再多卡片就长成气泡了，想看全文点开。 */
private const val LatestLineMaxLines = 2

/** 按下时卡片鼓到多大，和气泡一样给一点点。 */
private const val PressedScale = 1.02f

/** 模态窗里的字往里缩多少，和工具清单那一行的字左边对齐（清单行的左右留白是 8dp）。 */
private val DetailTextInset = 8.dp
