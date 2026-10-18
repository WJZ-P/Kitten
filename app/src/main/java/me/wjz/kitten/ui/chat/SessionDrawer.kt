package me.wjz.kitten.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.wjz.kitten.data.session.SessionSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 左侧抽屉里的历史会话列表。
 *
 * 只渲染 [SessionSummary]（标题 + 预览 + 时间），不碰消息本身——会话多起来以后，
 * 把每个会话的全部消息都读出来只为显示一行预览，纯属浪费。真正打开时才加载完整内容。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "历史会话",
                style = MaterialTheme.typography.titleMedium,
                color = KittenTheme.colors.textPrimary,
            )
            TextButton(onClick = onNewSession) {
                Text("新对话", color = KittenTheme.colors.accentStrong)
            }
        }

        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "还没有历史会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = KittenTheme.colors.textTertiary,
                )
            }
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    isCurrent = session.id == currentSessionId,
                    onClick = { onOpenSession(session.id) },
                    onDelete = { onDeleteSession(session.id) },
                )
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionSummary,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.bodyMedium,
                // 当前打开的那条高亮，否则一眼看不出自己在哪。
                color = if (isCurrent) KittenTheme.colors.accentStrong else KittenTheme.colors.textPrimary,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDelete) {
                Text("删除", style = MaterialTheme.typography.bodySmall, color = KittenTheme.colors.danger)
            }
        }
        session.preview?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = KittenTheme.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "${formatTime(session.updatedAtMillis)} · ${session.messageCount} 条",
            style = MaterialTheme.typography.labelSmall,
            color = KittenTheme.colors.textTertiary,
        )
    }
}

/**
 * 时间显示。
 *
 * 今天的只显示时分，更早的显示日期——会话列表里一串完整日期既占地方又难扫读。
 */
private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val pattern = if (now - millis < DAY_MILLIS) "HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
