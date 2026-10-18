package me.wjz.kitten.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassChip
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDivider
import me.wjz.kitten.ui.component.GlassIconTile
import me.wjz.kitten.ui.component.GlassListItem
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassSlider
import me.wjz.kitten.ui.component.GlassSwitch
import me.wjz.kitten.ui.setup.DebugCard
import me.wjz.kitten.ui.setup.PermissionItem
import me.wjz.kitten.ui.setup.PrivilegeSection
import me.wjz.kitten.ui.setup.SetupActions
import kotlin.math.roundToInt
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页：模型配置 + 权限管理。
 *
 * 原先这些内容独占一个启动页（SetupScreen），现在收进 tab。权限引导本来就是
 * 一次性的事，配好之后不该还占着首屏——首屏该是对话。
 *
 * 权限状态每次回到前台都要重读：用户是去系统设置里开关的，App 这边收不到回调。
 *
 * 页面本身不再放"设置"大标题：顶栏已经写着了，再写一遍只是占地方。
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ModelConfigSection(
            backdrop = backdrop,
            config = config,
            onConfigChange = { config = it },
            onSave = {
                LlmConfigStore.save(context, config)
                // 对话页的"模型是否已配置"是启动时读的，这里要主动通知它重读。
                onModelConfigChanged()
            },
        )

        GlassSection(backdrop = backdrop, title = "权限") {
            PermissionItem(
                backdrop = backdrop,
                icon = Icons.Filled.Face,
                title = stringResource(R.string.accessibility_service_label),
                enabled = status.accessibilityEnabled,
                onOpenSettings = actions::openAccessibilitySettings,
            )
            GlassDivider()
            PermissionItem(
                backdrop = backdrop,
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notification_listener_label),
                enabled = status.notificationListenerEnabled,
                onOpenSettings = actions::openNotificationListenerSettings,
            )
        }

        PrivilegeSection(backdrop = backdrop, onStartPairing = actions::startPairing)

        // 临时：看玻璃组件的手感，定下来之后换成真的设置项。
        ComponentPreviewSection(backdrop = backdrop)

        // 临时：验证副屏可用性，验完连同 DebugCard.kt 一起删。
        DebugCard()
    }
}

/**
 * 组件预览。
 *
 * **这里的控件都不接任何逻辑**，只有本地状态，纯粹用来看 `ui/component` 里那套玻璃
 * 组件的手感：开关、滑条、chip、两种按钮。等真的有对应的设置项时，把这一块换掉即可，
 * 组件本身不用改。
 */
@Composable
private fun ComponentPreviewSection(backdrop: Backdrop, modifier: Modifier = Modifier) {
    var autoGrant by remember { mutableStateOf(true) }
    var verboseLog by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0.6f) }
    var tone by remember { mutableIntStateOf(1) }

    GlassSection(
        backdrop = backdrop,
        title = "组件预览",
        footer = "mock，不接任何逻辑。按下就有反馈，松手才算数：按住开关拖回原处、" +
            "或者从滑条上开始滚动页面，都会退回原样。",
        modifier = modifier,
    ) {
        GlassListItem(
            title = "自动授予权限",
            supporting = "拿到 ADB 后自动打开无障碍",
            leading = { GlassIconTile(Icons.Filled.Check) },
            trailing = {
                GlassSwitch(checked = autoGrant, onCheckedChange = { autoGrant = it }, backdrop = backdrop)
            },
        )
        GlassDivider()
        GlassListItem(
            title = "打印详细日志",
            leading = { GlassIconTile(Icons.Filled.Info) },
            trailing = {
                GlassSwitch(checked = verboseLog, onCheckedChange = { verboseLog = it }, backdrop = backdrop)
            },
        )
        GlassDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                // 不用 String.format：它跟随系统区域设置，有的语言里小数点是逗号。
                // 数值只在松手时更新——这正是"松手才算数"的样子。
                text = "示例滑条 · ${(level * 10).roundToInt() / 10f}",
                style = MaterialTheme.typography.bodyLarge,
                color = KittenTheme.colors.textPrimary,
            )
            GlassSlider(
                value = level,
                onValueChange = { level = it },
                backdrop = backdrop,
                valueRange = 0f..2f,
                steps = 19,
            )
        }
        GlassDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("简洁", "均衡", "详细").forEachIndexed { index, label ->
                GlassChip(text = label, selected = tone == index, onClick = { tone = index })
            }
        }
        GlassDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(
                text = "主要",
                onClick = {},
                backdrop = backdrop,
                icon = Icons.Filled.Star,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "次要",
                onClick = {},
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "禁用",
                onClick = {},
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                enabled = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
