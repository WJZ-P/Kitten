package me.wjz.kitten.ui.component

import android.content.Context
import android.graphics.RuntimeShader
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.theme.LocalKittenColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * 在组件里铺一层缓缓流动的光雾：一层天依蓝的薄雾，上面浮着白色的亮雾，各自卷动、互相穿过。
 *
 * 和液态玻璃（`drawBackdrop`）一样是个 modifier，哪个组件要雾就加一行，不用管时钟、不用管
 * 它在不在屏幕上：
 *
 * ```kotlin
 * Modifier
 *     .drawBackdrop(...)                 // 玻璃
 *     .glassMist(shape, state = mist)    // 雾
 * ```
 *
 * 雾画在组件内容的下面；和 drawBackdrop 一起用时画在玻璃的上面。
 *
 * ## 必须写在 drawBackdrop 后面
 *
 * Compose 里同一个节点上任何一个绘制 modifier 失效，这个节点的绘制就要整个重录。雾每推进
 * 一次都要重画，要是和 drawBackdrop 在同一层，玻璃就得跟着重新模糊、折射——每帧白白多出
 * 好几毫秒。所以这个 modifier 自己先开一层 graphicsLayer 当边界：写在它前面的 drawBackdrop
 * 在外层，不受影响；雾和组件内容在里层，雾推进时只重录里层。
 *
 * 写在 drawBackdrop 前面的话，玻璃会被包进雾的这一层，边界就白开了。
 *
 * ## 时钟
 *
 * 所有雾读同一个时间（[GlassMistTicker]），一起推进，几块卡片的雾是同步的。它只在**有雾在屏幕
 * 上**的时候走：预加载着、但不在屏幕上的页面，滚出屏幕的卡片，都不算；App 切到后台时窗口不再绘制，
 * 时钟都会自己停下。用到雾的地方不用关心这些。
 *
 * ## 冲开
 *
 * 传了 [state] 时，调用方在手指落下时调 [GlassMistState.push]，雾会从那里被冲开：
 *
 * - 按下的那一刻，从触摸点荡出一道往外推的波前，雾被推着往外走、堆在波前上，波前扫过的地方
 *   被吹空；
 * - 手指按住时，指尖周围一直撑着一小片空地（[hold]）；
 * - 松手、波前散尽之后，雾慢慢流回来。
 *
 * 没有做流体模拟（那要多张纹理来回迭代，很贵）：只是在着色器里按离触摸点的距离，把每个像素
 * 的采样坐标往外推、把浓度压下去。每像素多二十来次算术，可以忽略。冲开的那一下跟着屏幕
 * 刷新率画，不然会一顿一顿的。
 *
 * ## 性能
 *
 * - 按 1/[MistDownscale] 的宽高渲染进一张离屏纹理，再放大回组件大小（做法同 [BackgroundMist]）：
 *   雾是软的，看不出颗粒，像素却只有 1/16。
 * - 雾按 [MistFps] 推进。贵的主要不是雾本身，而是"屏幕上有东西在动"，见 [MistFps]。
 *
 * @param shape 雾的形状，一般和玻璃的形状相同。雾会被裁在里面。
 * @param state 冲开雾的力。为 null 就不响应触摸。
 * @param strength 浓度倍数。1 是平时，按下组件时调用方给大一点，雾会更浓、更亮。在绘制阶段读。
 * @param hold 手指按住的程度 0..1，决定指尖周围那片空地有多大。在绘制阶段读。快速点一下时
 *   空地至少撑开一小段时间，不完全跟着它，见 [GlassMistState]。
 * @param style 雾的颗粒、流速、浓度。不传就是卡片用的那套。
 * @param seed 雾的起点。不传就随机取一个，几块卡片的雾不会长得一模一样；两块雾要严丝合缝地
 *   接上时（比如底部栏和它的高亮层）传同一个值。
 */
fun Modifier.glassMist(
    shape: Shape,
    state: GlassMistState? = null,
    strength: () -> Float = { 1f },
    hold: () -> Float = { 0f },
    style: GlassMistStyle = GlassMistStyle.Default,
    seed: Float? = null,
): Modifier = this
    .graphicsLayer()
    .then(GlassMistElement(shape, state, strength, hold, style, seed))

