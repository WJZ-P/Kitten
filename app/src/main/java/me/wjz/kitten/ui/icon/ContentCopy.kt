package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 复制：两张叠着的纸。Material Symbols 的 content_copy。 */
val KittenIcons.ContentCopy: ImageVector
    get() = contentCopy ?: materialSymbol(
        name = "ContentCopy",
        pathData = "M360-240q-33 0-56.5-23.5T280-320v-480q0-33 23.5-56.5T360-880h360q33 0 56.5 23.5T800-800v480q0 " +
            "33-23.5 56.5T720-240H360Zm0-80h360v-480H360v480ZM200-80q-33 0-56.5-23.5T120-160v-560h80v560h440v80H200Zm" +
            "160-240v-480 480Z",
    ).also { contentCopy = it }

private var contentCopy: ImageVector? = null
