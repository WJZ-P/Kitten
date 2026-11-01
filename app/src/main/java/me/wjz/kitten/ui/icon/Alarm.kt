package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 闹钟：技能页"系统能力"那一行、任务页里排着的提醒。Material Symbols 的 alarm。 */
val KittenIcons.Alarm: ImageVector
    get() = alarm ?: materialSymbol(
        name = "Alarm",
        pathData = "M339.5-108.5q-65.5-28.5-114-77t-77-114Q120-365 120-440t28.5-140.5q28.5-65.5 77-114t114-77Q405-800 " +
            "480-800t140.5 28.5q65.5 28.5 114 77t77 114Q840-515 840-440t-28.5 140.5q-28.5 65.5-77 114t-114 77Q555-80 " +
            "480-80t-140.5-28.5ZM480-440Zm112 168 56-56-128-128v-184h-80v216l152 152ZM224-866l56 56-170 170-56-56 " +
            "170-170Zm512 0 170 170-56 56-170-170 56-56ZM480-160q117 0 198.5-81.5T760-440q0-117-81.5-198.5T480-720q-" +
            "117 0-198.5 81.5T200-440q0 117 81.5 198.5T480-160Z",
    ).also { alarm = it }

private var alarm: ImageVector? = null