/**
 * 雾的样子：颗粒、流速、两层的浓度。默认值是卡片用的那套。
 *
 * @param grain 一团雾大概多大。按 dp 给而不是按组件高度归一化：高卡片和矮卡片里的雾颗粒一样大。
 *   冲开时的那些距离（[BurstReach] 等）都以它为单位。
 * @param speed 雾流动的快慢。1 大约是背景雾的两倍：卡片小，流慢了看不出在动。
 * @param hazeAlpha 天依蓝薄雾最浓处的不透明度。
 * @param lightAlpha 白色亮雾最浓处的不透明度。
 */
@Immutable
data class GlassMistStyle(
    val grain: Dp = 140.dp,
    val speed: Float = 2f,
    val hazeAlpha: Float = 0.45f,
    val lightAlpha: Float = 0.6f,
) {
    companion object {
        val Default = GlassMistStyle()
    }
}

/**
 * 冲开光雾的力：记住最近一次按下的位置，从那里放一道往外推的波前。
 *
 * 波前的进度从 0 走到 1：0 是刚按下、力最大，1 是波前散尽。没按过时停在 1，着色器里的力
 * 就是零。
 *
 * 指尖那片空地平时跟着调用方给的 hold 走（手指按住多久就撑多久）。但快速点一下时，按压态
 * 刚涨起来就落回去了，空地一闪就没，看着像没按到。所以每次按下，空地至少撑开
 * [minHoldMillis] 才开始合拢（[holdFloor]），按得更久时照旧跟着 hold。
 */
@Stable
class GlassMistState internal constructor(
    private val scope: CoroutineScope,
    private val minHoldMillis: Int,
) {

    /** 最近一次按下的位置（组件坐标）。 */
    var origin by mutableStateOf(Offset.Unspecified)
        private set

    internal val burst = Animatable(1f)

    /** 空地的保底撑开程度 0..1：按下时撑满，过了 [minHoldMillis] 再合拢。着色器取它和 hold 中大的那个。 */
    internal val holdFloor = Animatable(0f)

    private var holdJob: Job? = null

    /** 从 [at]（组件坐标）把雾冲开。在手指落下时调。 */
    fun push(at: Offset) {
        origin = at
        scope.launch {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(BurstDurationMillis, easing = BurstEasing))
        }
        holdJob?.cancel()
        holdJob = scope.launch {
            launch { holdFloor.animateTo(1f, HoldOpenSpec) }
            delay(minHoldMillis.toLong())
            // 撑开的动画要是还没放完，这里会把它打断，从当前的大小开始合拢。
            holdFloor.animateTo(0f, HoldCloseSpec)
        }
    }
}

/**
 * @param minHoldMillis 每次按下，指尖那片空地至少撑开多久才开始合拢，快速点一下也看得到雾被
 *   冲开。见 [GlassMistState]。
 */
@Composable
fun rememberGlassMistState(minHoldMillis: Int = DefaultMinHoldMillis): GlassMistState {
    val scope = rememberCoroutineScope()
    return remember(scope, minHoldMillis) { GlassMistState(scope, minHoldMillis) }
}

/** 指尖那片空地默认至少撑开多久（毫秒），见 [rememberGlassMistState]。 */
const val DefaultMinHoldMillis = 200

/**
 * 所有光雾共用的时钟（秒）。进程里只有这一个。
 *
 * 只在**有雾在屏幕上**时推进：屏幕上的雾在绘制时调 [demand] 说"我还要下一帧"。时钟每到一拍
 * 先看上一拍之后有没有这样的雾，没有就停下——不在屏幕上的页面、滚出去的卡片、后台的 App，
 * 都不会再要下一帧，时钟自然就停了，不用谁去管。停下后时间冻结，下次有雾回到屏幕上时接着走，
 * 不会跳一截。雾自己怎么判断在不在屏幕上，见 GlassMistNode.onScreen。
 *
 * 系统关掉了动画、省电模式、低内存设备上不推进（见 [shouldAnimate]），雾停在当前这一帧。
 *
 * 所有读写都在主线程：[demand] 在绘制时调，推进循环跑在主线程的 [AndroidUiDispatcher] 上。
 */
