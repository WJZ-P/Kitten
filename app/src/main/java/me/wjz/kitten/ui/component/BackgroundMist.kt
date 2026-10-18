package me.wjz.kitten.ui.component

import android.graphics.RuntimeShader
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 背景里缓缓流动的雾：一层天依蓝的薄雾，上面浮着一层白色的亮雾。
 *
 * 和按钮上的光雾（[GlassGlow]）是同一种画法——分形噪声 + 坐标扭曲——只是没有跟手的
 * 光斑，铺满整个背景，自己慢慢卷动。
 *
 * ## 为什么用 1/4 分辨率渲染
 *
 * 这个着色器每个像素要算 16 次噪声。全分辨率铺满 1220×2712 的屏幕，一帧就是五千多万次，
 * 30fps 下低端机会吃不消。但雾本来就是软的，没有任何锐利的边——先按 1/4 的宽高渲染
 * （像素数只有 1/16），再放大回全屏，肉眼看不出区别，计算量却降到一帧三百多万次。
 *
 * 做法是两层 graphicsLayer：
 *
 * ```
 * 外层  scale = 4，原点在左上角     把小图放大到铺满全屏
 * 内层  Offscreen，尺寸是全屏的 1/4  按自己的小尺寸渲染成一张纹理
 * ```
 *
 * 内层必须是离屏合成：不离屏的话，外层的放大只是一个坐标变换，着色器还是会在放大后的
 * 每个像素上逐个执行，等于没省。离屏后渲染发生在小纹理上，外层放大只是拉伸这张纹理。
 *
 * @param time 雾的时间（秒）。在绘制阶段才读，时间推进只让雾这一层重画。
 */
@Composable
internal fun BackgroundMist(time: () -> Float, modifier: Modifier = Modifier) {
    val colors = KittenTheme.colors
    val shader = remember { RuntimeShader(BackgroundMistShader) }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = MistDownscale
                    scaleY = MistDownscale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .size(maxWidth / MistDownscale, maxHeight / MistDownscale)
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawBehind {
                    shader.setFloatUniform("size", size.width, size.height)
                    shader.setFloatUniform("time", time())
                    shader.setColorUniform("haze", colors.backgroundMistHaze.toArgb())
                    shader.setColorUniform("light", colors.backgroundMistLight.toArgb())
                    drawRect(brush = ShaderBrush(shader))
                },
        )
    }
}

/** 渲染分辨率缩小的倍数（每个方向）。雾是软的，4 倍放大看不出颗粒。 */
private const val MistDownscale = 4f

/*
 * 两层雾各用一份 fbm，再共用一份"风场"去扭曲它们的采样坐标：雾就不是在平移，而是在卷动。
 * 两层的扭曲方向相反，白雾和蓝雾会互相穿过，而不是叠成一团一起走。
 *
 * smoothstep 只留中高段，雾才有一团一团的边界；白雾的门槛更高，只在浓处冒出来，像光。
 *
 * 输出是预乘 alpha 的颜色：白雾按 SrcOver 叠在蓝雾上面。
 */
private const val BackgroundMistShader = NoiseSkSL + """
uniform float2 size;
uniform float time;
layout(color) uniform half4 haze;
layout(color) uniform half4 light;

half4 main(float2 coord) {
    float2 uv = coord / size.y * 2.4;
    float t = time * 0.05;
    float2 warp = float2(
        fbm(uv * 0.9 + float2(0.0, t)),
        fbm(uv * 0.9 + float2(5.2, -t * 0.8))
    );
    float n = fbm(uv + warp * 1.8 + float2(t * 1.2, -t * 0.6));
    float m = fbm(uv * 1.6 - warp * 1.3 + float2(7.3 - t * 0.9, 1.9 + t * 0.5));
    float hazeAlpha = smoothstep(0.40, 0.85, n) * 0.6;
    float lightAlpha = smoothstep(0.50, 0.90, m) * 0.75;
    float3 color = float3(light.rgb) * lightAlpha + float3(haze.rgb) * hazeAlpha * (1.0 - lightAlpha);
    float alpha = lightAlpha + hazeAlpha * (1.0 - lightAlpha);
    return half4(half3(color), half(alpha));
}
"""
