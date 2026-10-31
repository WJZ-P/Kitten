package me.wjz.kitten.ui.chat

import android.content.ClipData
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.agent.AgentQuestion
import me.wjz.kitten.agent.tools.AskUserTool
import me.wjz.kitten.agent.tools.FORGET_MEMORY
import me.wjz.kitten.agent.tools.RECALL_MEMORY
import me.wjz.kitten.agent.tools.SAVE_MEMORY
import me.wjz.kitten.agent.tools.StartTaskTool
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContextMenu
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassMenuItem
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSurface
import me.wjz.kitten.ui.component.ImageViewer
import me.wjz.kitten.ui.component.rememberGlassContextMenuState
import me.wjz.kitten.ui.icon.ContactSupport
import me.wjz.kitten.ui.icon.ContentCopy
import me.wjz.kitten.ui.icon.Delete
import me.wjz.kitten.ui.icon.KeyboardArrowDown
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 对话页：顶栏 + 消息列表 + 输入栏。
 *
 * 无状态——所有数据由 [ChatUiState] 给，所有动作往上抛。这样 Preview 和以后做测试
 * 都不需要真的起一个 ViewModel。
 *
 * ## 列表铺到底，输入栏浮在上面
 *
 * 消息列表一直铺到屏幕底部，输入栏和底部栏都浮在列表上面，而不是在列表下面各占一条：
 * 气泡滚到它们后面时，玻璃折射得到气泡和字，液态玻璃的效果才出得来。列表把自己录一份
 * （listBackdrop）给输入栏折射；底部栏折射的是整个页面，本来就看得到列表。
 *
 * 列表末尾留出"输入栏 + 它下面让出来的那一截"的高度，最后一条消息才滚得上来。
 *
 * ## 回到最新消息
 *
 * 用户往上翻着看以前的消息时，新的字照样在底下冒，但不会把人拽下去；输入栏上方浮出一个
 * 玻璃小圆（[ScrollToLatestButton]），点了回到最底下。
 *
 * @param bottomInsets 输入栏下面要让出来的位置：平时是底部栏，键盘弹起时是键盘。
 * @param onDeleteMessage 删一条消息：它在 messages 里的位置，和那条消息本身（用来核对没删错）。
 * @param onSelectModel 在输入栏的模型菜单里选了哪一家（[ModelGroup.profileId]）的哪个模型。
 * @param onStop 回复期间点了输入栏上的停止。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    backdrop: Backdrop,
    onSend: (text: String, images: List<Uri>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    onDeleteMessage: (index: Int, message: ChatMessage) -> Unit,
    onAnswerQuestion: (String) -> Unit,
    onSelectModel: (profileId: String, model: String) -> Unit,
    onStop: () -> Unit,
    bottomInsets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listBackdrop = rememberLayerBackdrop()
    // 浮在列表上面的东西（输入栏、回到最新的按钮）折射的是"背景 + 消息列表"。
    val overBackdrop = rememberCombinedBackdrop(backdrop, listBackdrop)
    // 输入栏连同它下面让出来的那一截的高度，列表末尾留出这么多。
    var overlayHeight by remember { mutableStateOf(0.dp) }
    // 正在看的大图。输入栏的缩略图、气泡里的图片点了都在这里打开，见 ImageViewer。
    var viewingImage by remember { mutableStateOf<Uri?>(null) }
    // 换一个会话就是一张新列表：滚动位置从头来（倒着排，从头来就是在最底下）。
    val listState = key(state.sessionId) { rememberLazyListState() }
    val awayThreshold = with(density) { AwayFromLatestThreshold.roundToPx() }
    val awayFromLatest by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > awayThreshold
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatTopBar(
                title = state.title.ifBlank { "新对话" },
                onOpenDrawer = onOpenDrawer,
                onNewSession = onNewSession,
                backdrop = backdrop,
            )
            // 录一份给输入栏折射。输入栏是它的兄弟、不在录制范围里，不会成环。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .layerBackdrop(listBackdrop),
            ) {
                if (state.isEmpty) {
                    EmptyHint(
                        isModelConfigured = state.isModelConfigured,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.padding(bottom = overlayHeight),
                    )
                } else {
                    // 列表里记着的东西（长按菜单对着哪一条……）也跟着会话换新的。
                    key(state.sessionId) {
                        MessageList(
                            listState = listState,
                            messages = state.messages,
                            streamingReply = state.streamingReply,
                            isResponding = state.isResponding,
                            agentProgress = state.agentProgress,
                            pendingQuestion = state.pendingQuestion,
                            onAnswerQuestion = onAnswerQuestion,
                            backdrop = backdrop,
                            bottomPadding = overlayHeight,
                            onDeleteMessage = onDeleteMessage,
                            onOpenImage = { viewingImage = it },
                        )
                    }
                }
            }
        }
        ChatInputBar(
            backdrop = overBackdrop,
            enabled = state.canSend,
            hint = if (state.isModelConfigured) null else "请先在设置里配置模型",
            onSend = onSend,
            onOpenImage = { viewingImage = it },
            responding = state.isResponding,
            onStop = onStop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 量在让位之前：量到的是输入栏加上它下面让出来的那一截。
                .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } }
                .windowInsetsPadding(bottomInsets),
            modelSwitcher = { buttonBackdrop ->
                ModelSwitcher(
                    groups = state.modelGroups,
                    activeProfileId = state.activeProfileId,
                    activeModel = state.activeModel,
                    onSelect = onSelectModel,
                    enabled = !state.isResponding,
                    backdrop = buttonBackdrop,
                    // 菜单浮在整个界面上面，背后是背景（界面那部分浮层自己会录），不是输入栏背后的列表。
                    menuBackdrop = backdrop,
                )
            },
        )
        ScrollToLatestButton(
            visible = !state.isEmpty && awayFromLatest,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            backdrop = overBackdrop,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = overlayHeight + ScrollToLatestGap),
        )
    }

    ImageViewer(
        image = viewingImage,
        onDismissRequest = { viewingImage = null },
        backdrop = backdrop,
    )
}

