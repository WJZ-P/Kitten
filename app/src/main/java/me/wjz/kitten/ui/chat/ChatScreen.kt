package me.wjz.kitten.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.wjz.kitten.llm.ChatMessage
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
 * @param bottomInsets 输入栏下面要让出来的位置：平时是底部栏，键盘弹起时是键盘。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    backdrop: Backdrop,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    bottomInsets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val listBackdrop = rememberLayerBackdrop()
    // 输入栏连同它下面让出来的那一截的高度，列表末尾留出这么多。
    var overlayHeight by remember { mutableStateOf(0.dp) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ChatTopBar(
                title = state.title.ifBlank { "新对话" },
                onOpenDrawer = onOpenDrawer,
                onNewSession = onNewSession,
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
                    MessageList(
                        messages = state.messages,
                        isResponding = state.isResponding,
                        backdrop = backdrop,
                        bottomPadding = overlayHeight,
                    )
                }
            }
        }
        ChatInputBar(
            backdrop = rememberCombinedBackdrop(backdrop, listBackdrop),
            enabled = state.canSend,
            hint = if (state.isModelConfigured) null else "请先在设置里配置模型",
            onSend = onSend,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 量在让位之前：量到的是输入栏加上它下面让出来的那一截。
                .onSizeChanged { overlayHeight = with(density) { it.height.toDp() } }
                .windowInsetsPadding(bottomInsets),
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isResponding: Boolean,
    backdrop: Backdrop,
    bottomPadding: Dp,
) {
    val listState = rememberLazyListState()

    // 新消息进来就滚到底。只在数量变化时滚，否则用户往上翻历史会被不停拽回来。
    LaunchedEffect(messages.size, isResponding) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp + bottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages) { message ->
            MessageBubble(message = message, backdrop = backdrop)
        }
        if (isResponding) {
            item { TypingIndicator() }
        }
    }
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
