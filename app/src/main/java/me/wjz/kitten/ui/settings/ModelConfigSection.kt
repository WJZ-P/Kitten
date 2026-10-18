package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import me.wjz.kitten.llm.LlmPreset
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassChip
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDropdown
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 模型配置。
 *
 * 预设只是帮用户省掉查文档，不是白名单——任何兼容 OpenAI 协议的服务（包括自建的
 * Ollama）填上地址就能用，所以每个字段都保持可编辑。
 *
 * 预设用玻璃下拉菜单（[GlassDropdown]）选。Material 的下拉菜单是另开窗口的不透明弹层，
 * 和玻璃格格不入，所以没用它。
 */
@Composable
fun ModelConfigSection(
    backdrop: Backdrop,
    config: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // API Key 默认遮起来。设置页可能在别人旁边打开，密钥不该直接亮在屏幕上。
    var keyVisible by remember { mutableStateOf(false) }

    GlassSection(
        backdrop = backdrop,
        title = "模型",
        icon = Icons.Filled.Star,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 6.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            GlassDropdown(
                items = LlmPreset.ALL,
                // 预设没有单独存，按"格式 + 地址都对得上"反推当前用的是哪一家。
                selected = LlmPreset.ALL.firstOrNull { it.format == config.format && it.baseUrl == config.baseUrl },
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
                trailing = {
                    GlassChip(
                        text = if (keyVisible) "隐藏" else "显示",
                        selected = keyVisible,
                        onClick = { keyVisible = !keyVisible },
                    )
                },
            )

            Text(
                text = "密钥保存在应用私有目录，其它应用读不到；但 root 过的设备或系统备份" +
                    "仍可能读到明文，请自行斟酌是否在本机存放高权限密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = KittenTheme.colors.textTertiary,
            )

            GlassButton(
                text = "保存",
                onClick = onSave,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
