package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 向上的箭头，对话页的发送按钮用。Material Symbols 的 arrow_upward。 */
val KittenIcons.ArrowUpward: ImageVector
    get() = arrowUpward ?: materialSymbol(
        name = "ArrowUpward",
        pathData = "M440-160v-487L216-423l-56-57 320-320 320 320-56 57-224-224v487h-80Z",
    ).also { arrowUpward = it }

private var arrowUpward: ImageVector? = null
