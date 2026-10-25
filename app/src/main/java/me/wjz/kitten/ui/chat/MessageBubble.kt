package me.wjz.kitten.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 一条消息。
 *
 * 用户的话和模型的回复都是带小尾巴的半透明气泡（[ChatBubble]，背后只模糊、不做液态玻璃），
 * 尾巴指向说话的那一边：
 * - 用户消息靠右，染成浅天依蓝，是"我说的话"；
 * - 模型回复靠左，薄薄一层底色，和背景融为一体；
 * - 工具调用/结果是**过程信息**，不是对话，所以压扁成一行小字。
 *
 * 气泡里的内容一段一段往下排：现在只有文字，以后的图片作为单独的一段放进去，见 [ChatBubble]。
 *
 * @param lifted 这一条的长按菜单正开着：气泡一直鼓着。
 * @param onLongPress 长按气泡，参数是手指的窗口坐标。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: (positionInWindow: Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.text, backdrop, lifted, onLongPress, modifier)
        is ChatMessage.Assistant -> AssistantBubble(message, backdrop, lifted, onLongPress, modifier)
        // 工具结果本身不展示——它是给模型看的原始数据（整屏节点树能有几千字符），
        // 塞进对话里只会淹没真正的内容。用户需要知道的是"调用了什么工具"，
        // 那个信息在 Assistant 消息的 toolCalls 里已经显示了。
        is ChatMessage.ToolResult -> Unit
    }
}

@Composable
private fun UserBubble(
    text: String,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        ChatBubble(
            side = BubbleSide.End,
            backdrop = backdrop,
            tinted = true,
            lifted = lifted,
            onLongPress = onLongPress,
            // 不占满整行，留出左边的空白，视觉上才有"从右边来"的感觉。
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = KittenTheme.colors.onAccent,
                modifier = Modifier.padding(BubbleTextPadding),
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage.Assistant,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        ChatBubble(
            side = BubbleSide.Start,
            backdrop = backdrop,
            lifted = lifted,
            onLongPress = onLongPress,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(BubbleTextPadding)) {
                message.text?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KittenTheme.colors.textPrimary,
                    )
                }
                // 让用户看见 Agent 在做什么，而不是对着"正在思考"干等。
                message.toolCalls.forEach { call ->
                    Text(
                        text = "⚙ ${call.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = KittenTheme.colors.accentStrong,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
