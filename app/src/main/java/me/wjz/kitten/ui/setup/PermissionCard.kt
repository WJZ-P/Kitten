package me.wjz.kitten.ui.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.glass.GlassCard
import me.wjz.kitten.R

/** 一项系统权限的开关卡片：显示是否已启用，点按钮跳到对应的系统设置页。 */
@Composable
fun PermissionCard(
    backdrop: Backdrop,
    title: String,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassCard(backdrop = backdrop, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                Text(
                    text = stringResource(
                        if (enabled) R.string.status_enabled else R.string.status_disabled,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    // 已开启用品牌蓝，未开启压暗——状态一眼可辨，不用去读文字。
                    color = if (enabled) EnabledTint else Color.White.copy(alpha = 0.6f),
                )
            }
            Spacer(modifier = Modifier.padding(8.dp))
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    // 玻璃上放实心按钮会很突兀，用半透明白让它也像一块更亮的玻璃。
                    containerColor = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                ),
            ) {
                Text(text = stringResource(R.string.action_open_settings))
            }
        }
    }
}

private val EnabledTint = Color(0xFF66CCFF)
