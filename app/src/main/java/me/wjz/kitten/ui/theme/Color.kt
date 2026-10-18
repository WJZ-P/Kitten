package me.wjz.kitten.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Kitten 的原始色板。整个 App 的颜色值只在这里出现一次。
 *
 * 这一层只回答"有哪些颜色"，不回答"用在哪"——组件不直接引用这里的常量，而是通过
 * [KittenColors] 的语义角色（"正文色""强调色"……）拿颜色。分两层的好处是：换一套配色
 * （比如以后加深色模式）只需要给出另一个 KittenColors，组件一行都不用改。
 *
 * 色阶全部围绕天依蓝 #66CCFF 展开，数字越大越深，和 Material 色阶的习惯一致。
 */

// ---- 天依蓝色阶 ----
internal val Tianyi50 = Color(0xFFF5FBFF)
internal val Tianyi100 = Color(0xFFE3F3FD)
internal val Tianyi150 = Color(0xFFCDEEFF)
internal val Tianyi200 = Color(0xFFA6E4FF)
internal val Tianyi300 = Color(0xFF9EE2FF)

/** 天依蓝本色。 */
internal val Tianyi400 = Color(0xFF66CCFF)
internal val Tianyi450 = Color(0xFF39B3EE)
internal val Tianyi500 = Color(0xFF3AAEEA)

/**
 * 加深一档的天依蓝：白底上的文字、图标用它。
 *
 * 本色 66CCFF 压在白底上对比度只有 2:1 左右，小字几乎看不清；这一档 3.2:1，图标和
 * 粗一点的标签够用，看上去仍然是天依蓝。
 */
internal val Tianyi600 = Color(0xFF1E97D8)

/** 再深一档：白字压在它上面对比度 4.5:1，给 Material 按钮当 primary。 */
internal val Tianyi700 = Color(0xFF0B7DB8)
internal val Tianyi900 = Color(0xFF07344D)
internal val Tianyi950 = Color(0xFF002B40)

/** 偏紫的浅蓝，只在背景光晕里做层次，避免整屏一个颜色发平。 */
internal val Periwinkle200 = Color(0xFFB4C6FF)

// ---- 中性色 ----

/** 正文色：深蓝灰。不用纯黑——纯黑压在天依蓝上太硬。 */
internal val Ink = Color(0xFF0F2B3D)

internal val Neutral50 = Color(0xFFFAFAFA)
internal val Neutral200 = Color(0xFFDCE3E9)

/** 蓝灰色阶：只给 Material 的 secondary / outline 这些次要角色用，玻璃界面里用不到。 */
internal val Slate100 = Color(0xFFD1E5F4)
internal val Slate500 = Color(0xFF71787E)
internal val Slate600 = Color(0xFF4E616D)
internal val Slate700 = Color(0xFF41484D)
internal val Slate950 = Color(0xFF0A1E28)

/** iOS 浅色模式的系统灰，开关和滑条的空轨道用。 */
internal val SystemGray = Color(0xFF787878)

// ---- 状态色 ----
internal val Green600 = Color(0xFF16A06C)
internal val Orange600 = Color(0xFFE0861A)
internal val Red600 = Color(0xFFD9483B)
