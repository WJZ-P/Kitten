package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.data.skills.BuiltinSkill
import me.wjz.kitten.data.skills.UserSkill
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassSwitch
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.icon.AutoAwesome
import me.wjz.kitten.ui.icon.ContactSupport
import me.wjz.kitten.ui.icon.Extension
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.icon.Smartphone
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * "技能"二级页：上面一组是自定义的（用户自己写的、或者在对话里让 Kitten 写的），下面一组是默认的
 * （Kitten 自带的），每一行右边一个开关。自定义的放上面：那是用户自己的东西，常来这里多半是为了它们；
 * 默认的一般开一次就不动了。
 *
 * 关掉的技能，下一轮对话起那一组工具不交给模型，系统提示词里那一段也不写（见 SystemPrompt、
 * ChatViewModel）。正在进行的那一轮不受影响：一轮里工具和提示词都不能变。
 *
 * 默认技能只能开关；自定义的点一下进编辑页（[SkillEditorPage]）改，最后一行"教 Kitten 一个新技能"
 * 手动加一个。在对话里让 Kitten 写的技能（save_skill）、它办完事自己学的技能（自我进化，见
 * SkillReflector）也存进"自定义"，自己学的打头是小星星。开关是玻璃的，它自己接手指，按在开关上不会
 * 算成点了那一行。
 *
 * @param builtins 每个默认技能开没开。
 * @param userSkills 全部自定义技能（开着的、关着的都有）。
 */
@Composable
fun SkillsPage(
    builtins: Map<BuiltinSkill, Boolean>,
    userSkills: List<UserSkill>,
    onToggleBuiltin: (BuiltinSkill, Boolean) -> Unit,
    onToggleUser: (UserSkill, Boolean) -> Unit,
    onEdit: (UserSkill) -> Unit,
    onAdd: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors

    Column {
        SettingsGroupLabel("自定义")
        SettingsGroup(backdrop = backdrop) {
            userSkills.forEach { skill ->
                key(skill.id) {
                    // Kitten 自己学的打头是小星星、说明前面标一句，一眼分得出哪些是它自己记的、还没看过。
                    // 点进去看过、保存了，就算用户的了（见 SettingsScreen）。
                    val whenToUse = skill.whenToUse.trim().ifEmpty { "还没写什么时候用" }
                    SettingsRow(
                        icon = if (skill.isLearned) KittenIcons.AutoAwesome else KittenIcons.Extension,
                        iconTint = if (skill.isLearned) colors.iconPink else colors.iconAmber,
                        title = skill.name,
                        subtitle = if (skill.isLearned) "Kitten 自己学的 · $whenToUse" else whenToUse,
                        showChevron = false,
                        trailing = {
                            GlassSwitch(
                                checked = skill.enabled,
                                onCheckedChange = { onToggleUser(skill, it) },
                                backdrop = backdrop,
                            )
                        },
                        onClick = { onEdit(skill) },
                    )
                    SettingsDivider()
                }
            }
            SettingsRow(
                icon = Icons.Filled.Add,
                iconTint = colors.accentStrong,
                title = "教 Kitten 一个新技能",
                onClick = onAdd,
            )
        }
        // 告诉用户还能在对话里让 Kitten 写技能、它办完事也会自己学。"编写技能"关着的话，顺口提一句要先
        // 打开它，免得用户去说了却没反应。
        val writingOn = builtins[BuiltinSkill.SkillWriting] ?: true
        Text(
            text = "可以在对话里让 Kitten 帮你写技能，它办完事也会自己学，都会存到这里～" +
                (if (writingOn) "" else "让它写要先把下面的「编写技能」打开哦。"),
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = HintInset, top = 8.dp, end = HintInset),
        )
    }

    Column {
        SettingsGroupLabel("默认")
        SettingsGroup(backdrop = backdrop) {
            BuiltinSkill.entries.forEachIndexed { index, skill ->
                if (index > 0) SettingsDivider()
                SettingsRow(
                    icon = skill.icon,
                    iconTint = colors.tintOf(skill),
                    title = skill.title,
                    subtitle = skill.summary,
                    showChevron = false,
                    trailing = {
                        GlassSwitch(
                            checked = builtins[skill] ?: true,
                            onCheckedChange = { onToggleBuiltin(skill, it) },
                            backdrop = backdrop,
                        )
                    },
                )
            }
        }
    }
}

/**
 * 教一个新技能、或者改一个已有的：名字、什么时候用、怎么做，加上保存和删除。页面标题和返回按钮在
 * SettingsScreen 里。
 *
 * 改的是一份草稿（[draft]），由调用方在进来时拷一份：点保存才写回，不保存直接返回就丢掉，和编辑
 * 服务商一样。名字和"怎么做"都填了才能保存；重名由调用方检查（它手里有全部技能）。
 *
 * @param isNew 新教的：没有删除按钮。
 */
