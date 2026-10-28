package me.wjz.kitten.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.data.memory.MemoryEntry
import me.wjz.kitten.data.memory.MemoryStore
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.Delete
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页上的"记忆"：一张大卡片，标题右边写着记了几条，里面一张小卡露出最近的几条，点了打开
 * 模态窗（[MemoryDialog]）一条条地看、改、删、手动加。
 *
 * 记忆本身是 Agent 在对话里用工具记下的（见 agent/tools/MemoryTools），这里是给用户兜底的
 * 地方：记错了能改，不想被记住的能删。
 *
 * @param memories 全部记忆，最近改过的在前面。
 */
@Composable
fun MemorySection(
    backdrop: Backdrop,
    memories: List<MemoryEntry>,
    onOpen: () -> Unit,
) {
    GlassSection(
        backdrop = backdrop,
        title = "记忆",
        icon = Icons.Filled.Favorite,
        headerTrailing = {
            Text(
                text = "${memories.size} 条",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = KittenTheme.colors.accentStrong,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
        ) {
            MemoryTile(memories = memories, onClick = onOpen)
        }
    }
}

/**
 * 小卡：一句俏皮的说明，下面是最近记下的几条（没记过就是一句提示）。和系统提示词的小卡一个
 * 样子：按下垫一层淡天依蓝，松手才打开；标题后面一个"›"提示能点。
 */
@Composable
private fun MemoryTile(memories: List<MemoryEntry>, onClick: () -> Unit) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "管理记忆") {
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
                text = "Kitten 记住的关于你的小事",
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
        if (memories.isEmpty()) {
            Text(
                text = "还什么都没记住呢～跟 Kitten 说说你的口味吧！",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
        } else {
            memories.take(PreviewCount).forEach { entry ->
                Text(
                    text = "· ${entry.content}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 管理记忆的模态窗：顶上手动记一条，下面一行一条，每行能改、能删。
 *
 * 改是原地改：点了那一行变成输入框，下面一个保存一个取消，同一时间只改一条。删是点了就删，弹个
 * Toast（调用方弹）——记忆一句话的事，删错了重新记一条就好，不值得再弹一层确认。
 *
 * 一行一个玻璃按钮的话，几十条记忆就是上百块玻璃，滚起来很重；行上的"改""删"做成不带玻璃的
 * 小图标（[RowAction]），按下垫一层淡天依蓝，只有正在改的那一行才用玻璃输入框和按钮。
 */
@Composable
fun MemoryDialog(
    visible: Boolean,
    memories: List<MemoryEntry>,
    onAdd: (String) -> Unit,
    onUpdate: (id: Long, content: String) -> Unit,
    onDelete: (id: Long) -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    var newDraft by remember { mutableStateOf("") }
    // 正在改哪一条（null 是没在改），和改到一半的内容。
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editDraft by remember { mutableStateOf("") }
    // 关掉就清空：下次打开从头来，不留着上次打了一半的字。
    LaunchedEffect(visible) {
        if (!visible) {
            newDraft = ""
            editingId = null
        }
    }

    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = "记忆",
        icon = Icons.Filled.Favorite,
    ) { modalBackdrop ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 2.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "记错了就改掉，不想让 Kitten 知道的就删掉～",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            GlassTextField(
                value = newDraft,
                onValueChange = { newDraft = it.take(MemoryStore.MAX_CONTENT_LENGTH) },
                label = null,
                backdrop = modalBackdrop,
                placeholder = "手动记一条，比如：我对花生过敏",
                singleLine = false,
                maxLines = DraftMaxLines,
            )
            GlassButton(
                text = "记下来",
                onClick = {
                    onAdd(newDraft.trim())
                    newDraft = ""
                },
                backdrop = modalBackdrop,
                enabled = newDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (memories.isEmpty()) {
                Text(
                    text = "还什么都没记住呢 (｡･ω･｡)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textTertiary,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            memories.forEach { entry ->
                key(entry.id) {
                    if (editingId == entry.id) {
                        EditingRow(
                            draft = editDraft,
                            onDraftChange = { editDraft = it.take(MemoryStore.MAX_CONTENT_LENGTH) },
                            canSave = editDraft.isNotBlank() && editDraft.trim() != entry.content,
                            onSave = {
                                onUpdate(entry.id, editDraft.trim())
                                editingId = null
                            },
                            onCancel = { editingId = null },
                            backdrop = modalBackdrop,
                        )
                    } else {
                        MemoryRow(
                            entry = entry,
                            onEdit = {
                                editingId = entry.id
                                editDraft = entry.content
                            },
                            onDelete = {
                                if (editingId == entry.id) editingId = null
                                onDelete(entry.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 一条记忆：内容，右边"改""删"两个小图标。底子和设置页上的小卡一样。 */
@Composable
private fun MemoryRow(entry: MemoryEntry, onEdit: () -> Unit, onDelete: () -> Unit) {
    val colors = KittenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(TileShape)
            .drawBehind { drawRect(colors.controlTint) }
            .border(width = 1.dp, color = colors.hairline, shape = TileShape)
            .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.content,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp),
        )
        RowAction(icon = Icons.Filled.Edit, label = "修改", tint = colors.textSecondary, onClick = onEdit)
        RowAction(icon = KittenIcons.Delete, label = "删除", tint = colors.danger, onClick = onDelete)
    }
}

/** 正在改的那一条：一个输入框，下面保存、取消并排。 */
@Composable
private fun EditingRow(
    draft: String,
    onDraftChange: (String) -> Unit,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    backdrop: Backdrop,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassTextField(
            value = draft,
            onValueChange = onDraftChange,
            label = null,
            backdrop = backdrop,
            singleLine = false,
            maxLines = DraftMaxLines,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton(
                text = "保存",
                onClick = onSave,
                backdrop = backdrop,
                enabled = canSave,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "取消",
                onClick = onCancel,
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 行上的小图标按钮，不带玻璃（原因见 [MemoryDialog]）：按下垫一个淡天依蓝的圆，松手才算数。
 * 触摸区比图标大一圈，好按。
 */
@Composable
private fun RowAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    Box(
        modifier = Modifier
            .size(ActionTouchSize)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnClick() } }
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) drawCircle(colors.sliderIdle, alpha = pressed)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(ActionIconSize),
        )
    }
}

/** 小卡和每一行的圆角，和系统提示词的小卡同一档。 */
private val TileShape = RoundedCornerShape(GlassRadius.Small)

/** 小卡上露出最近几条。够看出记了些什么，多了设置页就被它占满了。 */
private const val PreviewCount = 3

/** 手动记一条、改一条时输入框最多长到几行，再多在框里滚。一条记忆本来就是一句话，四行足够。 */
private const val DraftMaxLines = 4

/** 行上"改""删"的触摸区和图标多大。 */
private val ActionTouchSize = 40.dp
private val ActionIconSize = 20.dp
