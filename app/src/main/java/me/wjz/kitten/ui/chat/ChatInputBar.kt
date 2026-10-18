package me.wjz.kitten.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.component.GlassSurface
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 底部输入栏。
 *
 * 用 [BasicTextField] 而不是 Material 的 `TextField`：后者自带一整套容器样式
 * （背景、下划线、浮动标签），放在玻璃上会多出一层不透明的底，把折射效果盖住。
 * 这里只需要光标和文字，容器交给 [GlassSurface]。
 */
@Composable
fun ChatInputBar(
    backdrop: Backdrop,
    enabled: Boolean,
    hint: String?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 输入内容留在这一层而不是 ViewModel：它是纯粹的界面中间态，
    // 没发出去之前不属于业务数据。抬到 ViewModel 只会让每次按键都触发一轮状态更新。
    var text by remember { mutableStateOf("") }

    fun submit() {
        val content = text.trim()
        if (content.isEmpty() || !enabled) return
        onSend(content)
        text = ""
    }

    GlassSurface(
        backdrop = backdrop,
        cornerRadius = 26.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = hint ?: "说点什么…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = KittenTheme.colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = KittenTheme.colors.textPrimary),
                    cursorBrush = SolidColor(KittenTheme.colors.accentStrong),
                    // 多行但有上限：长文本不该把整个屏幕顶掉。
                    maxLines = 5,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = ImeAction.Send,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSend = { submit() },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp),
                )
            }
            IconButton(
                onClick = ::submit,
                enabled = enabled && text.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送",
                    tint = if (enabled && text.isNotBlank()) {
                        KittenTheme.colors.accentStrong
                    } else {
                        KittenTheme.colors.textTertiary
                    },
                )
            }
        }
    }
}
