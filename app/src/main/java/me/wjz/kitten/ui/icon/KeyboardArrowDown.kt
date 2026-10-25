package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 向下的尖角，对话页"回到最新消息"的按钮用。Material Symbols 的 keyboard_arrow_down。 */
val KittenIcons.KeyboardArrowDown: ImageVector
    get() = keyboardArrowDown ?: materialSymbol(
        name = "KeyboardArrowDown",
        pathData = "M480-344 240-584l56-56 184 184 184-184 56 56-240 240Z",
    ).also { keyboardArrowDown = it }

private var keyboardArrowDown: ImageVector? = null
