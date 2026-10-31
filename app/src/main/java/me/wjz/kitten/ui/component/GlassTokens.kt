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
 * - 承载内容的容器（分组卡片、按钮、输入框）用小圆角，不做胶囊：一整页都是胶囊
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

/** 非胶囊容器的圆角几档。内外嵌套时按"外圆角 − 间距 = 内圆角"取，边才同心。 */
object GlassRadius {
    /**
     * 设置页的分组卡片。设置页改成简约的分组列表后，用户嫌 12dp 太方，单独加大一档：卡片里是
     * 一行行的入口、没有贴边的控件，圆角大一点不会和里面的东西打架；折射带宽也跟着圆角变宽
     * （封顶 16dp），液态玻璃的边看得更清楚。
     */
    val XLarge = 22.dp

    /** tab 栏。 */
    val Large = 16.dp

    /** 下拉菜单、对话页之外的其他卡片。 */
    val Medium = 12.dp

    /** 卡片里的输入框、按钮、chip、图标块。比卡片小一档，嵌在卡片里才不显得笨重。 */
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
