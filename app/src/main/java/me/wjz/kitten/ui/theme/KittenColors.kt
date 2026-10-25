package me.wjz.kitten.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Kitten 的语义配色：每个颜色"用在哪"。
 *
 * 组件只认这里的角色名，不认具体色值。通过 [KittenTheme.colors] 读取，由 [KittenTheme]
 * 注入——和 `MaterialTheme.colorScheme` 是同一个套路。
 *
 * ## 为什么不直接用 MaterialTheme.colorScheme
 *
 * Material 的角色是给 Material 组件设计的（primary / surface / outline……），玻璃界面里
 * 很多颜色没有对应的位置：背景光晕、玻璃表面的透明白、光雾的颜色、空轨道的灰。硬塞
 * 进 Material 的角色里只会让名字和用途对不上。所以玻璃界面用这套，Material 组件（弹窗、
 * Snackbar、调试卡片）用 colorScheme，两者都从 [Color.kt] 的同一张色板取值。
 */
@Immutable
data class KittenColors(
    /** 天依蓝本色：大面积填色（开关打开、主按钮、选中的 chip、用户的消息气泡）。 */
    val accent: Color,
    /** 加深一档的天依蓝：压在白色 / 浅色上的文字和图标。本色太浅看不清。 */
    val accentStrong: Color,
    /** 天依蓝渐变的浅端、深端（分组标题前的竖条、图标块、输入框聚焦线）。 */
    val accentLight: Color,
    val accentDeep: Color,
    /** 压在天依蓝填色上的文字。 */
    val onAccent: Color,

    /** 文字三档：标题 / 说明 / 提示。 */
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,

    /** 背景：上下两端的渐变，加上几团缓缓漂移的光晕和点阵。 */
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val backgroundGlowPrimary: Color,
    val backgroundGlowSecondary: Color,
    val backgroundGlowTertiary: Color,
    /** 白色亮斑：浅色背景上光是蓝色光晕在动不太看得出来，一团游走的白让流动更明显。 */
    val backgroundGlowHighlight: Color,
    /** 背景里流动的雾：一层天依蓝的薄雾，上面再浮一层白色的亮雾。透明度由着色器按浓淡算。 */
    val backgroundMistHaze: Color,
    val backgroundMistLight: Color,
    val backgroundDots: Color,

    /**
     * 内容玻璃（分组卡片、消息气泡、输入栏）的底色。
     *
     * 是一层淡淡的深天依蓝，不是白：背景本身就很浅、而且一直在动，玻璃要是比它还白，
     * 两者就糊成一片，分不清哪里是内容。压深一档，内容才从背景里站出来。
     */
    val glassTint: Color,
    /** 比 [glassTint] 更实一点的玻璃，tab 栏这种要压住下面滚动内容的地方用。 */
    val glassTintStrong: Color,
    /** tab 滑块没按下时的颜色：看得出它停在哪一格。 */
    val sliderIdle: Color,
    /** 分隔线、chip 和输入框的边。 */
    val hairline: Color,
    /**
     * 卡片里的控件（次要按钮、chip）的底色：比卡片亮一档。卡片往深里走、控件往
     * 亮里走，背景 → 卡片 → 控件三层一眼就分得开。
     */
    val controlTint: Color,
    /**
     * 输入框、下拉框这种凹下去的槽的底色：比所在的面深一档。凹下去的地方本来就该暗一点；
     * 浅色的窗上再放白色的槽，两者几乎分不开。
     */
    val wellTint: Color,
    /** 开关、滑条的空轨道。 */
    val trackOff: Color,
    /** 侧滑抽屉的底色（Material 的 Sheet 不透明，没法用玻璃）。 */
    val drawer: Color,
    /** 模态窗背后压暗的那一层，叠在模糊过的界面上。 */
    val scrim: Color,
    /**
     * Toast 的底色和字色。深底白字：Toast 不读背后，压在什么上面都要读得清，浅色界面上用
     * 深色也最醒目。
     */
    val toast: Color,
    val onToast: Color,
    /**
     * 模态窗窗体的底色，[glassTint] 和窗里的光雾叠在它上面。
     *
     * 窗体不透出窗外（见 GlassModal），窗外那层只糊了一点的界面全靠它挡住：透明度给低了，
     * 窗外的字会隐隐透进表单里。
     */
    val modalSurface: Color,

    /** 卡片里流动的光雾：一层深一点的天依蓝薄雾，上面浮着白色的亮雾。透明度由着色器按浓淡算。 */
    val cardMistHaze: Color,
    val cardMistLight: Color,

    /** 状态：正常 / 需要处理 / 危险操作。 */
    val positive: Color,
    val warning: Color,
    val danger: Color,

    /** 光雾：白玻璃上用天依蓝的雾，天依蓝的填色上用白雾。 */
    val glowOnGlass: GlowColors,
    val glowOnAccent: GlowColors,
)

