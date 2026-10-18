package me.wjz.kitten.ui.component

import android.graphics.RuntimeShader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.flow.first
import me.wjz.kitten.ui.theme.GlowColors
import me.wjz.kitten.ui.theme.KittenColors

/**
 * 光雾：按下时玻璃内部亮起的一团会流动的柔光。
 *
 * 这是 Kitten 的按压反馈。和 catalog 里 `InteractiveHighlight` 那种"手指底下一个静止的
 * 圆形光斑"不同，光雾是**活的**：用分形噪声（fbm）做出一团团浓淡不均的雾，再用另一层
 * 噪声去扭曲它的坐标（domain warp），让雾像烟一样缓缓卷动；离手指越近越亮，正下方还
 * 有一点颜色更浓的芯。
 *
 * 雾是半透明的，正常叠在玻璃上（SrcOver）。早先在深色背景上用的是加法混合（Plus），
 * 只会把底下照亮；换成浅色背景后加法混合会让白玻璃上的雾直接消失——白色再加亮还是
 * 白色。所以现在按底色选雾的颜色：白玻璃上是天依蓝的雾，天依蓝填色上是白雾，见
 * [KittenColors.glowOnGlass] / [KittenColors.glowOnAccent]。
 *
 * 不需要降级分支：AGSL（`RuntimeShader`）是 Android 13 加的，本项目 minSdk 就是 33。
 *
 * ## 性能
 *
 * 每个像素算 12 次值噪声，按钮大小的区域在任何能跑 Android 13 的机器上都不是负担。
 * 时间只在发光时推进（见 [rememberGlassGlow]），没按的时候不占帧。
 */
@Stable
class GlassGlow internal constructor() {

    /** 雾的流动时间（秒）。只在发光时累加，熄灭后冻结，下次按下接着流。 */
    var time by mutableFloatStateOf(0f)
        internal set

    // 第一次真正要画的时候才编译着色器：设置页上十几个控件各带一份光雾，大部分可能
    // 从来不会被按，没必要在页面组合时一口气编译十几个着色器。
    internal val shader: RuntimeShader by lazy { RuntimeShader(MistShader) }
}

/**
 * 创建一份光雾，并在 [intensity] 大于 0 时推进它的时间。
 *
 * @param intensity 当前亮度 0..1，通常就是控件的按压进度。
 */
@Composable
fun rememberGlassGlow(intensity: () -> Float): GlassGlow {
    val glow = remember { GlassGlow() }
    val currentIntensity by rememberUpdatedState(intensity)

    LaunchedEffect(glow) {
        while (true) {
            // 挂起到"亮起来"为止。first {} 会先立刻求一次值，不会漏掉已经在亮的情况。
            snapshotFlow { currentIntensity() > VisibleThreshold }.first { it }
            var lastFrameNanos = 0L
            while (currentIntensity() > VisibleThreshold) {
                val frameNanos = withFrameNanos { it }
                if (lastFrameNanos != 0L) {
                    // 夹住单帧步长：切后台再回来时两帧间隔可能有好几秒，雾会突然跳一大截。
                    val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                    glow.time += dt
                }
                lastFrameNanos = frameNanos
            }
        }
    }
    return glow
}

/**
 * 在当前区域画一层光雾。
 *
 * 不负责裁剪：调用方要画在已经裁成控件形状的地方（drawBackdrop 的各个回调、或者
 * `clip` 之后的 drawBehind）。
 *
 * @param intensity 亮度 0..1。
 * @param center 最亮处，一般传手指位置；不指定就在正中。
 * @param colors 雾的颜色：外围是 tint，手指正下方渐变到 core。
 */
fun DrawScope.drawGlassGlow(
    glow: GlassGlow,
    intensity: Float,
    center: Offset = Offset.Unspecified,
    colors: GlowColors,
) {
    if (intensity <= VisibleThreshold || size.width <= 0f || size.height <= 0f) return
    val focus = if (center.isSpecified) center else size.center
    glow.shader.apply {
        setFloatUniform("size", size.width, size.height)
        setFloatUniform("touch", focus.x, focus.y)
        setFloatUniform("time", glow.time)
        setFloatUniform("intensity", intensity.coerceIn(0f, 1f))
        setColorUniform("tint", colors.tint.toArgb())
        setColorUniform("coreTint", colors.core.toArgb())
    }
    // 同一个 RuntimeShader 改了 uniform 之后，Paint 在下一次绘制时会自己同步，不用重建。
    drawRect(brush = ShaderBrush(glow.shader))
}

private const val VisibleThreshold = 0.001f

/*
 * 坐标统一除以高度归一化：同一团雾放在 48dp 的按钮和 56dp 的 tab 滑块上，颗粒大小
 * 看起来差不多；宽的按钮横向会多出几团雾，而不是把一团雾拉长。
 *
 * 着色器里的几步：
 *
 * - warp：第一层噪声当"风场"，扭曲第二层的采样坐标——雾就不是在平移，而是在卷动；
 * - mist：smoothstep 只留中高段，雾才有一团一团的边界，而不是一层均匀的灰；
 * - halo / core：大光晕决定"雾主要聚在手指附近"，小光芯是正下方最亮的那一点。
 *
 * 输出是预乘 alpha 的颜色，按默认的 SrcOver 叠到玻璃上。
 *
 * 着色器源码里只写 ASCII：AGSL 是运行时才编译的，编译失败就是崩溃，不赌它的词法
 * 分析器认不认注释里的中文。
 */
private const val MistShader = NoiseSkSL + """
uniform float2 size;
uniform float2 touch;
uniform float time;
uniform float intensity;
layout(color) uniform half4 tint;
layout(color) uniform half4 coreTint;

half4 main(float2 coord) {
    float2 uv = coord / size.y;
    float2 t = touch / size.y;

    float2 warp = float2(
        fbm(uv * 1.5 + float2(0.0, time * 0.25)),
        fbm(uv * 1.5 + float2(5.2, -time * 0.21))
    );
    float n = fbm(uv * 2.0 + warp * 1.7 + float2(time * 0.35, -time * 0.15));
    float mist = smoothstep(0.30, 0.80, n);

    float2 offset = uv - t;
    float d2 = dot(offset, offset);
    float halo = exp(-d2 * 2.5);
    float core = exp(-d2 * 12.0);

    float glowAlpha = (mist * (0.25 + 0.75 * halo) * 0.55 + core * 0.30) * intensity;
    float3 glowColor = mix(float3(tint.rgb), float3(coreTint.rgb), clamp(core * 0.9 + mist * 0.25, 0.0, 1.0));
    return half4(half3(glowColor * glowAlpha), half(glowAlpha));
}
"""
