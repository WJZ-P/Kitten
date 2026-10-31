package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 手机：技能页里"操作手机"那一行。Material Symbols 的 smartphone。 */
val KittenIcons.Smartphone: ImageVector
    get() = smartphone ?: materialSymbol(
        name = "Smartphone",
        pathData = "M280-40q-33 0-56.5-23.5T200-120v-720q0-33 23.5-56.5T280-920h400q33 0 56.5 23.5T760-840v124q18 7 29 " +
            "22t11 34v80q0 19-11 34t-29 22v404q0 33-23.5 56.5T680-40H280Zm0-80h400v-720H280v720Zm0 0v-720 720Zm228.5-" +
            "611.5Q520-743 520-760t-11.5-28.5Q497-800 480-800t-28.5 11.5Q440-777 440-760t11.5 28.5Q463-720 480-720t28.5-" +
            "11.5Z",
    ).also { smartphone = it }

private var smartphone: ImageVector? = null
