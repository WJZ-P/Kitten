package me.wjz.kitten.ui.glass

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop

/**
 * 液体玻璃卡片：占满宽度、带统一内边距的玻璃容器。
 *
 * 效果本身在 [GlassSurface] 里，这层只加"卡片"的版式约定——权限卡片、设置项这类
 * 整齐排列的内容用它，省得每处都重复写宽度和 padding。需要自由尺寸（消息气泡、
 * 输入栏）时直接用 [GlassSurface]。
 *
 * 要配合 [GlassBackground] 用：玻璃的效果全部来自折射背后的内容，背后是纯色的话
 * 什么都看不出来。
 */
@Composable
fun GlassCard(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(GlassContentPadding),
            content = content,
        )
    }
}
