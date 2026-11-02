package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.vector.ImageVector

/** 闪光的星星：设置页"技能"那一行。Material Symbols 的 star_shine。 */
val KittenIcons.StarShine: ImageVector
    get() = starShine ?: materialSymbol24(
        name = "StarShine",
        pathData = "M21.3,18.7l-3-3l1.4-1.4l3,3l-1.4,1.4Zm-3.6-12L16.3,5.3l3-3l1.4,1.4l-3,3ZM6.3,6.7l-3-3L4.7,2.3l3,3" +
            "L6.3,6.7Zm-3.6,12L1.3,17.3l3-3l1.4,1.4l-3,3Zm6.15-1.88L12,14.93l3.15,1.93l-0.82-3.6l2.78-2.4" +
            "l-3.65-0.33L12,7.13L10.55,10.5L6.9,10.83l2.78,2.43L8.85,16.83ZM5.83,21l1.63-7.03L2,9.25L9.2,8.63" +
            "L12,2l2.8,6.63L22,9.25l-5.45,4.72L18.18,21L12,17.27L5.83,21ZM12,11.98Z",
    ).also { starShine = it }

private var starShine: ImageVector? = null
