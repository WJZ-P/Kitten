package me.wjz.kitten.ui.component

/*
 * 着色器共用的噪声函数（AGSL）。按钮上的光雾和背景的雾都用它，拼在各自的 main 前面。
 *
 * - hash21：二维坐标 → 0..1 的伪随机数；
 * - valueNoise：在整数格点上取随机值、格点之间平滑插值，得到连续的噪声；
 * - fbm：四层不同频率的噪声叠加（分形布朗运动），一层管大块形状、后面几层补细节，
 *   出来的就是云雾那种"大团里套小团"的质感。
 *
 * 只写 ASCII：AGSL 是运行时才编译的，编译失败就是崩溃，不赌它的词法分析器认不认中文注释。
 */
internal const val NoiseSkSL = """
float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + float2(1.0, 0.0));
    float c = hash21(i + float2(0.0, 1.0));
    float d = hash21(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * valueNoise(p);
        p = p * 2.03 + float2(3.1, 1.7);
        amp *= 0.5;
    }
    return v;
}
"""
