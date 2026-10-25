package me.wjz.kitten.ui.icon

import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Kitten 自己的图标。用法和 Material 的 `Icons.Filled.Xxx` 一样：`KittenIcons.Delete`，交给
 * [Icon] 或者 GlassIconButton 之类收 [ImageVector] 的地方。
 *
 * 每个图标一个文件，写成 `KittenIcons` 的扩展属性，第一次用到时才建（同 Material 图标的做法）。
 *
 * ## 加一个新图标
 *
 * 1. 去 Google Fonts 的 Material Symbols（fonts.google.com/icons）下载 SVG，参数和已有的保持
 *    一致：FILL 0、wght 400、GRAD 0、opsz 24，风格才统一。
 * 2. 在这个文件夹里新建一个文件，照着 Delete.kt 写，把 SVG 里 `<path d="…">` 的 d 原样贴进
 *    [materialSymbol]，不用自己换算坐标。
 */
object KittenIcons

/**
 * 把一个 Material Symbols 的 SVG 做成 [ImageVector]。
 *
 * 那边下载的 SVG 都是 24dp、`viewBox="0 -960 960 960"`：原点在左下角，y 往上数，全是负的。
 * ImageVector 的视口从 (0, 0) 开始，所以整组往下挪 960，path 原样用。
 *
 * 填充色无所谓：[Icon] 画的时候会整个换成 tint。
 */
internal fun materialSymbol(name: String, pathData: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    )
        .addGroup(translationY = 960f)
        .addPath(pathData = addPathNodes(pathData), fill = SolidColor(Color.Black))
        .clearGroup()
        .build()
