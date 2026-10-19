package me.wjz.kitten.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 对话页：顶栏 + 消息列表 + 输入栏。
 *
 * 无状态——所有数据由 [ChatUiState] 给，所有动作往上抛。这样 Preview 和以后做测试
 * 都不需要真的起一个 ViewModel。
 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    backdrop: Backdrop,
    onSend: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ChatTopBar(
            title = state.title.ifBlank { "新对话" },
            onOpenDrawer = onOpenDrawer,
            onNewSession = onNewSession,
        )
        Box(modifier = Modifier.weight(1f)) {
            if (state.isEmpty) {
                EmptyHint(
                    isModelConfigured = state.isModelConfigured,
                    onOpenSettings = onOpenSettings,
                )
            } else {
                MessageList(
                    messages = state.messages,
                    isResponding = state.isResponding,
                    backdrop = backdrop,
                )
            }
        }
        ChatInputBar(
            backdrop = backdrop,
            enabled = state.canSend,
            hint = if (state.isModelConfigured) null else "请先在设置里配置模型",
            onSend = onSend,
        )
    }
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isResponding: Boolean,
    backdrop: Backdrop,
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp,
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
) {
    Column(
        modifier = Modifier
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
