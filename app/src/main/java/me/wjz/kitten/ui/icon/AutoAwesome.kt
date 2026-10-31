package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 三颗星星：自我进化。技能页"自我进化"那一行、任务卡片下面"已学习到新知识！"那一行、Kitten 自己学的
 * 技能都用它。Material Symbols 的 auto_awesome。
 */
val KittenIcons.AutoAwesome: ImageVector
    get() = autoAwesome ?: materialSymbol(
        name = "AutoAwesome",
        pathData = "m760-600-50-110-110-50 110-50 50-110 50 110 110 50-110 50-50 110Zm0 560-50-110-110-50 110-50 50-" +
            "110 50 110 110 50-110 50-50 110ZM360-160 260-380 40-480l220-100 100-220 100 220 220 100-220 100-100 220Zm0-" +
            "194 40-86 86-40-86-40-40-86-40 86-86 40 86 40 40 86Zm0-126Z",
    ).also { autoAwesome = it }

private var autoAwesome: ImageVector? = null