/**
 * 回到最新消息的按钮：输入栏正上方一个玻璃小圆，里面一个向下的尖角。弹出来、收回去都是
 * 弹簧，从小长大、缩回去。
 */
@Composable
private fun ScrollToLatestButton(
    visible: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(spring(stiffness = 600f)) + scaleIn(spring(dampingRatio = 0.6f, stiffness = 500f), initialScale = 0.5f),
        exit = fadeOut(spring(stiffness = 900f)) + scaleOut(spring(stiffness = 900f), targetScale = 0.5f),
    ) {
        GlassIconButton(
            icon = KittenIcons.KeyboardArrowDown,
            contentDescription = "回到最新消息",
            onClick = onClick,
            backdrop = backdrop,
            size = ScrollToLatestSize,
        )
    }
}

/**
 * 消息列表。
 *
 * ## 倒着排
 *
 * 列表是倒过来排的（reverseLayout）：第 0 项是最新的那条，钉在最底下。这是聊天列表的标准
 * 做法，好处是"贴底"不用自己去滚：
 *
 * - 在最底下时，回复越写越长，上面的内容在同一帧被推上去，最新的字一直看得见；
 * - 用户往上翻着时，列表钉住的是他正看着的那一条，底下怎么长都不影响他；
 * - 刚打开会话，本来就在最底下。
 *
 * 唯一要自己管的是"底下多出一项"：每一项都有稳定的 key，列表会钉住原来最底下那一项，新进来
 * 的那项落在屏幕下面看不见。所以本来就在最底下（或者是用户自己刚发的消息）时，让列表这一次
 * 把第 0 项摆在最底下（requestScrollToItem），同一帧生效，不会先错一下再跳。
 *
 * 消息不满一屏时照样贴顶（Arrangement 带 Alignment.Top），和正着排的时候一个样。
 *
 * ## key
 *
 * 每一项的 key 是它在对话里的序号（从 0 数起），发消息、收回复都是往后加，前面的不会变。正在
 * 生成的那条排在 messages.size 这个序号上，生成完的正式消息也是这个序号：对列表来说是同一项，
 * 气泡和里面没冒完的字都接着来，不会闪一下。还没冒出第一个字时显示"正在思考…"。工具清单的 key
 * 见 [ChatItem.Tools]。
 *
 * ## 长按菜单
 *
 * 长按一条气泡弹出菜单（[GlassContextMenu]）：复制、删除。整个列表共用一个菜单，记着长按的
 * 是哪一条；菜单在列表外面，删掉那一条之后收起的动画照样放得完。还在生成的那条不响应长按。
 */
