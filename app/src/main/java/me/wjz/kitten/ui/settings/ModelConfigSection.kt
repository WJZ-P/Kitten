package me.wjz.kitten.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.LlmConfig
import me.wjz.kitten.llm.LlmFormat
import me.wjz.kitten.llm.LlmPreset
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDropdown
import me.wjz.kitten.ui.component.GlassListItem
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassStatus
import me.wjz.kitten.ui.component.GlassStatusTone
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.component.GlassToggleButton
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页上的模型入口：只显示当前用的是哪家、哪个模型、配没配好，具体的表单在
 * [ModelConfigDialog] 里。
 *
 * 表单一共五项，平铺在设置页上会占掉大半屏，而它又是配一次就很少再动的东西。收进模态窗
 * 之后，设置页上只剩一行。
 */
@Composable
fun ModelConfigEntry(
    backdrop: Backdrop,
    config: LlmConfig,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preset = config.matchingPreset()
    GlassSection(
        backdrop = backdrop,
        title = "模型",
        icon = Icons.Filled.Star,
        modifier = modifier,
    ) {
        GlassListItem(
            title = when {
                preset != null -> preset.label
                config.baseUrl.isBlank() -> "还没选服务商"
                else -> "自定义服务"
            },
            supporting = config.model.ifBlank { "还没填模型名" },
            supportingContent = {
                GlassStatus(
                    text = if (config.isConfigured) "已配置" else "未配置，填好模型名和 API Key 才能对话",
                    tone = if (config.isConfigured) GlassStatusTone.Positive else GlassStatusTone.Warning,
                )
            },
            trailing = {
                GlassButton(
                    text = "配置",
                    onClick = onEdit,
                    backdrop = backdrop,
                    style = GlassButtonStyle.Secondary,
                    compact = true,
                )
            },
        )
    }
}

/**
 * 模型配置的模态窗。
 *
 * 编辑的是一份草稿（[draft]），由调用方在打开时从已保存的配置拷一份：点保存才写回，不保存
 * 直接关掉就丢弃，不会出现"改了一半关掉，下次打开还是改了一半的样子"。
 *
 * 预设只是帮用户省掉查文档，不是白名单——任何兼容 OpenAI 或 Anthropic 协议的服务（包括
 * 自建的 Ollama、各种中转）填上地址就能用，所以每个字段都保持可编辑。地址对不上任何预设时，
 * 表单里会多出"接口格式"一项（[FormatPicker]）让用户选协议。
 *
 * 预设用玻璃下拉菜单（[GlassDropdown]）选。Material 的下拉菜单是另开窗口的不透明弹层，
 * 和玻璃格格不入，所以没用它。
 */
@Composable
fun ModelConfigDialog(
    visible: Boolean,
    draft: LlmConfig,
    onDraftChange: (LlmConfig) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = "模型",
        icon = Icons.Filled.Star,
    ) { modalBackdrop ->
        ModelConfigForm(
            backdrop = modalBackdrop,
            config = draft,
            onConfigChange = onDraftChange,
            onSave = onSave,
        )
    }
}

