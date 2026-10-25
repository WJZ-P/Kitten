package me.wjz.kitten.ui.chat

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 对话页的顶栏：左边拉出历史会话，中间是会话标题，右边开新对话。
 *
 * 只有对话页有顶栏。任务页和设置页既没有历史会话也没有"新建"，顶栏上只剩一个页面名——
 * 而当前在哪一页，底部栏已经标着了，再写一遍只是占地方。
 */
@Composable
internal fun ChatTopBar(
    title: String,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 两边都是玻璃圆按钮，和模态窗的关闭按钮一样。顶栏下面是背景，折射的就是背景。
        GlassIconButton(
            icon = Icons.Filled.Menu,
            contentDescription = "历史会话",
            onClick = onOpenDrawer,
            backdrop = backdrop,
            size = TopBarButtonSize,
        )

        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = KittenTheme.colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )

        GlassIconButton(
            icon = Icons.Filled.Add,
            contentDescription = "新对话",
            onClick = onNewSession,
            backdrop = backdrop,
            size = TopBarButtonSize,
        )
    }
}

/**
 * 顶栏两个按钮的玻璃圆有多大（图标跟着等比例缩放）。比模态窗关闭按钮那种默认尺寸（36dp）大
 * 一号：顶栏是对话页最常点的地方，圆小了不好按、也不显眼。
 */
private val TopBarButtonSize = 40.dp
