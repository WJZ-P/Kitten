package me.wjz.kitten.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import me.wjz.kitten.R
import me.wjz.kitten.permission.PermissionStatus
import me.wjz.kitten.privilege.client.WirelessDebugController
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 权限引导页（有状态）：负责读取并刷新权限状态，把渲染交给 [SetupContent]。
 *
 * 权限是用户去系统设置里开关的，App 这边收不到回调，只能每次回到前台重新读一次。
 * 以前这段逻辑写在 MainActivity.onResume 里，现在跟着界面走，Activity 不用再操心。
 */
@Composable
fun SetupScreen(
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { WirelessDebugController.getInstance(context) }
    var status by remember { mutableStateOf(PermissionStatus.read(context)) }

    LifecycleResumeEffect(context) {
        status = PermissionStatus.read(context)
        // 配对过之后就不该再让用户点按钮连接了。放在"回到前台"而不是"首次进入"，
        // 是因为重启后无线调试默认关着：用户去设置里打开再切回来，这里正好接上。
        // 失败会被静默吞掉，控制器内部也挡住了重入，多触发几次没有副作用。
        controller.tryAutoConnect()
        onPauseOrDispose { }
    }

    SetupContent(status = status, actions = actions, modifier = modifier)
}

/** 权限引导页（无状态）：给什么画什么，方便 Preview 和以后做测试。 */
@Composable
private fun SetupContent(
    status: PermissionStatus,
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
        )
        Text(
            text = stringResource(R.string.setup_intro),
            style = MaterialTheme.typography.bodyMedium,
        )
        PermissionCard(
            title = stringResource(R.string.accessibility_service_label),
            enabled = status.accessibilityEnabled,
            onOpenSettings = actions::openAccessibilitySettings,
        )
        PermissionCard(
            title = stringResource(R.string.notification_listener_label),
            enabled = status.notificationListenerEnabled,
            onOpenSettings = actions::openNotificationListenerSettings,
        )
        PrivilegeCard(onStartPairing = actions::startPairing)
    }
}

@Preview(showBackground = true)
@Composable
private fun SetupContentPreview() {
    KittenTheme {
        SetupContent(
            status = PermissionStatus(
                accessibilityEnabled = true,
                notificationListenerEnabled = false,
            ),
            actions = SetupActions.Noop,
        )
    }
}
