package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 方块，对话页回复期间发送按钮换成的"停止"。Material Symbols 的 stop。 */
val KittenIcons.Stop: ImageVector
    get() = stop ?: materialSymbol(
        name = "Stop",
        pathData = "M320-640v320-320Zm-80 400v-480h480v480H240Zm80-80h320v-320H320v320Z",
    ).also { stop = it }

private var stop: ImageVector? = null
