package me.wjz.kitten.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 模型配置。
 *
 * 预设只是帮用户省掉查文档，不是白名单——任何兼容 OpenAI 协议的服务（包括自建的
 * Ollama）填上地址就能用，所以每个字段都保持可编辑。
 *
 * 预设从下拉菜单改成一排 chip：一共就七个，平铺出来扫一眼就能点，不用先展开再找；
 * Material 的下拉菜单是不透明的弹层，也和玻璃格格不入。
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
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "服务商预设",
                style = MaterialTheme.typography.labelMedium,
                color = KittenTheme.colors.textSecondary,
                modifier = Modifier.padding(horizontal = GlassContentPadding),
            )
            // 留白放在滚动区域里面：chip 能一直滚到卡片边缘，而不是在离边 16dp 的地方
            // 被齐刷刷切断。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = GlassContentPadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LlmPreset.ALL.forEach { preset ->
                    GlassChip(
                        text = preset.label,
                        // 预设没有单独存，按"格式 + 地址都对得上"反推当前用的是哪一家。
                        selected = preset.format == config.format && preset.baseUrl == config.baseUrl,
                        onClick = {
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
                    )
                }
            }

            Column(
                modifier = Modifier.padding(horizontal = GlassContentPadding),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                GlassTextField(
                    value = config.baseUrl,
                    onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
                    label = "API 地址",
                    placeholder = "留空用官方默认",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                GlassTextField(
                    value = config.model,
                    onValueChange = { onConfigChange(config.copy(model = it)) },
                    label = "模型名",
                    placeholder = "deepseek-chat",
                )
                GlassTextField(
                    value = config.apiKey,
                    onValueChange = { onConfigChange(config.copy(apiKey = it)) },
                    label = "API Key",
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
}