@Composable
private fun MessageList(
    listState: LazyListState,
    messages: List<ChatMessage>,
    streamingReply: String?,
    isResponding: Boolean,
    agentProgress: String?,
    pendingQuestion: AgentQuestion?,
    onAnswerQuestion: (String) -> Unit,
    backdrop: Backdrop,
    bottomPadding: Dp,
    onDeleteMessage: (index: Int, message: ChatMessage) -> Unit,
    onOpenImage: (Uri) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val menu = rememberGlassContextMenuState()
    // 长按的是哪一条。菜单收起后也留着：收起的动画还在放，菜单里的东西不能跟着变。
    var menuTarget by remember { mutableStateOf<MenuTarget?>(null) }
    // 要画的东西：气泡和工具清单，见 buildChatItems。
    val shown = remember(messages, streamingReply, isResponding) {
        buildChatItems(messages, streamingReply, isResponding)
    }
    // 正在看哪个任务的完整过程（任务卡片的 key），没在看是 null。见 TaskDetailDialog。
    var openTaskKey by remember { mutableStateOf<Any?>(null) }
    val openTask = openTaskKey?.let { key -> shown.firstOrNull { it.key == key } as? ChatItem.Task }
    // 窗收起的动画还在放时，里面的内容不能一下子没了：记着最后看的那一份。
    var lastOpenedTask by remember { mutableStateOf<TaskInfo?>(null) }
    SideEffect { if (openTask != null) lastOpenedTask = openTask.task }
    // 任务卡片正在办：卡片上已经写着"进行中"和"正在思考…"了，外面不再多一行。
    val taskRunning = (shown.lastOrNull() as? ChatItem.Task)?.task?.status == TaskStatus.Running
    // 等用户回答时不显示"正在思考"：它没在想，就是在等，卡片本身已经说清楚了。正在执行工具时
    // 也不显示：清单里那一行的圆点正闪着，已经说了在干什么。
    val showTyping = isResponding && streamingReply == null && pendingQuestion == null && agentProgress == null &&
        !taskRunning
    // 括号不能省：Kotlin 里 `a + if (x) 1 else 0 + if (y) 1 else 0` 会把后半截算进 else 分支。
    val itemCount = shown.size + (if (showTyping) 1 else 0) + (if (pendingQuestion != null) 1 else 0)

    // 底下多出一项时要不要把它摆到最底下，见类注释。读的是这次数据变化之前的位置。
    val atLatest by remember(listState) { derivedStateOf { !listState.canScrollBackward } }
    val seen = remember { SeenCounts(items = itemCount, messages = messages.size) }
    SideEffect {
        val justSent = messages.size > seen.messages && messages.lastOrNull() is ChatMessage.User
        if (justSent || (itemCount > seen.items && atLatest)) listState.requestScrollToItem(0)
        seen.items = itemCount
        seen.messages = messages.size
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
    ) {
        // 倒着排，先写的在最底下。提问卡片要压在最下面，紧挨着输入框。
        if (pendingQuestion != null) {
            item(key = QuestionKey) {
                QuestionCard(question = pendingQuestion, onAnswer = onAnswerQuestion, backdrop = backdrop)
            }
        }
        if (showTyping) {
            item(key = TypingKey) { TypingIndicator() }
        }
        items(count = shown.size, key = { shown[shown.lastIndex - it].key }) { reversedIndex ->
            // 倒着排，所以第 0 项是最后一项。
            when (val item = shown[shown.lastIndex - reversedIndex]) {
                is ChatItem.Tools -> ToolCallList(calls = item.calls)
                is ChatItem.Task -> TaskCard(
                    task = item.task,
                    waitingForAnswer = pendingQuestion != null,
                    backdrop = backdrop,
                    onOpen = { openTaskKey = item.key },
                )
                is ChatItem.Answered -> AnsweredQuestionCard(
                    question = item.question,
                    answer = item.answer,
                    backdrop = backdrop,
                )
                is ChatItem.Bubble -> {
                    // index 是它在 messages 里的真实位置，长按删除要用。
                    val index = item.index
                    val isDraft = index >= messages.size
                    MessageBubble(
                        message = item.message,
                        backdrop = backdrop,
                        lifted = !isDraft && menu.expanded && menuTarget?.index == index,
                        onLongPress = if (isDraft) {
                            null
                        } else {
                            {
                                menuTarget = MenuTarget(index, item.message)
                                menu.show(it)
                            }
                        },
                        onOpenImage = onOpenImage,
                        streaming = isDraft,
                    )
                }
            }
        }
    }

    val target = menuTarget
    GlassContextMenu(
        state = menu,
        items = buildList {
            target?.message?.copyableText()?.let { text ->
                add(
                    GlassMenuItem(text = "复制", icon = KittenIcons.ContentCopy) {
                        // Android 13 起系统会自己弹"已复制"的提示，这里不再另弹一条。
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("消息", text))) }
                    },
                )
            }
            if (target != null) {
                add(
                    GlassMenuItem(text = "删除", icon = KittenIcons.Delete, destructive = true) {
                        onDeleteMessage(target.index, target.message)
                    },
                )
            }
        },
        backdrop = backdrop,
    )

    // 任务还在办时，窗里跟着往下长：给的是这一次整理出来的那一份，不是点开时的快照。
    TaskDetailDialog(
        visible = openTask != null,
        task = openTask?.task ?: lastOpenedTask,
        waitingForAnswer = pendingQuestion != null,
        onDismiss = { openTaskKey = null },
        backdrop = backdrop,
    )
}

