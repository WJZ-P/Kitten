package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.agent.SystemPrompt
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 改人设的模态窗：一句说明、一个大输入框、保存、恢复默认。
 *
 * 改的是一份草稿（[draft]），由调用方在打开时拷一份：点保存才写回，不保存直接关掉就丢掉，和服务商
 * 的编辑窗一样。"恢复默认"也只是把草稿换成默认人设，同样要点保存才算数——点错了关掉就好。
 */
@Composable
fun SystemPromptDialog(
    visible: Boolean,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = "系统提示词",
        icon = Icons.Filled.Edit,
    ) { modalBackdrop ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 2.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 一两句就够，俏皮一点：这是捏人设的地方，不是填技术参数。操作手机的本事是自带的，
            // 顺口提一句，免得用户把工具用法也往这里抄。
            Text(
                text = "在这里定义 Kitten（你也可以给它改名！）的人设和性格～比如捏一只鲸鱼娘？！" +
                    "操作手机的本事是自带的，不用写进来。",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            GlassTextField(
                value = draft,
                onValueChange = onDraftChange,
                label = null,
                backdrop = modalBackdrop,
                placeholder = "留空就用回默认的 Kitten",
                singleLine = false,
                minLines = EditorMinLines,
                maxLines = EditorMaxLines,
            )
            GlassButton(
                text = "保 存",
                onClick = onSave,
                backdrop = modalBackdrop,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge,
            )
            GlassButton(
                text = "恢复默认",
                onClick = { onDraftChange(SystemPrompt.DEFAULT_PERSONA) },
                backdrop = modalBackdrop,
                style = GlassButtonStyle.Secondary,
                // 已经是默认的了，再点也没变化。
                enabled = draft != SystemPrompt.DEFAULT_PERSONA,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 编辑框至少几行高、最多长到几行，再多在框里滚动。
 *
 * 默认人设只有三行，但用户捏角色可能写一大段（背景故事、口癖……），框给大一点。16 行（bodyMedium
 * 一行 20sp，约 320dp）加上说明和两个按钮，按估算在常见尺寸的手机上、没弹键盘时大致装得进九成屏高
 * 的窗（没实测）；装不下、或者键盘弹起来把窗压矮了，窗自己会滚，正在打字的那一行会被滚进视野。
 */
private const val EditorMinLines = 8
private const val EditorMaxLines = 16
