package me.wjz.kitten

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.client.WirelessDebugController

/** 增强模式：通过无线调试拿到 ADB 权限。全程手机闭环。 */
@Composable
fun PrivilegeCard(
    controller: WirelessDebugController,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state by PrivilegeManager.state.collectAsState()
    val step by controller.step.collectAsState()
    var code by remember { mutableStateOf("") }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("增强模式（ADB · 可选）", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. 开发者选项 → 打开『无线调试』\n" +
                    "2. 点『使用配对码配对设备』，记下 6 位配对码（保持该弹窗打开）\n" +
                    "3. 回到这里输入配对码，点『配对并启动』",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "状态：${stateText(state)}${stepText(step)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text("配对码（6 位）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { scope.launch { controller.pairAndLaunch(code.trim()) } },
                enabled = code.trim().length >= 6,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("配对并启动")
            }
            OutlinedButton(
                onClick = { scope.launch { controller.connectAndLaunch() } },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("仅连接（已配对过）")
            }
        }
    }
}

private fun stateText(state: PrivilegeState): String = when (state) {
    PrivilegeState.Idle -> "未连接"
    PrivilegeState.Connecting -> "等待握手"
    is PrivilegeState.Ready -> "已就绪（uid=${state.uid}）"
    is PrivilegeState.Error -> "错误：${state.message}"
}

private fun stepText(step: WirelessDebugController.Step): String = when (step) {
    WirelessDebugController.Step.Idle -> ""
    WirelessDebugController.Step.DiscoveringPairingPort -> " · 发现配对端口中"
    WirelessDebugController.Step.Pairing -> " · 配对中"
    WirelessDebugController.Step.Connecting -> " · 连接中"
    WirelessDebugController.Step.Launching -> " · 启动特权进程中"
    WirelessDebugController.Step.Launched -> " · 已启动，等待交回 binder"
    is WirelessDebugController.Step.Failed -> " · 失败：${step.message}"
}