/** 长按菜单对着的那一条：它在对话里的序号，和长按那一刻的消息本身。 */
private class MenuTarget(val index: Int, val message: ChatMessage)

/** 上一次组合时列表有几项、几条消息，用来看这一次是不是多出了一项、是不是刚发了一条。 */
private class SeenCounts(var items: Int, var messages: Int)

/** 列表里的一项：一个气泡、连在一起的一串工具调用、一张问过的问答卡片，或者一张任务卡片。 */
private sealed interface ChatItem {
    /** LazyColumn 的 key，见 MessageList 的类注释。 */
    val key: Any

    /** 一个气泡。[index] 是这条消息在 messages 里的位置，正在生成的那条是 messages.size。 */
    class Bubble(val index: Int, val message: ChatMessage) : ChatItem {
        override val key: Any get() = index
    }

    /**
     * 连在一起的一串工具调用（[ToolCallList]）。key 跟着这一串里第一个调用走（第几条消息里的第几个）：
     * 后面的步骤接上来，清单变长，还是同一项。字符串的 key，不会和气泡的序号撞上。
     *
     * 要带上"第几个"：问答卡片会把同一条消息里的调用切成前后两串，只用消息序号的话两串撞 key。
     */
    class Tools(firstIndex: Int, firstCall: Int, val calls: List<ToolCallItem>) : ChatItem {
        override val key: Any = "tools-$firstIndex-$firstCall"
    }

    /**
     * 问过的一个问题（[AnsweredQuestionCard]）。[answer] 是 null 表示没等到回答任务就停了。
     * key 是第几条消息里的第几个调用，和工具清单一个规矩、前缀不同。
     */
    class Answered(index: Int, call: Int, val question: String, val answer: String?) : ChatItem {
        override val key: Any = "ask-$index-$call"
    }

    /**
     * 一张任务卡片（[TaskCard]）。key 是开任务的那次 start_task 在第几条消息里的第几个调用：任务
     * 往前走、卡片内容在变，还是同一项。
     */
    class Task(index: Int, call: Int, val task: TaskInfo) : ChatItem {
        override val key: Any = "task-$index-$call"
    }
}

/**
 * 整理消息时正在收进卡片的那个任务，见 [buildChatItems]。
 *
 * @param index 开任务的那次调用在第几条消息里，[call] 是那条消息里的第几个调用，一起组成卡片的 key。
 * @param auto 是界面自动开的（模型没调 start_task），标题是用户那句话的开头。之后模型补调了
 *   start_task 的话，直接换成它起的标题，不另开一张。
 */
private class TaskBuilder(val index: Int, val call: Int, var title: String, var auto: Boolean) {
    private val events = mutableListOf<TaskEvent>()

    fun said(text: String) {
        events += TaskEvent.Said(text)
    }

    fun asked(question: String, answer: String?) {
        events += TaskEvent.Asked(question, answer)
    }

    /** 接在最后那串工具后面；最后一件事不是工具（说了话、问了问题），另起一串。 */
    fun tool(item: ToolCallItem) {
        val last = events.lastOrNull()
        if (last is TaskEvent.Tools) {
            events[events.lastIndex] = TaskEvent.Tools(last.calls + item)
        } else {
            events += TaskEvent.Tools(listOf(item))
        }
    }

    fun build(status: TaskStatus, streamingText: String?): ChatItem.Task =
        ChatItem.Task(index, call, TaskInfo(title, events.toList(), status, streamingText))
}

