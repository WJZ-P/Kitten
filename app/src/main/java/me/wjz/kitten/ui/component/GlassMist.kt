package me.wjz.kitten.ui.component

import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.random.Random

/**
 * 卡片里流动的光雾的时钟（秒）。所有卡片共用一个。
 *
 * 只在有卡片在看的时候走：卡片显示出来时登记（[subscribers] + 1），不显示了注销。没人看时
 * 时钟挂起，不会每一帧都把主线程叫醒。App 不在前台、系统关掉了动画、省电模式、低内存设备，
 * 都停在当前时间，雾就是一张静态图（见 [shouldAnimate]）。
 */
@Stable
class GlassMistClock internal constructor() {
    var time by mutableFloatStateOf(0f)
        internal set

    internal var subscribers by mutableIntStateOf(0)
}

/**
 * 当前页面的光雾时钟。为 null 表示这里的卡片不该动——比如预加载着、但不在屏幕上的页面。
 * 由 KittenApp 按页提供：只有当前页拿到时钟。
 */
val LocalGlassMistClock = staticCompositionLocalOf<GlassMistClock?> { null }

@Composable
fun rememberGlassMistClock(): GlassMistClock {
    val clock = remember { GlassMistClock() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(clock, lifecycleOwner, context) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!shouldAnimate(context)) return@repeatOnLifecycle
            val interval = 1f / MistFps
            while (true) {
                // 没有卡片在看就挂起，一帧都不占。
                snapshotFlow { clock.subscribers > 0 }.first { it }
                var lastFrameNanos = 0L
                var pending = 0f
                while (clock.subscribers > 0) {
                    val frameNanos = withFrameNanos { it }
                    if (lastFrameNanos != 0L) {
                        // 夹住单帧步长：卡顿或者刚切回来时，雾不该突然跳一大截。
                        pending += ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
                        // 留一点余量：120Hz 下 4 帧加起来按浮点算会略小于 1/30 秒。
                        if (pending >= interval - FrameSlack) {
                            clock.time += pending
                            pending = 0f
                        }
                    }
                    lastFrameNanos = frameNanos
                }
            }
        }
    }
    return clock
}

/**
 * 卡片里的一层光雾：一层天依蓝的薄雾，上面浮着白色的亮雾，各自缓缓卷动、互相穿过。
 *
 * 和背景的雾（[BackgroundMist]）是同一种画法——分形噪声 + 坐标扭曲——只是颗粒更小、更浓，
 * 而且叠在卡片的玻璃上面，是卡片自己的雾：卡片滚动时雾跟着卡片走。每张卡片取一个随机的
 * 起点，几张卡片的雾不会长得一模一样。
 *
 * ## 冲开
 *
 * 传了 [push] 时，按下卡片会把雾冲开（见 [GlassMistPush]）：
 *
 * - 按下的那一刻，从触摸点荡出一道往外推的波前，雾被推着往外走、堆在波前上，波前扫过的地方
 *   被吹空；
 * - 手指按住时，指尖周围一直撑着一小片空地（[hold]）；
 * - 松手、波前散尽之后，雾慢慢流回来。
 *
 * 没有做流体模拟（那要多张纹理来回迭代，很贵）：只是在着色器里按离触摸点的距离，把每个像素
 * 的采样坐标往外推、把浓度压下去。每像素多二十来次算术，雾本来就是 1/4 分辨率，可以忽略。
 *
 * ## 性能
 *
 * - 单独一个图层：雾每推进一次只重画这一层，卡片的模糊和折射不会跟着重算。
 * - 按 1/4 的宽高渲染再放大回卡片大小（做法同 [BackgroundMist]）：雾是软的，看不出颗粒，
 *   像素却只有 1/16。
 * - 时间来自共用的 [GlassMistClock]，按 [MistFps] 推进，只在有卡片显示时走。拿不到时钟（不在当前页）
 *   就停在静止的一帧，也不登记。冲开的那一下例外：它跟着屏幕刷新率画，不然会一顿一顿的。
 *
 * @param strength 浓度倍数。1 是平时，按下卡片时调用方给大一点，雾会更浓、更亮。在绘制阶段读。
 * @param push 冲开雾的力。为 null 就不响应触摸。
 * @param hold 手指按住的程度 0..1，决定指尖周围那片空地有多大。在绘制阶段读。
 */
