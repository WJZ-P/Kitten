package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 删除：一个垃圾桶。Material Symbols 的 delete。 */
val KittenIcons.Delete: ImageVector
    get() = delete ?: materialSymbol(
        name = "Delete",
        pathData = "M280-120q-33 0-56.5-23.5T200-200v-520h-40v-80h200v-40h240v40h200v80h-40v520q0 33-23.5 " +
            "56.5T680-120H280Zm400-600H280v520h400v-520ZM360-280h80v-360h-80v360Zm160 0h80v-360h-80v360ZM280-720" +
            "v520-520Z",
    ).also { delete = it }

private var delete: ImageVector? = null