/**
 * 把消息整理成列表里的一项项：说的话是气泡，调的工具排成清单。
 *
 * - 模型的回复：有字就是一项（只放字，不套气泡，见 MessageBubble），调的工具接在下面的清单里。只调
 *   工具不说话的那几步不占一项，工具接着往上一张清单里排——一个任务十几步，读起来是"一段话 + 一张
 *   清单"，而不是十几个只写着工具名的空段落。中间一说话、或者用户插了一句，清单就断开，后面的另起一张。
 * - 工具结果不画：它是给模型看的原始数据（一棵界面树动辄几千字符），塞进对话里只会把真正的内容
 *   淹掉。但清单里每一行的状态要看它：有没有结果、是不是报错了，点开那一行看的也是它。
 * - 问用户的问题（ask_user）不进清单，单独一张问答卡片：问了什么、用户答了什么，不用点开清单
 *   去翻。还在等回答的不画，底下压着的提问卡片就是它；参数坏了、报了错的照常是清单里的一行。
 * - 既没说话也没调工具的空壳不画——异常响应会产生这种。
 * - 正在生成的那条排在最后，序号是 messages.size：生成完的正式消息也落在这个序号上，对列表来说
 *   是同一项，交接时不闪。
 *
 * ## 任务卡片
 *
 * 模型调了 start_task，从这一下开始到这一轮结束，说的话、调的工具、问过的问题都收进一张任务卡片
 * （[TaskBuilder]），不再一条条摊在对话里：
 *
 * - 开任务那条消息里 start_task 之前说的话（"好的，我来找找～"）还是气泡，在卡片上面。
 * - 一轮里最后那段不带工具调用的话是讲结果的：任务到这里办完，这段话放在卡片外面当气泡——用户
 *   多半只看它。
 * - 一轮里又调了一次 start_task：前一个任务算办完，另开一张。
 * - 一轮没说完就断了（用户喊停、模型调用失败），任务算中断；用户发了下一句，同样收尾。
 * - 正在冒的字先算在卡片里：还不知道它是边做边说还是讲结果的那段。说完要是不带工具调用，下一次
 *   整理时它就出现在卡片外面。
 * - 还在等回答的提问照旧不画，提问卡片压在对话最下面；答过的问题收进卡片。
 *
 * ## 模型没开任务时，界面自己开
 *
 * 实测模型经常不调 start_task（提示词里写了也一样，尤其是历史里一直没调过的老会话，它会照着
 * 以前的样子来）。所以兜一层：一轮里操作手机满 [AutoTaskMinSteps] 步（查记忆、提问不算），就从
 * 第一步操作手机的地方开始自动收进卡片，标题用用户那句话的开头。模型后来补调了 start_task，
 * 换成它起的标题，不另开一张。
 *
 * 一轮里操作手机不到这么多步（看一眼屏幕、查个记忆），一切照旧：气泡加折叠的工具清单。
 */
