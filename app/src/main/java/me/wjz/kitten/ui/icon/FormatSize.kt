package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 一大一小两个 T：设置页"字号"那一行。Material Symbols 的 format_size。 */
val KittenIcons.FormatSize: ImageVector
    get() = formatSize ?: materialSymbol(
        name = "FormatSize",
        pathData = "M560-160v-520H360v-120h520v120H680v520H560Zm-360 0v-320H80v-120h360v120H320v320H200Z",
    ).also { formatSize = it }

private var formatSize: ImageVector? = null