@Composable
fun SkillEditorPage(
    draft: UserSkill,
    isNew: Boolean,
    onDraftChange: (UserSkill) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    GlassSection(backdrop = backdrop, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 8.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = if (draft.isLearned) {
                    "这是 Kitten 办完事自己学的，看看哪里不对就改～点保存就算你教的啦。"
                } else {
                    "教 Kitten 一个新本事～写清楚什么时候用、怎么做，它碰到对得上的事就会照着做。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary,
            )
            GlassTextField(
                value = draft.name,
                onValueChange = { onDraftChange(draft.copy(name = it.take(UserSkill.MAX_NAME_LENGTH))) },
                label = "名字",
                backdrop = backdrop,
                placeholder = "比如：帮我点奶茶",
            )
            GlassTextField(
                value = draft.whenToUse,
                onValueChange = { onDraftChange(draft.copy(whenToUse = it.take(UserSkill.MAX_WHEN_TO_USE_LENGTH))) },
                label = "什么时候用",
                backdrop = backdrop,
                placeholder = "比如：我说想喝奶茶，或者让你帮我点杯喝的",
                singleLine = false,
                minLines = WhenToUseMinLines,
                maxLines = WhenToUseMaxLines,
            )
            GlassTextField(
                value = draft.instructions,
                onValueChange = { onDraftChange(draft.copy(instructions = it.take(UserSkill.MAX_INSTRUCTIONS_LENGTH))) },
                label = "怎么做",
                backdrop = backdrop,
                placeholder = "一步步写清楚：打开哪个外卖软件、去哪家店、点什么、甜度冰度怎么选、付钱之前要不要先问我……",
                singleLine = false,
                minLines = InstructionsMinLines,
                maxLines = InstructionsMaxLines,
            )
            GlassButton(
                text = "保 存",
                onClick = onSave,
                backdrop = backdrop,
                enabled = draft.name.isNotBlank() && draft.instructions.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.titleLarge,
            )
            if (!isNew) {
                // 删掉就连写好的说明一起没了：第一下只是变成确认，再点一次才删，和删服务商一样。
                var confirming by remember(draft.id) { mutableStateOf(false) }
                GlassButton(
                    text = if (confirming) "再点一次，确认删除" else "删除这个技能",
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

/** 默认技能在列表里叫什么。 */
private val BuiltinSkill.title: String
    get() = when (this) {
        BuiltinSkill.Phone -> "操作手机"
        BuiltinSkill.Ask -> "向你提问"
        BuiltinSkill.Memory -> "记忆"
        BuiltinSkill.SkillWriting -> "编写技能"
        BuiltinSkill.SelfLearning -> "自我进化"
    }

/** 默认技能下面那行小字：它能干什么，一句大白话。 */
private val BuiltinSkill.summary: String
    get() = when (this) {
        BuiltinSkill.Phone -> "给AI装上眼睛和双手！"
        BuiltinSkill.Ask -> "拿不准的时候，弹出选项问你"
        BuiltinSkill.Memory -> "记住你的偏好，换了对话也记得"
        BuiltinSkill.SkillWriting -> "在对话里帮你写技能，写好存到上面"
        BuiltinSkill.SelfLearning -> "办完事自己复盘，学到的也存到上面"
    }

/** 默认技能的图标，和对话里对应的东西用同一个（提问卡片的问号、记忆的心、学到新知识的小星星）。 */
private val BuiltinSkill.icon: ImageVector
    get() = when (this) {
        BuiltinSkill.Phone -> KittenIcons.Smartphone
        BuiltinSkill.Ask -> KittenIcons.ContactSupport
        BuiltinSkill.Memory -> Icons.Filled.Favorite
        BuiltinSkill.SkillWriting -> Icons.Filled.Edit
        BuiltinSkill.SelfLearning -> KittenIcons.AutoAwesome
    }

/** 默认技能图标的颜色：一个技能一种，记忆和设置首页"记忆"那一行同色，自我进化和它学到的技能同色。 */
private fun KittenColors.tintOf(skill: BuiltinSkill): Color = when (skill) {
    BuiltinSkill.Phone -> iconBlue
    BuiltinSkill.Ask -> iconViolet
    BuiltinSkill.Memory -> iconCoral
    BuiltinSkill.SkillWriting -> iconGreen
    BuiltinSkill.SelfLearning -> iconPink
}

/** 页底那句小字往里缩多少：和分组名对齐。 */
private val HintInset = 8.dp

/** "什么时候用"的输入框至少几行、最多长到几行：一两句话的事。 */
private const val WhenToUseMinLines = 2
private const val WhenToUseMaxLines = 4

/** "怎么做"的输入框至少几行、最多长到几行，再多在框里滚：步骤可能写得很长，框给大一点，和改人设一样。 */
private const val InstructionsMinLines = 8
private const val InstructionsMaxLines = 16