internal object GlassMistTicker {

    var time by mutableFloatStateOf(0f)
        private set

    /** 上一拍之后有没有屏幕上的雾要过下一帧。 */
    private var demanded = false
    private var running = false

    /** 系统允不允许做持续动画。查一次是一次跨进程调用，缓存一段时间再重查。 */
    private var allowed = true
    private var allowedCheckedAt = Long.MIN_VALUE

    private val scope = CoroutineScope(SupervisorJob() + AndroidUiDispatcher.Main)

    fun demand(context: Context) {
        demanded = true
        if (running) return
        val now = SystemClock.uptimeMillis()
        if (allowedCheckedAt == Long.MIN_VALUE || now - allowedCheckedAt >= RecheckMillis) {
            allowed = shouldAnimate(context)
            allowedCheckedAt = now
        }
        if (!allowed) return
        running = true
        val appContext = context.applicationContext
        scope.launch {
            try {
                run(appContext)
            } finally {
                running = false
            }
        }
    }

    private suspend fun run(context: Context) {
        val interval = 1f / MistFps
        var lastFrameNanos = 0L
        var pending = 0f
        var sinceCheck = 0f
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastFrameNanos != 0L) {
                // 夹住单帧步长：卡顿后补帧时雾不该突然跳一大截。
                val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
                pending += dt
                sinceCheck += dt
                if (pending >= interval - FrameSlack) {
                    // 上一拍推进之后屏幕上没有雾要下一帧：没人在看，停下。
                    if (!demanded) return
                    demanded = false
                    time += pending
                    pending = 0f
                }
                // 省电模式可能在前台时被打开，隔几秒查一次。
                if (sinceCheck * 1000f >= RecheckMillis) {
                    sinceCheck = 0f
                    if (!shouldAnimate(context)) {
                        allowed = false
                        allowedCheckedAt = SystemClock.uptimeMillis()
                        return
                    }
                }
            }
            lastFrameNanos = frameNanos
        }
    }
}

private data class GlassMistElement(
    val shape: Shape,
    val state: GlassMistState?,
    val strength: () -> Float,
    val hold: () -> Float,
    val style: GlassMistStyle,
    val seed: Float?,
) : ModifierNodeElement<GlassMistNode>() {

    override fun create() = GlassMistNode(shape, state, strength, hold, style, seed)

    override fun update(node: GlassMistNode) {
        if (node.shape != shape || node.style != style || node.seed != seed) {
            node.shape = shape
            node.style = style
            node.seed = seed
            node.invalidateDraw()
        }
        node.state = state
        node.strength = strength
        node.hold = hold
    }
}