/**
 * 光雾的颜色：外围是 [tint]，手指正下方渐变到 [core]。
 *
 * 浅色玻璃上白雾看不见（白叠白），所以分两套用，见 [KittenColors.glowOnGlass]。
 */
@Immutable
data class GlowColors(val tint: Color, val core: Color)

/** 天依蓝 + 白的浅色配色。目前 Kitten 只有这一套。 */
val LightKittenColors = KittenColors(
    accent = Tianyi400,
    accentStrong = Tianyi600,
    accentLight = Tianyi200,
    accentDeep = Tianyi500,
    onAccent = Tianyi900,

    textPrimary = Ink,
    textSecondary = Ink.copy(alpha = 0.62f),
    textTertiary = Ink.copy(alpha = 0.40f),

    backgroundTop = Tianyi50,
    backgroundBottom = Tianyi100,
    backgroundGlowPrimary = Tianyi400.copy(alpha = 0.5f),
    backgroundGlowSecondary = Tianyi300.copy(alpha = 0.4f),
    backgroundGlowTertiary = Periwinkle200.copy(alpha = 0.35f),
    backgroundGlowHighlight = Color.White.copy(alpha = 0.7f),
    backgroundMistHaze = Tianyi400,
    backgroundMistLight = Color.White,
    backgroundDots = Ink.copy(alpha = 0.07f),

    // 给得很薄：只把背景压深一点，玻璃的质感还是来自透过去看到的、被边缘掰弯的背景。
    glassTint = Tianyi500.copy(alpha = 0.18f),
    glassTintStrong = Neutral50.copy(alpha = 0.4f),
    // catalog 浅色主题这里是 10% 的黑，压在天依蓝背景上像一块脏灰，换成淡天依蓝。
    sliderIdle = Tianyi400.copy(alpha = 0.22f),
    hairline = Ink.copy(alpha = 0.10f),
    controlTint = Color.White.copy(alpha = 0.45f),
    wellTint = Tianyi500.copy(alpha = 0.16f),
    trackOff = SystemGray.copy(alpha = 0.2f),
    drawer = Tianyi50,
    // 浅色主题压得很轻：界面本来就糊开了，再压重就成了一块脏灰。
    scrim = Ink.copy(alpha = 0.10f),
    toast = Ink.copy(alpha = 0.88f),
    onToast = Color.White,
    // 和背景顶部同色：再叠上 glassTint，窗体和页面上的卡片颜色接近。
    modalSurface = Tianyi50.copy(alpha = 0.92f),
    cardMistHaze = Tianyi450,
    cardMistLight = Color.White,

    positive = Green600,
    warning = Orange600,
    danger = Red600,

    glowOnGlass = GlowColors(tint = Tianyi400, core = Tianyi450),
    glowOnAccent = GlowColors(tint = Color.White, core = Color.White),
)

internal val LocalKittenColors = staticCompositionLocalOf { LightKittenColors }
