package me.wjz.kitten.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.ui.glass.GlassSurface

/**
 * 一条消息。
 *
 * 三种角色的呈现差别很大，所以没有做成"一个气泡组件配不同参数"：
 * - 用户消息靠右，实心高亮，是"我说的话"；
 * - 模型回复靠左，用玻璃材质，和背景融为一体；
 * - 工具调用/结果是**过程信息**，不是对话，所以压扁成一行小字。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    when (message) {
        is ChatMessage.User -> UserBubble(message.text, modifier)
        is ChatMessage.Assistant -> AssistantBubble(message, backdrop, modifier)
        // 工具结果本身不展示——它是给模型看的原始数据（整屏节点树能有几千字符），
        // 塞进对话里只会淹没真正的内容。用户需要知道的是"调用了什么工具"，
        // 那个信息在 Assistant 消息的 toolCalls 里已经显示了。
        is ChatMessage.ToolResult -> Unit
    }
}

@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            modifier = Modifier
                // 不占满整行，留出左边的空白，视觉上才有"从右边来"的感觉。
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(UserBubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AssistantBubble(
    message: ChatMessage.Assistant,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        GlassSurface(
            backdrop = backdrop,
            cornerRadius = 18.dp,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                message.text?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
                // 让用户看见 Agent 在做什么，而不是对着"正在思考"干等。
                message.toolCalls.forEach { call ->
                    Text(
                        text = "⚙ ${call.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AccentTint,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private val UserBubbleColor = Color(0xFF1E6F93)