private fun buildChatItems(
    messages: List<ChatMessage>,
    streamingReply: String?,
    isResponding: Boolean,
): List<ChatItem> {
    val results = messages.filterIsInstance<ChatMessage.ToolResult>().associateBy { it.callId }
    // 这一轮从用户最后一句话开始。之前几轮里没等到结果的调用是那时候被停掉的，不是现在还在跑。
    val runStart = messages.indexOfLast { it is ChatMessage.User }
    // 哪几轮要自动开任务：用户那句话的序号。
    val autoTaskRuns = runsNeedingAutoTask(messages)
    val items = mutableListOf<ChatItem>()
    var group: MutableList<ToolCallItem>? = null
    var groupStart = 0
    var groupFirstCall = 0
    // 这一轮里第一个还没结果的调用正在执行；同一步里排在它后面的还没轮到。
    var runningFound = false
    // 正在收进卡片的任务，没开任务时是 null。
    var task: TaskBuilder? = null
    // 当前这一轮是从哪句用户的话开始的，和它自动开任务时用的标题。
    var currentRun = -1
    var currentRunTitle = DefaultAutoTaskTitle

    fun closeGroup() {
        group?.let { items += ChatItem.Tools(groupStart, groupFirstCall, it) }
        group = null
    }

    fun closeTask(status: TaskStatus) {
        task?.let { items += it.build(status, streamingText = null) }
        task = null
    }

    messages.forEachIndexed { index, message ->
        when (message) {
            is ChatMessage.User -> {
                closeGroup()
                // 上一轮的任务没等到讲结果的那段就到了下一句：那一轮是被打断的。
                closeTask(TaskStatus.Stopped)
                items += ChatItem.Bubble(index, message)
                currentRun = index
                currentRunTitle = autoTaskTitle(message.text)
            }
            is ChatMessage.Assistant -> {
                val openTask = task
                if (openTask != null && message.toolCalls.isEmpty()) {
                    // 任务里不带工具调用的话：这一轮的最后一段，讲结果的。任务办完，这段放在卡片外面。
                    closeTask(TaskStatus.Done)
                    if (!message.text.isNullOrBlank()) items += ChatItem.Bubble(index, message)
                    return@forEachIndexed
                }
                message.text?.takeIf { it.isNotBlank() }?.let { text ->
                    if (openTask != null) {
                        openTask.said(text)
                    } else {
                        closeGroup()
                        items += ChatItem.Bubble(index, message)
                    }
                }
                message.toolCalls.forEachIndexed calls@{ position, call ->
                    val result = results[call.id]?.content
                    val status = when {
                        result != null ->
                            if (result.startsWith("ERROR")) ToolCallStatus.Failed else ToolCallStatus.Done
                        !isResponding || index < runStart -> ToolCallStatus.Stopped
                        !runningFound -> ToolCallStatus.Running.also { runningFound = true }
                        else -> ToolCallStatus.Pending
                    }
                    if (call.name == StartTaskTool.NAME) {
                        val current = task
                        if (current != null && current.auto) {
                            // 界面已经替它开了：换成模型起的标题，不另开一张。
                            current.title = StartTaskTool.titleOf(call)
                            current.auto = false
                            return@calls
                        }
                        closeGroup()
                        // 一轮里又开了一个：前一个算办完。
                        closeTask(TaskStatus.Done)
                        task = TaskBuilder(index, position, StartTaskTool.titleOf(call), auto = false)
                        return@calls
                    }
                    if (task == null && currentRun in autoTaskRuns && call.name !in NotPhoneOperations) {
                        // 模型没开任务，但这一轮操作手机的步数够多：从这一步起自动收进卡片。
                        closeGroup()
                        task = TaskBuilder(index, position, currentRunTitle, auto = true)
                    }
                    val question = if (call.name == AskUserTool.NAME) AskUserTool.questionOf(call) else null
                    val inTask = task
                    if (inTask != null) {
                        when {
                            question == null || status == ToolCallStatus.Failed ->
                                inTask.tool(ToolCallItem(call, status, result))
                            // 还在等回答：提问卡片压在对话最下面，卡片里先不记。
                            status == ToolCallStatus.Running || status == ToolCallStatus.Pending -> Unit
                            else -> inTask.asked(question, result?.let { AskUserTool.answerOf(it) ?: it.trim() })
                        }
                        return@calls
                    }
                    when {
                        // 正常的工具调用，和问坏了（没给问题、报了错）的提问，都是清单里的一行。
                        question == null || status == ToolCallStatus.Failed -> {
                            val current = group ?: mutableListOf<ToolCallItem>().also {
                                group = it
                                groupStart = index
                                groupFirstCall = position
                            }
                            current += ToolCallItem(call, status, result)
                        }
                        // 还在等回答：底下压着的提问卡片就是它，这里不再画一遍。
                        status == ToolCallStatus.Running || status == ToolCallStatus.Pending -> Unit
                        else -> {
                            closeGroup()
                            items += ChatItem.Answered(
                                index = index,
                                call = position,
                                question = question,
                                // 拆不出来（格式对不上）就照原样显示，总比说"没回答"强。
                                answer = result?.let { AskUserTool.answerOf(it) ?: it.trim() },
                            )
                        }
                    }
                }
            }
            is ChatMessage.ToolResult -> Unit
        }
    }
    closeGroup()
    val openTask = task
    if (openTask != null) {
        // 走到头还没收尾：这一轮正在跑就是进行中，正在冒的字算在卡片里；不在跑就是没办完断了。
        val running = isResponding && openTask.index >= runStart
        items += openTask.build(
            status = if (running) TaskStatus.Running else TaskStatus.Stopped,
            streamingText = if (running) streamingReply else null,
        )
    } else if (streamingReply != null) {
        items += ChatItem.Bubble(messages.size, ChatMessage.Assistant(text = streamingReply))
    }
    return items
}

/** 不算"操作手机"的工具：查记忆、提问、开任务。自动开任务时数步数不数它们。 */
private val NotPhoneOperations = setOf(
    StartTaskTool.NAME,
    AskUserTool.NAME,
    RECALL_MEMORY,
    SAVE_MEMORY,
    FORGET_MEMORY,
)

/**
 * 哪几轮要自动开任务：一轮从用户的一句话开始到下一句为止，模型没调 start_task、而操作手机的调用
 * 满了 [AutoTaskMinSteps] 次。返回那句用户的话在 messages 里的序号。
 *
 * 模型自己开了任务的那一轮不用自动开，但也不碍事：任务已经开着，自动开的条件（还没有任务）不成立。
 */