private class GlassMistNode(
    var shape: Shape,
    var state: GlassMistState?,
    var strength: () -> Float,
    var hold: () -> Float,
    var style: GlassMistStyle,
    var seed: Float?,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {

    // 第一次真正要画的时候才编译着色器：设置页上好几块卡片，没必要在组合时一口气编译。
    private val shader by lazy { RuntimeShader(MistShader) }

    /** 没指定 [seed] 时用的随机起点，几块卡片的雾不会长得一模一样。 */
    private val randomSeed = Random.nextFloat() * 100f

    /** 按 1/[MistDownscale] 渲染雾的离屏纹理。 */
    private var layer: GraphicsLayer? = null

    /**
     * 这块雾现在在不在屏幕上。只有在屏幕上的雾才推着时钟走。
     *
     * 不能用"draw 有没有被调"来判断：Compose 每帧会把所有失效的图层都重录一遍
     * （AndroidComposeView.dispatchDraw 里的 dirtyLayers），不管它的父级这一帧画不画它——预加载着、
     * 被 pager 跳过绘制的页面里，雾的 draw 照样会被调。所以按位置判断：boundsInWindow 会被
     * 沿途每一层父级裁剪，pager 外面的页面、滚出屏幕的卡片都会被裁成空。
     */
    private var onScreen = false

    private var clip: Path? = null
    private var clipSize = Size.Unspecified
    private var clipDirection: LayoutDirection? = null
    private var clipShape: Shape? = null

    override fun onAttach() {
        layer = requireGraphicsContext().createGraphicsLayer().apply {
            // 必须离屏：不离屏的话 drawLayer 时的放大只是坐标变换，着色器还是会在放大后的
            // 每个像素上跑，等于没省。离屏后渲染发生在小纹理上，放大只是拉伸这张纹理。
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        layer = null
    }

    override fun ContentDrawScope.draw() {
        val layer = layer
        if (layer != null && size.width >= 1f && size.height >= 1f) {
            if (onScreen) GlassMistTicker.demand(currentValueOf(LocalContext))

            val colors = currentValueOf(LocalKittenColors)
            val origin = state?.origin?.takeIf { it.isSpecified }
            shader.setFloatUniform("unit", style.grain.toPx() / MistDownscale)
            shader.setFloatUniform("seed", seed ?: randomSeed)
            shader.setFloatUniform("time", GlassMistTicker.time * style.speed)
            shader.setFloatUniform("strength", strength())
            shader.setFloatUniform("hazeAlpha", style.hazeAlpha)
            shader.setFloatUniform("lightAlpha", style.lightAlpha)
            shader.setColorUniform("haze", colors.cardMistHaze.toArgb())
            shader.setColorUniform("light", colors.cardMistLight.toArgb())
            // 触摸点是组件坐标，雾是缩小渲染的，要换算过来。还没按过就放到很远的地方，
            // 公式里的力自然衰减到零。
            if (origin != null) {
                shader.setFloatUniform("touch", origin.x / MistDownscale, origin.y / MistDownscale)
            } else {
                shader.setFloatUniform("touch", -1e4f, -1e4f)
            }
            shader.setFloatUniform("burst", state?.burst?.value ?: 1f)
            val holdAmount = max(hold(), state?.holdFloor?.value ?: 0f)
            shader.setFloatUniform("hold", if (origin != null) holdAmount.coerceIn(0f, 1f) else 0f)
            shader.setFloatUniform("burstReach", BurstReach)
            shader.setFloatUniform("burstWidth", BurstWidth)
            shader.setFloatUniform("burstPush", BurstPush)
            shader.setFloatUniform("burstPile", BurstPile)
            shader.setFloatUniform("holdRadius", HoldRadius)
            shader.setFloatUniform("holdPush", HoldPush)
            shader.setFloatUniform("clearAmount", ClearAmount)

            val small = IntSize(
                ceil(size.width / MistDownscale).toInt(),
                ceil(size.height / MistDownscale).toInt(),
            )
            layer.record(small) { drawRect(brush = ShaderBrush(shader)) }
            clipPath(mistClip()) {
                scale(MistDownscale, MistDownscale, pivot = Offset.Zero) { drawLayer(layer) }
            }
        }
        drawContent()
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        onScreen = !coordinates.boundsInWindow().isEmpty
        // 滚进屏幕、切到这一页时，时钟可能正停着，而位置变化不会让雾重画——这里推它一下，
        // 不然雾会停在滚出去时的那一帧。
        if (onScreen) GlassMistTicker.demand(currentValueOf(LocalContext))
    }

    /** 雾裁成组件的形状。尺寸、方向、形状不变时复用上一次的 Path。 */
    private fun ContentDrawScope.mistClip(): Path {
        val cached = clip
        if (cached != null && clipSize == size && clipDirection == layoutDirection && clipShape == shape) {
            return cached
        }
        val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@mistClip)) }
        clip = path
        clipSize = size
        clipDirection = layoutDirection
        clipShape = shape
        return path
    }
}

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

/** 系统动画设置隔多久重查一次（毫秒）。 */
private const val RecheckMillis = 5_000L

/** 冲开的波前从按下到散尽要多久。 */
private const val BurstDurationMillis = 2000

/** 先快后慢：一下冲出去，越往外越缓。 */
private val BurstEasing = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f)

/** 按下时空地撑开的弹簧。 */
private val HoldOpenSpec = spring<Float>(dampingRatio = 0.75f, stiffness = 600f)

/** 保底时间到了之后空地合拢的弹簧：雾流回来。 */
private val HoldCloseSpec = spring<Float>(dampingRatio = 0.75f, stiffness = 400f)

// 下面这几个距离都以"雾团"为单位，一团约 [GlassMistStyle.grain]。

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
private const val MistShader = NoiseSkSL + """
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