@Composable
internal fun GlassMist(
    shape: Shape,
    modifier: Modifier = Modifier,
    strength: () -> Float = { 1f },
    push: GlassMistPush? = null,
    hold: () -> Float = { 0f },
) {
    val clock = LocalGlassMistClock.current
    if (clock != null) {
        DisposableEffect(clock) {
            clock.subscribers++
            onDispose { clock.subscribers-- }
        }
    }
    val colors = KittenTheme.colors
    val shader = remember { RuntimeShader(CardMistShader) }
    val seed = remember { Random.nextFloat() * 100f }

    BoxWithConstraints(modifier = modifier.clip(shape)) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = MistDownscale
                    scaleY = MistDownscale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
                .size(maxWidth / MistDownscale, maxHeight / MistDownscale)
                // 必须离屏：不离屏的话外层的放大只是坐标变换，着色器还是会在放大后的每个像素上跑。
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawBehind {
                    shader.setFloatUniform("unit", MistGrain.toPx() / MistDownscale)
                    shader.setFloatUniform("seed", seed)
                    shader.setFloatUniform("time", (clock?.time ?: 0f) * MistSpeed)
                    shader.setFloatUniform("strength", strength())
                    shader.setFloatUniform("hazeAlpha", MistHazeAlpha)
                    shader.setFloatUniform("lightAlpha", MistLightAlpha)
                    shader.setColorUniform("haze", colors.cardMistHaze.toArgb())
                    shader.setColorUniform("light", colors.cardMistLight.toArgb())

                    // 触摸点是卡片坐标，雾是按 1/4 渲染的，要换算过来。还没按过就放到很远的地方，
                    // 公式里的力自然衰减到零。
                    val origin = push?.origin?.takeIf { it.isSpecified }
                    if (origin != null) {
                        shader.setFloatUniform("touch", origin.x / MistDownscale, origin.y / MistDownscale)
                    } else {
                        shader.setFloatUniform("touch", -1e4f, -1e4f)
                    }
                    shader.setFloatUniform("burst", push?.burst?.value ?: 1f)
                    shader.setFloatUniform("hold", if (origin != null) hold().coerceIn(0f, 1f) else 0f)
                    shader.setFloatUniform("burstReach", BurstReach)
                    shader.setFloatUniform("burstWidth", BurstWidth)
                    shader.setFloatUniform("burstPush", BurstPush)
                    shader.setFloatUniform("burstPile", BurstPile)
                    shader.setFloatUniform("holdRadius", HoldRadius)
                    shader.setFloatUniform("holdPush", HoldPush)
                    shader.setFloatUniform("clearAmount", ClearAmount)
                    drawRect(brush = ShaderBrush(shader))
                },
        )
    }
}

/**
 * 冲开光雾的力：记住最近一次按下的位置，从那里放一道往外推的波前。
 *
 * 波前的进度 [burst] 从 0 走到 1：0 是刚按下、力最大，1 是波前散尽。没按过时停在 1，
 * 着色器里的力就是零。
 */
@Stable
internal class GlassMistPush(private val scope: CoroutineScope) {

    /** 最近一次按下的位置（卡片坐标）。 */
    var origin by mutableStateOf(Offset.Unspecified)
        private set

    internal val burst = Animatable(1f)

    fun push(at: Offset) {
        origin = at
        scope.launch {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(BurstDurationMillis, easing = BurstEasing))
        }
    }
}

@Composable
internal fun rememberGlassMistPush(): GlassMistPush {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassMistPush(scope) }
}

/** 一团雾大概多大。按 dp 给而不是按卡片高度归一化：高卡片和矮卡片里的雾颗粒一样大。 */
private val MistGrain = 140.dp

/** 雾流动的快慢。1 大约是背景雾的两倍：卡片小，流慢了看不出在动。 */
private const val MistSpeed = 2f

/** 天依蓝薄雾最浓处的不透明度。 */
private const val MistHazeAlpha = 0.45f

/** 白色亮雾最浓处的不透明度。 */
private const val MistLightAlpha = 0.6f

/** 渲染分辨率缩小的倍数（每个方向）。 */
private const val MistDownscale = 4f

/**
 * 雾每秒推进几次。
 *
 * 贵的主要不是雾本身（1/4 分辨率），而是"屏幕上有东西在动"：只要动一帧，整个窗口就得重新
 * 合成一遍，设置页满屏的玻璃、阴影、高光都要重画。实测设置页静置时每帧 GPU 14～16ms，其中
 * 底部栏折射页面只占 1.5ms 左右。所以帧率直接决定耗电：雾流得很慢，帧率尽量给低。
 */
private const val MistFps = 15f

/** 留一点余量：120Hz 下几帧加起来按浮点算会略小于整数倍的间隔。 */
private const val FrameSlack = 0.002f

