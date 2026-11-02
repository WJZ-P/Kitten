package me.wjz.kitten.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.data.persona.Persona
import me.wjz.kitten.data.persona.PersonaPresets
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * "人设"二级页：一个人设一张卡片，写着名字和第一句话（一行，放不下就截断），正在用的那张标着"正在用"。
 *
 * 点卡片就换成它，下一句话起生效（提示词每轮开始时才读一次，见 SystemPrompt）。卡片右边一支笔，点了进编辑页
 * （[PersonaEditorPage]）改；最下面一张"捏一个新人设"手动加一个。
 *
 * 一张卡片一个人设，而不是挤在一个分组里一行一个：人设是挑着换的，一张一张分开看着更像"选一个角色"。
 *
 * @param personas 全部人设，自带的在前。
 * @param activeId 正在用的那个。
 */
@Composable
fun PersonasPage(
    personas: List<Persona>,
    activeId: String,
    onSelect: (Persona) -> Unit,
    onEdit: (Persona) -> Unit,
    onAdd: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(CardGap)) {
        personas.forEach { persona ->
            key(persona.id) {
                PersonaCard(
                    persona = persona,
                    active = persona.id == activeId,
                    onSelect = { onSelect(persona) },
                    onEdit = { onEdit(persona) },
                    backdrop = backdrop,
                )
            }
        }
        SettingsGroup(backdrop = backdrop) {
            SettingsRow(
                icon = Icons.Filled.Add,
                iconTint = colors.accentStrong,
                title = "捏一个新人设",
                onClick = onAdd,
            )
        }
        Text(
            text = "点一下就换成她，下一句话开始生效～",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = HintInset, end = HintInset),
        )
    }
}

/**
 * 一张人设卡片：名字（正在用的后面跟着"正在用"），下面一行是人设的第一句话。按下整张铺一层水波，在卡片里抬手
 * 才算选了它；右边那支笔自己接手指，按在笔上是去编辑，不算选。
 */
@Composable
private fun PersonaCard(
    persona: Persona,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    SettingsGroup(backdrop = backdrop) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { selected = active }
                .clickable(
                    interactionSource = null,
                    indication = ripple(color = colors.accentStrong),
                    role = Role.RadioButton,
                    onClickLabel = "换成${persona.name}",
                    onClick = onSelect,
                )
                .heightIn(min = CardMinHeight)
                .padding(start = GlassContentPadding, end = CardEndPadding, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = persona.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = NameFontSize),
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (active) {
                        Text(
                            text = "正在用",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.accentStrong,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    text = remember(persona.content) { persona.content.previewLine() },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = PreviewFontSize),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowAction(icon = Icons.Filled.Edit, label = "改${persona.name}", tint = colors.textSecondary, onClick = onEdit)
        }
    }
}

/** 卡片上那一行：第一句正文（跳过 Markdown 的标题和空行）。 */
private fun String.previewLine(): String =
    lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: "还什么都没写"

/**
 * 改一个人设、或者捏一个新的：名字、人设正文，加上保存；自带的多一个"恢复默认"，自己捏的多一个删除。页面标题
 * 和返回按钮在 SettingsScreen 里。
 *
 * 改的是一份草稿（[draft]），由调用方在进来时拷一份：点保存才写回，不保存直接返回就丢掉，和编辑服务商一样。
 * "恢复默认"也只是把草稿换成原样，同样要点保存才算数——点错了返回就好。
 *
 * @param isNew 新捏的：没有删除按钮。
 */
@Composable
fun PersonaEditorPage(
    draft: Persona,
    isNew: Boolean,
    onDraftChange: (Persona) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    val original = remember(draft.id) { PersonaPresets.find(draft.id) }
    GlassSection(backdrop = backdrop, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 一两句就够，俏皮一点：这是捏角色的地方，不是填技术参数。操作手机的本事是自带的，顺口提一句，
            // 免得用户把工具用法也往这里抄。
            Text(
                text = if (original != null) {
                    "这是自带的人设，想怎么改都行，改坏了点「恢复默认」～"
                } else {
                    "捏一个新角色～名字、性格、口癖随你写，操作手机的本事是自带的，不用写进来。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            GlassTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it.take(Persona.MAX_NAME_LENGTH))) },
                label = "名字",
                backdrop = backdrop,
                placeholder = "比如：兔娘",
            )
            GlassTextField(
                value = draft.content,
                onValueChange = { onDraftChange(draft.copy(content = it.take(Persona.MAX_CONTENT_LENGTH))) },
                label = "人设",
                backdrop = backdrop,
                placeholder = "她叫什么、什么性格、平时怎么说话、有什么口癖……",
                singleLine = false,
                minLines = EditorMinLines,
                maxLines = EditorMaxLines,
            )
            GlassButton(
                text = "保 存",
                onClick = onSave,
                backdrop = backdrop,
                enabled = draft.name.isNotBlank() && draft.content.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge,
            )
            if (original != null) {
                GlassButton(
                    text = "恢复默认",
                    onClick = { onDraftChange(original) },
                    backdrop = backdrop,
                    style = GlassButtonStyle.Secondary,
                    // 已经是原样了，再点也没变化。
                    enabled = draft.name != original.name || draft.content != original.content,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (!isNew) {
                // 删掉就连写好的一大段一起没了：第一下只是变成确认，再点一次才删，和删技能一样。
                var confirming by remember(draft.id) { mutableStateOf(false) }
                GlassButton(
                    text = if (confirming) "再点一次，确认删除" else "删除这个人设",
                    onClick = {
                        if (confirming) {
                            onDelete()
                        } else {
                            confirming = true
                        }
                    },
                    backdrop = backdrop,
                    style = GlassButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 卡片和卡片之间空多少：比分组之间近一点，看得出是同一类东西。 */
private val CardGap = 10.dp

/** 卡片至少多高、右边留白（编辑按钮自带一圈触摸区，留得少一点）。 */
private val CardMinHeight = 64.dp
private val CardEndPadding = 6.dp

/** 名字和那一行预览的字号，和设置里列表行的标题、说明一样大。 */
private val NameFontSize = 17.sp
private val PreviewFontSize = 13.sp

/** 页底那句小字往里缩多少：和分组名对齐。 */
private val HintInset = 8.dp

/**
 * 编辑框至少几行高、最多长到几行，再多在框里滚动。
 *
 * 自带的人设有一千来字，用户捏角色也可能写一大段（背景故事、口癖……），框给大一点。页面本身能滚，键盘弹起来时
 * 设置页会让出键盘的高度（KittenApp 里的 imePadding），正在打字的那一行会被滚进视野；框也别太高，不然键盘一弹，
 * 下面的"保存"得先收起键盘才看得到。
 */
private const val EditorMinLines = 8
private const val EditorMaxLines = 16
