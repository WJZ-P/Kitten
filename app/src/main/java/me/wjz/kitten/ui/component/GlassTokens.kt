package me.wjz.kitten.ui.component

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.highlight.Highlight

/*
 * Kitten 玻璃组件的设计令牌：圆角、间距、边。
 *
 * **颜色不在这里**，统一在 `ui/theme` 里：色板在 `Color.kt`，用途在 `KittenColors`，
 * 组件里通过 `KittenTheme.colors.xxx` 读取。
 *
 * ## 风格
 *
 * - 会被手指拖动的控件（tab 栏、开关、滑条）沿用 iOS 的胶囊形和玻璃拨钮：这套形状
 *   和 catalog 里调好的弹簧参数是配套的，换成别的形状手感就不对了。
 * - 承载内容的容器（分组卡片、按钮、输入框）用中等圆角，不做胶囊：一整页都是胶囊
 *   会显得软塌塌的，卡片也装不下多行内容。
 * - 按钮类控件的按压反馈是**光雾**（[GlassGlow]）：按下时玻璃内部亮起一团会流动的柔光，
 *   最亮处跟着手指。
 *
 * ## 圆角为什么用 Compose 的 RoundedCornerShape
 *
 * backdrop 2.0.1 的高光着色器只认 `CornerBasedShape`（见 `HighlightStyle.getCornerRadii`），
 * 遇到 kyant 自家的 `RoundedRectangle` 会退化成"按胶囊算"，高光那圈边就和实际轮廓对不上。
 * 折射（lens）两种都认，但高光不认，所以非胶囊的形状统一用 [RoundedCornerShape]。
 *
 * kyant 的 `Capsule` 不受影响：它本来就是胶囊，退化后的算法恰好对。
 */

/** 非胶囊容器的圆角三档。内外嵌套时按"外圆角 − 间距 = 内圆角"取，边才同心。 */
object GlassRadius {
    /** 分组卡片。 */
    val Large = 16.dp

    /** 按钮、输入框。 */
    val Medium = 12.dp

    /** chip、图标块这类小件。 */
    val Small = 8.dp
}

internal val LargeShape = RoundedCornerShape(GlassRadius.Large)
internal val MediumShape = RoundedCornerShape(GlassRadius.Medium)
internal val SmallShape = RoundedCornerShape(GlassRadius.Small)

/** 卡片、列表项的左右留白。 */
val GlassContentPadding = 16.dp

/**
 * 容器的那圈边。
 *
 * 白色的高光边在浅色背景上很淡，主要靠阴影把玻璃和背景分开；这圈边负责的是边缘那一下
 * "反光"的质感。比库的默认值（0.5dp）略粗，太细就看不出来了。
 */
internal val GlassRim = Highlight(width = 0.75.dp, alpha = 0.9f)