@Composable
private fun ModelConfigForm(
    backdrop: Backdrop,
    config: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit,
    onSave: () -> Unit,
) {
    // API Key 默认遮起来。设置页可能在别人旁边打开，密钥不该直接亮在屏幕上。
    var keyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GlassContentPadding)
            .padding(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassDropdown(
            items = LlmPreset.ALL,
            selected = config.matchingPreset(),
            onSelect = { preset ->
                // 只填地址和格式，不碰 API Key——换服务商时密钥本来就得重填，
                // 但如果用户只是想换个模型名，清掉 key 会很烦。
                onConfigChange(
                    config.copy(
                        format = preset.format,
                        baseUrl = preset.baseUrl,
                        model = preset.sampleModel,
                    ),
                )
            },
            itemLabel = { it.label },
            label = "服务商预设",
            backdrop = backdrop,
            placeholder = "自定义",
        )
        GlassTextField(
            value = config.baseUrl,
            onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            label = "API 地址",
            backdrop = backdrop,
            placeholder = "留空用官方默认",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        GlassTextField(
            value = config.model,
            onValueChange = { onConfigChange(config.copy(model = it)) },
            label = "模型名",
            backdrop = backdrop,
            placeholder = "deepseek-chat",
        )
        GlassTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = "API Key",
            backdrop = backdrop,
            placeholder = "sk-…",
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { wellBackdrop ->
                GlassToggleButton(
                    text = if (keyVisible) "隐藏" else "显示",
                    checked = keyVisible,
                    onCheckedChange = { keyVisible = it },
                    backdrop = wellBackdrop,
                )
            },
        )

        // 选了预设时格式是那一家定死的，不用问；地址对不上任何预设（自定义）时，不知道它说
        // 哪种协议，让用户选。不显示的话，先选 Anthropic 预设再改地址，格式会悄悄停在 Anthropic。
        AnimatedVisibility(visible = config.matchingPreset() == null) {
            FormatPicker(
                format = config.format,
                onFormatChange = { onConfigChange(config.copy(format = it)) },
                backdrop = backdrop,
            )
        }

        Text(
            text = "密钥保存在应用私有目录，其它应用读不到；但 root 过的设备或系统备份" +
                "仍可能读到明文，请自行斟酌是否在本机存放高权限密钥。",
            style = MaterialTheme.typography.labelSmall,
            color = KittenTheme.colors.textTertiary,
        )

        GlassButton(
            text = "保 存",
            onClick = onSave,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge
        )
    }
}

/**
 * 自定义服务的接口格式：OpenAI 还是 Anthropic。
 *
 * [LlmFormat.OPENAI] 和 [LlmFormat.OPENAI_COMPATIBLE] 发出去的请求一模一样（只差显示名），
 * 这里合成一个选项，选它时存成 OPENAI_COMPATIBLE。
 *
 * 两个选项是两块玻璃切换按钮（[GlassToggleButton]），各占一半铺满整行；选中的那块染上强调色，
 * 点的时候鼓起、变成透镜，和别的按钮一样。
 */
@Composable
private fun FormatPicker(
    format: LlmFormat,
    onFormatChange: (LlmFormat) -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 和输入框的标签同一个样子，看起来是表单里的一项。
        Text(
            text = "接口格式",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 单选：点已经选中的那块不会取消，只有点另一块才切换。
            GlassToggleButton(
                text = "OpenAI 格式",
                checked = !format.isAnthropic,
                onCheckedChange = { if (it) onFormatChange(LlmFormat.OPENAI_COMPATIBLE) },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                pressedScale = FormatPressedScale,
            )
            GlassToggleButton(
                text = "Anthropic 格式",
                checked = format.isAnthropic,
                onCheckedChange = { if (it) onFormatChange(LlmFormat.ANTHROPIC) },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                pressedScale = FormatPressedScale,
            )
        }
        Text(
            text = "不确定就选 OpenAI 格式，大多数第三方服务和中转都兼容它。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
    }
}

/** 接口格式的两个按钮按下时鼓到多大。它们各占半行，比输入框里的小按钮宽得多，给小一点。 */
private const val FormatPressedScale = 1.05f

private val LlmFormat.isAnthropic: Boolean get() = this == LlmFormat.ANTHROPIC

/**
 * 预设没有单独存，按"地址 + 协议都对得上"反推当前用的是哪一家。对不上就是用户自己填的。
 *
 * 协议只分 OpenAI 和 Anthropic 两种：OPENAI 和 OPENAI_COMPATIBLE 是同一种协议，地址填的是
 * OpenAI 官方、格式存的是 OPENAI_COMPATIBLE 时，也该认成 OpenAI 预设。
 */
private fun LlmConfig.matchingPreset(): LlmPreset? =
    LlmPreset.ALL.firstOrNull { it.baseUrl == baseUrl && it.format.isAnthropic == format.isAnthropic }
