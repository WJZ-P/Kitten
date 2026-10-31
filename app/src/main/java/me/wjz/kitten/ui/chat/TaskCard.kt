package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.agent.Reflection
import me.wjz.kitten.agent.describe
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.AutoAwesome
import me.wjz.kitten.ui.icon.KittenIcons
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
                // 卡片上只放得下两行纯文字：Markdown 的符号去掉，不然满眼 ** 和 #。点开看是排好版的。
                text = remember(line) { line.markdownPreview() },
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
                            is TaskEvent.Said -> ChatMarkdown(
                                text = event.text,
                                streaming = false,
                                modifier = Modifier.padding(horizontal = DetailTextInset),
                            )
                            is TaskEvent.Tools -> ToolCallList(calls = event.calls, collapsible = false)
                            is TaskEvent.Asked -> AskedEntry(event)
                        }
                    }
                }
                task.streamingText?.takeIf { it.isNotBlank() }?.let {
                    // 窗里不做逐字冒出来：每收到一段就整段重排一遍，窗开着的时候才有人看。
                    ChatMarkdown(
                        text = it,
                        streaming = false,
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

/**
 * 任务卡片下面那一行：办完以后 Kitten 在后台复盘（见 SkillReflector），复盘到哪了。
 *
 * - 复盘中："Kitten 在复盘这次的经过…"，字上扫流光，和卡片上正在执行的那一步一个样子。
 * - 学到了："已学习到新知识！「技能名」 ›"，点了去设置里看那个技能。按下铺一层水波，松手才跳。
 * - 没学到、复盘失败：这一行收掉。复盘是顺手的事，没学到不用特意告诉用户。
 *
 * 出现、换状态、收掉，高度都跟着长缩，下面的回复跟着挪，不会跳。
 *
 * @param reflection 复盘到哪了，没复盘过是 null。
 */
@Composable
internal fun ReflectionLine(reflection: Reflection?, onOpenSkill: (skillId: String) -> Unit) {
    val shown = reflection?.takeIf { it !is Reflection.NothingLearned }
    // 收起的动画还在放时 shown 已经是 null 了，里面的字不能跟着没：记着最后那一份。
    var last by remember { mutableStateOf(shown) }
    SideEffect { if (shown != null) last = shown }
    AnimatedVisibility(
        visible = shown != null,
        enter = expandVertically(LineSizeSpec, expandFrom = Alignment.Top) + fadeIn(RowFadeSpec),
        exit = shrinkVertically(LineSizeSpec, shrinkTowards = Alignment.Top) + fadeOut(RowFadeSpec),
    ) {
        AnimatedContent(
            targetState = shown ?: last,
            // 只在换状态时换（复盘中 → 学到了），复盘里换一步不用动。
            contentKey = { it?.javaClass },
            transitionSpec = { fadeIn(RowFadeSpec) togetherWith fadeOut(RowFadeSpec) using SizeTransform(clip = false) },
            label = "reflectionLine",
        ) { state ->
            when (state) {
                is Reflection.Running -> ReflectingRow()
                is Reflection.Learned -> LearnedRow(state, onOpenSkill)
                Reflection.NothingLearned, null -> Unit
            }
        }
    }
}

@Composable
private fun ReflectingRow() {
    val colors = KittenTheme.colors
    val shimmer = rememberShimmerProgress(active = true)
    Row(
        modifier = Modifier.padding(start = ReflectionInset, end = ReflectionInset, top = ReflectionGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReflectionIconGap),
    ) {
        Icon(
            imageVector = KittenIcons.AutoAwesome,
            contentDescription = null,
            tint = colors.accent,
            modifier = Modifier.size(ReflectionIconSize),
        )
        Text(
            text = "Kitten 在复盘这次的经过…",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = if (shimmer != null) {
                Modifier.shimmer(progress = { shimmer.value }, color = colors.accent)
            } else {
                Modifier
            },
        )
    }
}

@Composable
private fun LearnedRow(learned: Reflection.Learned, onOpenSkill: (skillId: String) -> Unit) {
    val colors = KittenTheme.colors
    Row(
        modifier = Modifier
            // 按下的那层底色比字宽一圈：往外让出这一圈，字还和"复盘中"那一行对齐。
            .padding(start = ReflectionInset - LearnedPressPadding, top = ReflectionGap - LearnedPressPaddingVertical)
            .clip(RoundedCornerShape(GlassRadius.Small))
            .clickable(
                interactionSource = null,
                indication = ripple(color = colors.accentStrong),
                role = Role.Button,
                onClickLabel = "查看学到的技能",
            ) { onOpenSkill(learned.skillId) }
            .padding(horizontal = LearnedPressPadding, vertical = LearnedPressPaddingVertical),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ReflectionIconGap),
    ) {
        Icon(
            imageVector = KittenIcons.AutoAwesome,
            contentDescription = null,
            tint = colors.accentStrong,
            modifier = Modifier.size(ReflectionIconSize),
        )
        Text(
            text = "已学习到新知识！",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.accentStrong,
            maxLines = 1,
        )
        Text(
            text = "「${learned.skillName}」",
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(LearnedArrowSize),
        )
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

/** 复盘那一行往里缩多少：和卡片里的字左边对齐，看着是挂在这张卡片底下的。 */
private val ReflectionInset = ChatCardPadding

/** 复盘那一行和卡片之间空多少。贴太近像卡片里的字，太远又像另起一段。 */
private val ReflectionGap = 6.dp

/** 复盘那一行打头的小星星多大、和字隔多远：比字（bodySmall，12sp）略大一点。 */
private val ReflectionIconSize = 14.dp
private val ReflectionIconGap = 6.dp

/** "已学习到新知识！"那一行按下时的底色比字宽出多少，好按。 */
private val LearnedPressPadding = 8.dp
private val LearnedPressPaddingVertical = 4.dp

/** "已学习到新知识！"那一行末尾的"›"。比卡片标题行上的小一号，这一行字也小。 */
private val LearnedArrowSize = 16.dp

/** 复盘那一行长出来、收回去的弹簧，和工具清单展开收起一个性格，几乎不回弹。 */
private val LineSizeSpec = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = IntSize.VisibilityThreshold,
)
