package me.wjz.kitten.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.agent.SystemPrompt
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页上的"系统提示词"：一张大卡片，里面一张小卡显示开头几行，点了打开模态窗改
 * （[SystemPromptDialog]）。
 *
 * 用户改的只是**人设**那一段（Kitten 叫什么、什么性格）。怎么操作手机的那套操作手册是固定的，
 * 永远接在人设后面，这里看不到也改不到，见 [SystemPrompt]——所以人设随便写，都不会把 Agent 改坏。
 *
 * 人设可能写得很长，直接在设置页里摊开一个大输入框，既挤又看不全；放进模态窗，窗能占九成屏高，
 * 一次看得到的多得多。和服务商的编辑窗一个路子。
 *
 * @param customPrompt 用户改过的人设，没改过是 null（用默认的）。
 */
@Composable
fun SystemPromptSection(
    backdrop: Backdrop,
    customPrompt: String?,
    onEdit: () -> Unit,
) {
    GlassSection(backdrop = backdrop, title = "系统提示词", icon = Icons.Filled.Edit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
        ) {
            PromptTile(customPrompt = customPrompt, onClick = onEdit)
        }
    }
}

/**
 * 小卡：第一行是"默认"还是"已自定义"，下面是提示词的开头几行。
 *
 * 和服务商的小卡一个样子：按下垫一层淡天依蓝，松手才打开；标题后面一个"›"提示能点。
 */
@Composable
private fun PromptTile(customPrompt: String?, onClick: () -> Unit) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "编辑") {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnClick() } }
            .clip(TileShape)
            .drawBehind {
                drawRect(colors.controlTint)
                drawRect(colors.sliderIdle, alpha = press.pressProgress.coerceIn(0f, 1f))
            }
            .border(width = 1.dp, color = colors.hairline, shape = TileShape)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (customPrompt != null) "已自定义" else "默认",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Text(
            text = customPrompt ?: SystemPrompt.DEFAULT_PERSONA,
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary,
            maxLines = PreviewLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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

/** 小卡和服务商的小卡同一档圆角。 */
private val TileShape = RoundedCornerShape(GlassRadius.Small)

/** 小卡上预览几行。够看出是哪一份、大概写了什么，多了设置页就被它占满了。 */
private const val PreviewLines = 3

/**
 * 编辑框至少几行高、最多长到几行，再多在框里滚动。
 *
 * 默认人设只有三行，但用户捏角色可能写一大段（背景故事、口癖……），框给大一点。16 行（bodyMedium
 * 一行 20sp，约 320dp）加上说明和两个按钮，按估算在常见尺寸的手机上、没弹键盘时大致装得进九成屏高
 * 的窗（没实测）；装不下、或者键盘弹起来把窗压矮了，窗自己会滚，正在打字的那一行会被滚进视野。
 */
private const val EditorMinLines = 8
private const val EditorMaxLines = 16