private fun runsNeedingAutoTask(messages: List<ChatMessage>): Set<Int> {
    val runs = mutableSetOf<Int>()
    var run = -1
    var steps = 0
    messages.forEachIndexed { index, message ->
        when (message) {
            is ChatMessage.User -> {
                run = index
                steps = 0
            }
            is ChatMessage.Assistant -> {
                steps += message.toolCalls.count { it.name !in NotPhoneOperations }
                if (run >= 0 && steps >= AutoTaskMinSteps) runs += run
            }
            is ChatMessage.ToolResult -> Unit
        }
    }
    return runs
}

/** 自动开的任务用什么标题：用户那句话的第一行，太长截断。只发了图片的退回一句泛泛的。 */
private fun autoTaskTitle(userText: String): String {
    val firstLine = userText.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (firstLine.isEmpty()) return DefaultAutoTaskTitle
    val max = StartTaskTool.MAX_TITLE_LENGTH
    return if (firstLine.length > max) firstLine.take(max) + "…" else firstLine
}

/** 能复制的文字：用户说的话、模型回复里的文字。只发了图片、只调了工具的那条没得复制。 */
private fun ChatMessage.copyableText(): String? = when (this) {
    is ChatMessage.User -> text.takeIf { it.isNotBlank() }
    is ChatMessage.Assistant -> text?.takeIf { it.isNotBlank() }
    is ChatMessage.ToolResult -> null
}

/** 空会话时的引导。模型没配好的话，这里是用户最该看到提示的地方。 */
@Composable
private fun EmptyHint(
    isModelConfigured: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Kitten",
            style = MaterialTheme.typography.headlineMedium,
            color = KittenTheme.colors.textPrimary,
        )
        Text(
            text = if (isModelConfigured) {
                "说点什么吧～"
            } else {
                "还没有配置模型。去「设置」填上 API Key 就能开始对话了。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = KittenTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        if (!isModelConfigured) {
            androidx.compose.material3.TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("去设置", color = KittenTheme.colors.accentStrong)
            }
        }
    }
}

/**
 * Agent 的提问卡片，压在对话最下面、紧挨着输入框。
 *
 * 选项做成按钮，点一下就答完。没给选项时（比如要填收货地址）提示用户在输入框里打字——
 * 那种情况下 [ChatViewModel.send] 会把输入当成答案，而不是一条新消息。
 *
 * 用户这会儿不在 Kitten 界面里的话，同一个问题也在常驻通知上，两边答都算数。
 */
