package me.wjz.kitten.ui.setup

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.R
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassIconTile
import me.wjz.kitten.ui.component.GlassListItem
import me.wjz.kitten.ui.component.GlassStatus
import me.wjz.kitten.ui.component.GlassStatusTone

/**
 * 一项系统权限：图标、名称、是否已开启；没开的话右边给一个去开启的按钮。
 *
 * 已经开了就不再显示按钮——这一行在设置页里常驻，每次看到都挂着一个"去开启"会让人
 * 以为还没弄好。要关掉的话去系统设置里关，那本来就是系统的地盘。
 */
@Composable
fun PermissionItem(
    backdrop: Backdrop,
    icon: ImageVector,
    title: String,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassListItem(
        title = title,
        modifier = modifier,
        leading = { GlassIconTile(icon) },
        supportingContent = {
            GlassStatus(
                text = stringResource(
                    if (enabled) R.string.status_enabled else R.string.status_disabled,
                ),
                tone = if (enabled) GlassStatusTone.Positive else GlassStatusTone.Warning,
            )
        },
        trailing = if (enabled) {
            null
        } else {
            {
                GlassButton(
                    text = stringResource(R.string.action_open_settings),
                    onClick = onOpenSettings,
                    backdrop = backdrop,
                    style = GlassButtonStyle.Secondary,
                    compact = true,
                )
            }
        },
    )
}
