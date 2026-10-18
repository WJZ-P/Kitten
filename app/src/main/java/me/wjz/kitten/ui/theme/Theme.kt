package me.wjz.kitten.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Material 组件（弹窗、Snackbar、调试卡片）的配色，和 [LightKittenColors] 取自同一张色板。
 *
 * primary 用 [Tianyi700] 而不是天依蓝本色：Material 按钮是白字压在 primary 上，本色太浅，
 * 对比度不够。
 */
private val LightMaterialColors = lightColorScheme(
    primary = Tianyi700,
    onPrimary = Color.White,
    primaryContainer = Tianyi150,
    onPrimaryContainer = Tianyi950,
    secondary = Slate600,
    onSecondary = Color.White,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate950,
    tertiary = Tianyi400,
    background = Tianyi50,
    onBackground = Ink,
    surface = Tianyi50,
    onSurface = Ink,
    surfaceVariant = Neutral200,
    onSurfaceVariant = Slate700,
    outline = Slate500,
)

/**
 * Kitten 的主题：天依蓝 + 白。
 *
 * 同时提供两套配色——玻璃界面用的 [KittenTheme.colors]，Material 组件用的
 * `MaterialTheme.colorScheme`。两者都从 `Color.kt` 的同一张色板取值，颜色只定义一次。
 *
 * ## 为什么只有浅色
 *
 * 玻璃界面目前只设计了浅色这一套。以前这里也有深色方案和 Android 12 的动态取色，但它们
 * 只作用于 Material 组件，玻璃界面不跟着变——系统一开深色模式，弹窗变黑、页面还是浅色，
 * 反而更乱。所以先拿掉，等真要做深色时：给出一份 `DarkKittenColors` 和对应的 Material
 * 配色，在这里按 `isSystemInDarkTheme()` 一起切换即可，组件不用改。
 */
@Composable
fun KittenTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalKittenColors provides LightKittenColors) {
        MaterialTheme(colorScheme = LightMaterialColors, content = content)
    }
}

/** 读取当前主题的入口，用法和 `MaterialTheme.colorScheme` 一样：`KittenTheme.colors.accent`。 */
object KittenTheme {
    val colors: KittenColors
        @Composable
        @ReadOnlyComposable
        get() = LocalKittenColors.current
}