@Composable
private fun QuestionCard(
    question: AgentQuestion,
    onAnswer: (String) -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    // 卡片自己那层玻璃，给里面的按钮折射：用外面的 backdrop 的话按钮会透出卡片背后的东西，
    // 像在卡片上挖了个洞。
    val surfaceBackdrop = rememberLayerBackdrop()
    GlassSurface(
        backdrop = backdrop,
        cornerRadius = GlassRadius.Large,
        exportedBackdrop = surfaceBackdrop,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = question.question,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
            )
            question.options.forEach { option ->
                GlassButton(
                    text = option,
                    onClick = { onAnswer(option) },
                    backdrop = surfaceBackdrop,
                    style = GlassButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (question.options.isEmpty()) {
                Text(
                    text = "在下面的输入框里回答",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

/**
 * 已经问过的问题，答完以后留在对话里它原来的位置上，回头翻记录看得到当时选了什么。
 *
 * 要和模型的回复（直接铺在页面上的字）一眼分开，所以从三处做出"卡片"的样子：
 *
 * - 卡片那层玻璃（[chatCardGlass]，和任务卡片共用）：底色深一档、带软阴影，浮在页面上。
 * - 问题字大一号，前面一个带问号的对话框图标，一看就是"这是个问题"，不用再加标题。问和答中间
 *   一道两头尖的分界线隔开。
 * - 回答做成一个白色的小块、前面打个勾，长得和提问卡片里的选项按钮一样：就是当时点的那个
 *   选项，定在了这里。
 *
 * @param answer 用户的回答；null 表示没等到回答任务就停了，这时不画勾和白块，只写一句淡淡的说明。
 */
@Composable
private fun AnsweredQuestionCard(
    question: String,
    answer: String?,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 读屏一口气读完"问题，回答"，不用一行行摸。
            .semantics(mergeDescendants = true) {}
            .chatCardGlass(backdrop, colors)
            .padding(ChatCardPadding),
        verticalArrangement = Arrangement.spacedBy(AnsweredCardGap),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // 图标顶着问题第一行放：问题折成几行时，图标还是挨着第一行，不往中间跑。对齐的细节见
            // QuestionIconTopInset。
            Icon(
                imageVector = KittenIcons.ContactSupport,
                contentDescription = null,
                // 和发送按钮里的图标同一种浅灰。
                tint = colors.textSecondary,
                modifier = Modifier
                    .padding(top = QuestionIconTopInset)
                    .size(QuestionIconSize),
            )
            Spacer(modifier = Modifier.width(QuestionIconGap))
            Text(
                text = question,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
        }
        // 问和答中间的分界线：中间最粗，往两头一点点收成一个尖，像一道细细的梭子。
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(DividerThickness)
                .drawWithCache {
                    val path = Path().apply {
                        moveTo(0f, size.height / 2f)
                        lineTo(size.width / 2f, 0f)
                        lineTo(size.width, size.height / 2f)
                        lineTo(size.width / 2f, size.height)
                        close()
                    }
                    onDrawBehind { drawPath(path, colors.accentStrong) }
                },
        )
        if (answer != null) {
            Row(
                modifier = Modifier
                    .clip(AnsweredChoiceShape)
                    .background(colors.controlTint)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = "你的回答",
                    tint = colors.accentStrong,
                    modifier = Modifier.size(AnsweredCheckSize),
                )
                Text(
                    text = answer,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textPrimary,
                )
            }
        } else {
            Text(
                text = "没来得及回答",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textTertiary,
            )
        }
    }
}

/** 等模型想的时候。正在执行哪一步不在这里说，在工具清单那一行上（见 [ToolCallList]）。 */
@Composable
private fun TypingIndicator() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(
            text = "正在思考…",
            style = MaterialTheme.typography.bodySmall,
            color = KittenTheme.colors.textTertiary,
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
        )
    }
}

/**
 * 模型没开任务时，一轮里操作手机满几步就自动收进任务卡片（见 [buildChatItems]）。
 *
 * 3 步：看一眼屏幕、开个应用再看一眼这种一两步就完的，摊在对话里也不吵；真在办事的一轮，第三步
 * 往往就到了。给小了，随手问一句都变成卡片；给大了，前面那几步会先摊开、到了门槛才突然收成卡片，
 * 跳一下更明显。
 */
private const val AutoTaskMinSteps = 3

/** 自动开的任务、用户那句话又是空的（只发了图片）时用的标题。 */
private const val DefaultAutoTaskTitle = "办你交代的事"

/** "正在思考…"和提问卡片那两项的 key。气泡的 key 是序号（Int），清单的是 "tools-" 开头，都撞不上。 */
private const val TypingKey = "typing"
private const val QuestionKey = "question"

/** 问题、分界线、回答三段之间空多少。 */
private val AnsweredCardGap = 6.dp

/**
 * 问题前面那个图标多大、和问题隔多远。图标比字（16sp）大一圈，是这张卡片一眼认得出的记号。
 *
 * [QuestionIconTopInset]：图标顶上往下挪多少。图标 28dp 比问题的一行（24sp）还高，顶着放（0）时
 * 第一行字只比图标正中偏上 2dp，看着就是居中的。换成比一行矮的图标时要往下挪，挪
 * （行高 − 图标）/ 2 正好和第一行字居中。
 */
private val QuestionIconSize = 28.dp
private val QuestionIconGap = 10.dp
private val QuestionIconTopInset = 0.dp

/** 问和答中间那道分界线最粗处（正中间）多粗，往两头收到 0。 */
private val DividerThickness = 2.dp

/** 回答那个白块的圆角，和提问卡片里的选项按钮同一档。 */
private val AnsweredChoiceShape = RoundedCornerShape(GlassRadius.Small)

/** 回答前面那个勾多大。 */
private val AnsweredCheckSize = 16.dp

/** 离最底下多远才算"翻上去了"、浮出回到最新的按钮：差一点点就冒出来会一闪一闪的。 */
private val AwayFromLatestThreshold = 48.dp

/** 回到最新的按钮看得见的那个圆有多大，和顶栏按钮一样。 */
private val ScrollToLatestSize = 40.dp

/** 按钮离输入栏上沿多远。按钮的触摸区（48dp）比圆大一圈，看起来的间距比这个再大 4dp。 */
private val ScrollToLatestGap = 4.dp
