package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.R
import me.wjz.kitten.llm.LlmConfigStore
import me.wjz.kitten.permission.PermissionStatus
import me.wjz.kitten.permission.SelfConfigurator
import me.wjz.kitten.privilege.client.WirelessDebugController
import me.wjz.kitten.ui.glass.GlassCard
import me.wjz.kitten.ui.glass.GlassSwitch
import me.wjz.kitten.ui.setup.DebugCard
import me.wjz.kitten.ui.setup.PermissionCard
import me.wjz.kitten.ui.setup.PrivilegeCard
import me.wjz.kitten.ui.setup.SetupActions

/**
 * 设置页：模型配置 + 权限管理。
 *
 * 原先这些内容独占一个启动页（SetupScreen），现在收进 tab。权限引导本来就是
 * 一次性的事，配好之后不该还占着首屏——首屏该是对话。
 *
 * 权限状态每次回到前台都要重读：用户是去系统设置里开关的，App 这边收不到回调。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    actions: SetupActions,
    onModelConfigChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { WirelessDebugController.getInstance(context) }
    var status by remember { mutableStateOf(PermissionStatus.read(context)) }
    var config by remember { mutableStateOf(LlmConfigStore.load(context)) }

    LifecycleResumeEffect(context) {
        // 拿到 WRITE_SECURE_SETTINGS 之后，无障碍和通知使用权由 Kitten 自己开。
        // 放在读取状态之前，这样下面读到的就是刚配好的结果。
        SelfConfigurator.applyAll(context)
        status = PermissionStatus.read(context)
        // 配对过之后就不该再让用户点按钮连接了。放在"回到前台"而不是"首次进入"，
        // 是因为重启后无线调试默认关着：用户去设置里打开再切回来，这里正好接上。
        controller.tryAutoConnect()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )

        ModelConfigCard(
            backdrop = backdrop,
            config = config,
            onConfigChange = { config = it },
            onSave = {
                LlmConfigStore.save(context, config)
                // 对话页的"模型是否已配置"是启动时读的，这里要主动通知它重读。
                onModelConfigChanged()
            },
        )

        PermissionCard(
            backdrop = backdrop,
            title = stringResource(R.string.accessibility_service_label),
            enabled = status.accessibilityEnabled,
            onOpenSettings = actions::openAccessibilitySettings,
        )
        PermissionCard(
            backdrop = backdrop,
            title = stringResource(R.string.notification_listener_label),
            enabled = status.notificationListenerEnabled,
            onOpenSettings = actions::openNotificationListenerSettings,
        )
        PrivilegeCard(backdrop = backdrop, onStartPairing = actions::startPairing)

        // 临时：看液态玻璃开关的效果，定下来之后换成真的设置项。
        SwitchPreviewCard(backdrop = backdrop)

        // 临时：验证副屏可用性，验完连同 DebugCard.kt 一起删。
        DebugCard()
    }
}

/**
 * 开关样式预览。
 *
 * **这几个开关不接任何逻辑**，只有本地状态，纯粹用来看 [GlassSwitch] 的手感：轨道上
 * 任意位置点一下都能切换，也可以按住拖过去，按下的那一瞬拨钮会鼓起来变成透镜。
 *
 * 等真的有要做开关的设置项（比如"自动授予权限"、"详细日志"）时，把这张卡换成真的
 * 配置项并接上 `LlmConfigStore` 那套存储即可，组件本身不用改。
 */
@Composable
private fun SwitchPreviewCard(backdrop: Backdrop, modifier: Modifier = Modifier) {
    var autoGrant by remember { mutableStateOf(true) }
    var verboseLog by remember { mutableStateOf(false) }

    GlassCard(backdrop = backdrop, modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "开关预览",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = "mock，不接逻辑。可以点，也可以按住拖。",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.45f),
            )

            SwitchRow(
                label = "自动授予权限",
                checked = autoGrant,
                onCheckedChange = { autoGrant = it },
                backdrop = backdrop,
            )
            SwitchRow(
                label = "打印详细日志",
                checked = verboseLog,
                onCheckedChange = { verboseLog = it },
                backdrop = backdrop,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    backdrop: Backdrop,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 拨钮按下时会鼓到 36dp，比轨道的 28dp 高——留出余量，不然会贴到上下两行。
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier
                .weight(1f)
                .padding(end = 16.dp),
        )
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            backdrop = backdrop,
        )
    }
}
