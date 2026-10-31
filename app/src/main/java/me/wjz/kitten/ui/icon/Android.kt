package me.wjz.kitten.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 安卓小机器人的头：Material Symbols 的 android。认不出是哪家的模型时，输入栏上切换模型的圆按钮里
 * 画它（见 ui/chat/ModelBrand）。
 *
 * 这一份 SVG 是 `viewBox="0 0 24 24"`、原点在左上角，和 [materialSymbol] 收的那种 960 的视口不一样，
 * 所以不走它，按 24 的视口直接建。path 原样贴的，没改形状。填充色无所谓，画的时候整个换成 tint。
 */
val KittenIcons.Android: ImageVector
    get() = androidIcon ?: ImageVector.Builder(
        name = "Android",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    )
        .addPath(pathData = addPathNodes(AndroidPath), fill = SolidColor(Color.Black))
        .build()
        .also { androidIcon = it }

private var androidIcon: ImageVector? = null

private const val AndroidPath =
    "M1,18q0.25-2.63 1.6-4.78t3.48-3.5L4.15,6.5Q3.93,6.15 4.04,5.75T4.5,5.15Q4.85,4.93 5.25,5.02q0.4,0.1 " +
        "0.6,0.45l2,3.33Q8.8,8.4 9.84,8.2T12,8t2.16,0.2t1.99,0.6l2-3.33q0.2-0.35 0.6-0.45q0.4-0.1 0.75,0.13" +
        "q0.35,0.2 0.45,0.6T19.85,6.5L17.93,9.73q2.13,1.35 3.48,3.5T23,18H1Zm17.01-3.79q0.04-0.44-0.24-0.86" +
        "T17.1,12.8t-0.73,0.1Q16.05,13.13 16,13.55q-0.05,0.43 0.23,0.85q0.27,0.43 0.69,0.56t0.74-0.09t0.36-0.66Z" +
        "M7.1,14.95q0.4-0.13 0.67-0.55q0.28-0.42 0.24-0.86T7.65,12.88T6.91,12.79T6.23,13.35Q5.95,13.77 6,14.2" +
        "t0.38,0.65t0.73,0.1Z"
