package me.wjz.kitten.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.component.GlassTab
import me.wjz.kitten.ui.component.GlassTabBar
import me.wjz.kitten.ui.theme.KittenTheme

/** 底部的三个 tab。 */
enum class KittenTab(val label: String, val icon: ImageVector) {
    Chat("对话", Icons.Filled.Email),
    Task("任务", Icons.Filled.List),
    Settings("设置", Icons.Filled.Settings),
}

/**
 * 顶栏：左边拉出历史会话，右边开新对话。
 *
 * 两个按钮只在对话页显示——任务页和设置页既没有历史会话，也没有"新建"的概念，
 * 留着会让人以为能点。
 */
@Composable
fun KittenTopBar(
    title: String,
    icon: ImageVector,
    showSessionActions: Boolean,
    onOpenDrawer: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSessionActions) {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Filled.Menu, contentDescription = "历史会话", tint = KittenTheme.colors.textPrimary)
            }
        } else {
            // 没有菜单按钮的页面，在同一个位置放这一页的图标：标题在三个 tab 之间保持同一个
            // 位置，切换时不会左右跳，左边也不会空出一块。
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = KittenTheme.colors.accentStrong,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

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

        if (showSessionActions) {
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = "新对话", tint = KittenTheme.colors.textPrimary)
            }
        }
    }
}

/**
 * 底部 tab 栏。
 *
 * 没用 Material 的 `NavigationBar`：它自带不透明的容器色，会把底下的玻璃折射盖住，
 * 而且它的选中指示器是一块固定的 pill，没法做成会鼓起、位移、形变的液态滑块。
 * 具体怎么叠出来的见 [GlassTabBar]。
 *
 * 注意三个 tab 的图标和文字**一律给正文色**，不区分选中态——高亮是滑块折射出来的，
 * 这里再染一遍会和滑块错开半格。
 */
@Composable
fun KittenBottomBar(
    backdrop: Backdrop,
    current: KittenTab,
    onSelect: (KittenTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = KittenTab.entries
    GlassTabBar(
        backdrop = backdrop,
        selectedIndex = current.ordinal,
        // 滑块被拖到某一格之后回调的是序号，这里换回 tab。
        onSelect = { onSelect(tabs[it]) },
        tabCount = tabs.size,
        modifier = modifier.padding(horizontal = 64.dp, vertical = 12.dp),
    ) {
        tabs.forEach { tab ->
            GlassTab(selected = tab == current, onSelect = { onSelect(tab) }) {
                Icon(
                    imageVector = tab.icon,
                    // 下面的文字已经是标签了，tab 又把两者合成一个读屏节点，这里再给
                    // 描述会被念两遍。
                    contentDescription = null,
                    tint = KittenTheme.colors.textPrimary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = KittenTheme.colors.textPrimary,
                )
            }
        }
    }
}
