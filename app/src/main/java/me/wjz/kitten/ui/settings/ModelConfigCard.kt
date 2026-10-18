package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.LlmConfig
import me.wjz.kitten.llm.LlmPreset
import me.wjz.kitten.ui.glass.GlassCard

/**
 * 模型配置卡片。
 *
 * 预设只是帮用户省掉查文档，不是白名单——任何兼容 OpenAI 协议的服务（包括自建的
 * Ollama）填上地址就能用，所以每个字段都保持可编辑。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigCard(
    backdrop: Backdrop,
    config: LlmConfig,
    onConfigChange: (LlmConfig) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var presetMenuOpen by remember { mutableStateOf(false) }
    // API Key 默认遮起来。设置页可能在别人旁边打开，密钥不该直接亮在屏幕上。
    var keyVisible by remember { mutableStateOf(false) }

    GlassCard(backdrop = backdrop, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = "模型",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )

            ExposedDropdownMenuBox(
                expanded = presetMenuOpen,
                onExpandedChange = { presetMenuOpen = !presetMenuOpen },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "服务商预设",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = config.format.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentTint,
                        )
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = presetMenuOpen)
                    }
                }
                ExposedDropdownMenu(
                    expanded = presetMenuOpen,
                    onDismissRequest = { presetMenuOpen = false },
                ) {
                    LlmPreset.ALL.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(preset.label) },
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
                                presetMenuOpen = false
                            },
                        )
                    }
                }
            }

            ConfigField(
                label = "API 地址",
                value = config.baseUrl,
                placeholder = "留空用官方默认",
                onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            )
            ConfigField(
                label = "模型名",
                value = config.model,
                placeholder = "deepseek-chat",
                onValueChange = { onConfigChange(config.copy(model = it)) },
            )
            ConfigField(
                label = "API Key",
                value = config.apiKey,
                placeholder = "sk-…",
                onValueChange = { onConfigChange(config.copy(apiKey = it)) },
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailing = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(
                            text = if (keyVisible) "隐藏" else "显示",
                            style = MaterialTheme.typography.bodySmall,
                            color = AccentTint,
                        )
                    }
                },
            )

            Text(
                text = "密钥保存在应用私有目录，其它应用读不到；但 root 过的设备或系统备份" +
                    "仍可能读到明文，请自行斟酌是否在本机存放高权限密钥。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
            )

            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
private fun ConfigField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.6f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.3f),
                    )
                }
                // 和输入栏同理：Material 的 TextField 自带不透明容器，会盖住玻璃效果。
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    visualTransformation = visualTransformation,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                    cursorBrush = SolidColor(AccentTint),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            trailing?.invoke()
        }
    }
}

private val AccentTint = Color(0xFF66CCFF)