/** 冲开的波前从按下到散尽要多久。 */
private const val BurstDurationMillis = 2000

/** 先快后慢：一下冲出去，越往外越缓。 */
private val BurstEasing = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f)

// 下面这几个距离都以"雾团"为单位，一团约 [MistGrain]。

/** 波前最远走多远。 */
private const val BurstReach = 1.2f

/** 波前的宽度。 */
private const val BurstWidth = 0.35f

/** 波前把雾往外推多远。 */
private const val BurstPush = 0.2f

/** 雾堆在波前上变浓多少（相对平时的比例）。 */
private const val BurstPile = 0.6f

/** 手指按住时，指尖周围那片空地的半径。 */
private const val HoldRadius = 0.5f

/** 按住时指尖把雾往外推多远。 */
private const val HoldPush = 0.35f

/** 被冲开的地方雾最多淡掉多少。1 是完全吹空。 */
private const val ClearAmount = 0.25f

/*
 * 两层雾各用一份 fbm，共用一份"风场"扭曲采样坐标：雾在卷动而不是平移；两层的扭曲方向相反，
 * 白雾和蓝雾互相穿过。smoothstep 只留中高段，雾才一团一团的；白雾的门槛更高，只在浓处冒出来。
 * strength 同时放大两层的浓度，超过 1 时用 clamp 收住。
 *
 * 冲开（坐标都以雾团为单位）：
 *
 * - ring：以触摸点为圆心、半径随 burst 变大的一圈，是正在往外走的波前；inside 是波前已经扫过
 *   的圆盘。两者都乘 (1 - burst)，波前越走越弱。
 * - near：指尖周围的一团，乘 hold，手指按住时一直在。
 * - 把采样坐标往触摸点方向拉（uv0 - dir * push）：每个像素显示的是离触摸点更近处的雾，
 *   看上去雾就被往外推了。
 *   拉的距离不超过离触摸点距离的八成：超过的话采样点会越过圆心，指尖周围的雾会被折成一圈圈
 *   同心环，像漩涡（实测过）。限住之后指尖附近只是被放大、往外摊开。
 * - 波前上的雾变浓（pile），扫过的地方和指尖周围变淡（clear）。
 *
 * 只写 ASCII：AGSL 是运行时才编译的，编译失败就是崩溃。
 */
private const val CardMistShader = NoiseSkSL + """
uniform float unit;
uniform float seed;
uniform float time;
uniform float strength;
uniform float hazeAlpha;
uniform float lightAlpha;
layout(color) uniform half4 haze;
layout(color) uniform half4 light;

uniform float2 touch;
uniform float burst;
uniform float hold;
uniform float burstReach;
uniform float burstWidth;
uniform float burstPush;
uniform float burstPile;
uniform float holdRadius;
uniform float holdPush;
uniform float clearAmount;

half4 main(float2 coord) {
    float2 uv0 = coord / unit;
    float2 rel = uv0 - touch / unit;
    float dist = length(rel);
    float2 dir = dist > 0.0001 ? rel / dist : float2(0.0, 0.0);

    float alive = 1.0 - burst;
    float front = burst * burstReach;
    float k = (dist - front) / burstWidth;
    float ring = exp(-k * k) * alive;
    float inside = (1.0 - smoothstep(front - burstWidth, front, dist)) * alive;
    float near = exp(-(dist * dist) / (holdRadius * holdRadius)) * hold;

    float pushAmount = min(ring * burstPush + near * holdPush, dist * 0.8);
    float2 uv = uv0 - dir * pushAmount + float2(seed, seed * 0.37);

    float t = time * 0.1;
    float2 warp = float2(
        fbm(uv * 0.9 + float2(0.0, t)),
        fbm(uv * 0.9 + float2(5.2, -t * 0.8))
    );
    float n = fbm(uv + warp * 1.8 + float2(t * 1.2, -t * 0.6));
    float m = fbm(uv * 1.5 - warp * 1.4 + float2(7.3 - t * 0.9, 1.9 + t * 0.5));

    float density = strength * (1.0 + ring * burstPile) * (1.0 - clamp((inside + near) * clearAmount, 0.0, 0.95));
    float hazeA = clamp(smoothstep(0.35, 0.80, n) * hazeAlpha * density, 0.0, 1.0);
    float lightA = clamp(smoothstep(0.45, 0.85, m) * lightAlpha * density, 0.0, 1.0);
    float3 color = float3(light.rgb) * lightA + float3(haze.rgb) * hazeA * (1.0 - lightA);
    float alpha = lightA + hazeA * (1.0 - lightA);
    return half4(half3(color), half(alpha));
}
"""
