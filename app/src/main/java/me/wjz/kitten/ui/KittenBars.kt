package me.wjz.kitten.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
