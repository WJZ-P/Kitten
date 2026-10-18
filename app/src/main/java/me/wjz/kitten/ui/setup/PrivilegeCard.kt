package me.wjz.kitten.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.wjz.kitten.ui.glass.GlassCard
import me.wjz.kitten.R
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.client.WirelessDebugController
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 增强模式：通过无线调试拿到 ADB 权限。全程手机闭环。
 **/
@Composable
fun PrivilegeCard(
    backdrop: Backdrop,
    onStartPairing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(context) { WirelessDebugController.getInstance(context) }
    val state by PrivilegeManager.state.collectAsState()
    val step by controller.step.collectAsState()

    GlassCard(backdrop = backdrop, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.setup_privilege_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                text = stringResource(R.string.setup_privilege_guide),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.66f),
            )
            // ADB 侧的进度只在还没就绪时才有意义。已经 Ready 了还追加一句
            // "等待交回 binder"会自相矛盾——binder 早就交回来了，
            // 那只是 ADB 链路的最后一个步骤名，后面本来就没有步骤了。
            val stepLabel = if (state is PrivilegeState.Ready) null else stepText(step)
            Text(
                text = stringResource(
                    R.string.setup_privilege_status,
                    stateText(state) + stepLabel.orEmpty().let { if (it.isEmpty()) "" else " · $it" },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state is PrivilegeState.Ready) ReadyTint else Color.White,
            )
            Button(
                onClick = onStartPairing,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Text(stringResource(R.string.setup_privilege_action_pair))
            }
        }
    }
}

private val ReadyTint = Color(0xFF66CCFF)

@Composable
private fun stateText(state: PrivilegeState): String = when (state) {
    PrivilegeState.Idle -> stringResource(R.string.setup_privilege_state_idle)
    PrivilegeState.Connecting -> stringResource(R.string.setup_privilege_state_connecting)
    is PrivilegeState.Ready -> stringResource(R.string.setup_privilege_state_ready, state.uid)
    is PrivilegeState.Error -> stringResource(R.string.setup_privilege_state_error, state.message)
}

/** 返回 null 表示没有需要展示的进度。分隔符由调用方拼，不塞进文案里。 */
@Composable
private fun stepText(step: WirelessDebugController.Step): String? = when (step) {
    WirelessDebugController.Step.Idle -> null
    WirelessDebugController.Step.Pairing -> stringResource(R.string.setup_privilege_step_pairing)
    WirelessDebugController.Step.Connecting -> stringResource(R.string.setup_privilege_step_connecting)
    WirelessDebugController.Step.Launching -> stringResource(R.string.setup_privilege_step_launching)
    WirelessDebugController.Step.Launched -> stringResource(R.string.setup_privilege_step_launched)
    is WirelessDebugController.Step.Failed ->
        stringResource(R.string.setup_privilege_step_failed, step.message)
}

@Preview(showBackground = true)
@Composable
private fun PrivilegeCardPreview() {
    KittenTheme {
        PrivilegeCard(backdrop = rememberLayerBackdrop(), onStartPairing = {})
    }
}
