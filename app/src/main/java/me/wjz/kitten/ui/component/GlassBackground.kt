package me.wjz.kitten.ui.component

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.PI
import kotlin.math.sin

/**
 * 玻璃背后的背景。
 *
 * ## 背景必须是内容的兄弟，不能是父级
 *
 * 这是用这个库最容易踩的坑，踩过一次直接整个进程崩掉：
 *
 * ```
 * RenderNode::prepareTreeImpl → SkiaDisplayList::prepareListAndChildren → 递归 500+ 层
 * FORTIFY: vsnprintf: size 18446744073709551453 > SSIZE_MAX
 * ```
 *
 * 起因是把 `layerBackdrop()` 加在了包着卡片的那个 Box 上。于是：背景层要录制自己的
 * 全部内容 → 内容里含玻璃卡片 → 卡片又要读取背景层 → RenderNode 树成环 →
 * `prepareTree` 无限递归到栈溢出。
 *
 * 所以这里把两层拆开：被录制的那层**只画背景**，内容层是它的兄弟盖在上面。
 * 推论是——**玻璃卡片永远不会折射到另一块玻璃卡片**，它们都在内容层里，不在录制范围内。
 *
 * ## layerBackdrop 必须写在绘制 modifier 的左边
 *
 * 这是第二个坑，而且不报错、不崩溃，只是效果悄悄没了：
 *
 * `layerBackdrop` 录的是它的 `drawContent()`，也就是**它右边**的 modifier 和子节点画的
 * 东西。早先写成了 `.drawBehind { 光晕 }.layerBackdrop(backdrop)`——光晕在它左边，
 * 画在了录制范围外面，录下来的是一张**透明的空图**。后果：
 *
 * - 所有玻璃都在折射"什么都没有"，模糊、折射、vibrancy 全部白做；肉眼看到的光晕
 *   其实是直接画在屏幕上的，透过透明的玻璃看过去而已；
 * - tab 栏的滑块本应不透明地盖住底下那排白色图标，结果是透明的。按下时高亮层放大
 *   1.2 倍，和底下没放大的白图标错开，白色就漏出来了。
 *
 * ## 为什么要专门画一层背景
 *
 * 液体玻璃的效果来自折射背后的内容：`lens()` 在边缘做透镜畸变，`blur()` 把背后糊开。
 * 背后是一整块纯色的话，折射和模糊之后还是同一块纯色，用户什么都看不到。所以这里铺
 * 几团颜色明确、边界柔和的光晕。
 *
 * 光有光晕还不够：光晕本身就是糊的，透镜把它掰弯了也看不出来。所以再叠一层很淡的
 * **点阵**——点是锐利的，玻璃边缘把它们拉弯、放大时一眼就能看出"这里有块玻璃"。
 * 这也是 Kitten 区别于 iOS 的地方之一：iOS 靠壁纸撑效果，Kitten 的背景自己带刻度。
 *
 * ## 光晕会缓缓漂移，性能怎么控制
 *
 * 四团光晕沿各自的轨迹慢慢游走、半径微微呼吸。周期取 23～43 秒、互不成倍数，叠在一起
 * 几乎不会重复，看起来是"流动"而不是"循环播放"。
 *
 * 贵的不是背景本身——几个径向渐变而已——而是**下游**：背景录进了 backdrop，它一变，
 * 屏幕上每一块玻璃都要重新模糊、重新折射。实测每块玻璃每次要花 RenderThread 两三毫秒，
 * 设置页一屏十来块，加起来二十多毫秒，背景要是每帧都喂给玻璃，30fps 下光 RenderThread
 * 就占掉七成。所以分了几层：
 *
 * ```
 * ① 给玻璃看的那份    alpha(0) 不上屏，只录进 backdrop，6fps 更新
 * ② 屏幕上看到的那份  底色 / 光晕 / 点阵三层：
 *                       底色、点阵不会动，离屏缓存成纹理，之后每帧只是贴图
 *                       光晕 30fps 更新，没有任何东西折射它，重画很便宜
 * ```
 *
 * - **玻璃只按 6fps 看背景**：玻璃里是模糊过、又叠了一层白的背景，光晕晚零点几秒更新
 *   根本看不出来，而最贵的重新模糊、折射从每秒 30 次降到 6 次。两份最多差 1/6 秒的漂移，
 *   也就十几个像素的软渐变。
 * - **屏幕上 30fps，不是 60 / 120**：光晕移动得很慢，每帧只挪几个像素，30fps 和高刷看
 *   不出区别。
 * - **只重画，不重组**：时间是个 State，只在绘制阶段读。推进一次只让光晕那一层重画，
 *   不触发任何 recomposition。
 * - **能停就停**：App 不在前台、系统关掉了动画（开发者选项或无障碍里的"移除动画"）、
 *   省电模式、低内存设备，都停在当前那一帧，当一张静态背景用。调用方也可以用
 *   [animate] 关掉——设置页满屏玻璃，背景几乎露不出来，就不动。
 *
 * 实测（120Hz 屏，静置不操作）：对话页、任务页 RenderThread 约 200ms/s，设置页 0。
 * 分层之前（玻璃也跟着 30fps 更新）同样的场景是 390～680ms/s。
 *
 * 没用 AGSL 着色器画背景：全屏着色器每个像素都要算一遍，放在 3K 分辨率的屏幕上反而比
 * 几个渐变圆贵得多；光晕这种又大又软的东西，用渐变画已经足够好看。
 */
@Composable
fun GlassBackground(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = KittenTheme.colors
    val clock = rememberAuroraClock(animate)
    Box(modifier = modifier.fillMaxSize()) {
        // ① 给玻璃看的那份：不上屏，只录进 backdrop，低频更新。
        // 它不能包含任何读取 backdrop 的东西，否则成环。
        Box(
            modifier = Modifier
                .fillMaxSize()
                // alpha(0) 只影响上屏合成，不影响 layerBackdrop 录下来的内容（和 tab 栏的
                // 隐藏层是同一个用法）。这两个 modifier 的顺序不能换。
                .alpha(0f)
                // 顺序不能换，见上面"layerBackdrop 必须写在绘制 modifier 的左边"。
                .layerBackdrop(backdrop)
                .aurora(colors) { clock.glassTime },
        ) {
            // 雾作为子节点画在光晕和点阵之间，同样只按玻璃的节奏更新。
            BackgroundMist(time = { clock.glassTime })
        }
        // ② 屏幕上看到的那份，拆成四层：不动的底色、会动的光晕和雾、不动的点阵。
        //
        // 不动的两层用离屏合成（Offscreen）：渲染成一张纹理缓存起来，之后每帧只是贴图。
        // 不拆的话，光晕每推进一次，底色渐变和七百多个点也得跟着重新画一遍——它们根本没变，
        // 实测拆开后每帧省 1ms 左右。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawBehind { drawBase(colors) },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 单独一个图层：光晕每推进一次只重录这一层。不加的话它会和兄弟节点画在同一个
                // 父图层里，背景一变，整棵内容树（顶栏、页面里的文字……）都得跟着重新录制。
                .graphicsLayer()
                .drawBehind { drawGlows(colors, clock.screenTime) },
        )
        BackgroundMist(time = { clock.screenTime })
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .dots(colors),
        )
        // 内容是上面这些背景层的兄弟，盖在它们上面，不参与录制。
        content()
    }
}

/**
 * 在一层里画完整张背景：底色、光晕，子节点（雾）夹在中间，最上面是点阵。给玻璃看的
 * 那份用。
 *
 * [time] 在绘制阶段才读，时间推进只会让这一层重画，不会触发重组。
 */
private fun Modifier.aurora(colors: KittenColors, time: () -> Float): Modifier =
    drawWithCache {
        val dots = dotGrid(size, DotSpacing.toPx())
        val dotSize = DotSize.toPx()
        onDrawWithContent {
            drawBase(colors)
            drawGlows(colors, time())
            drawContent()
            drawDots(dots, dotSize, colors)
        }
    }

/** 只画点阵。点阵坐标只跟尺寸有关，缓存住，别每次重画都重新算几百个点。 */
private fun Modifier.dots(colors: KittenColors): Modifier =
    drawWithCache {
        val dots = dotGrid(size, DotSpacing.toPx())
        val dotSize = DotSize.toPx()
        onDrawBehind { drawDots(dots, dotSize, colors) }
    }

private fun DrawScope.drawDots(dots: List<Offset>, dotSize: Float, colors: KittenColors) {
    drawPoints(
        points = dots,
        pointMode = PointMode.Points,
        color = colors.backgroundDots,
        strokeWidth = dotSize,
        cap = StrokeCap.Round,
    )
}

/**
 * 背景的时钟（秒），分两个节奏。
 *
 * 不走的时候停在当前时间（从没走过就是 0，光晕的初始位置），背景就是一张静态图。
 */
@Stable
private class AuroraClock {
    /** 屏幕上的背景用：按 [AuroraFps] 推进。 */
    var screenTime by mutableFloatStateOf(0f)

    /** 给玻璃看的背景用：按 [GlassFps] 推进，总是追着 [screenTime]，最多落后一拍。 */
    var glassTime by mutableFloatStateOf(0f)
}

@Composable
private fun rememberAuroraClock(animate: Boolean): AuroraClock {
    val clock = remember { AuroraClock() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner, context, animate) {
        // 调用方说这会儿不用动（比如背景几乎被玻璃挡完的页面），就停在当前这一帧。
        if (!animate) return@LaunchedEffect
        // 只在前台时走。切到后台这个块会被取消，回到前台重新进来，顺便重新检查一遍
        // 省电模式和动画开关——用户可能是去系统设置里改完了才回来的。
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!shouldAnimate(context)) return@repeatOnLifecycle
            val interval = 1f / AuroraFps
            val glassInterval = 1f / GlassFps
            var lastFrameNanos = 0L
            var pending = 0f
            var sinceCheck = 0f
            while (true) {
                val frameNanos = withFrameNanos { it }
                if (lastFrameNanos != 0L) {
                    // 夹住单帧步长：卡顿后补帧时光晕不该突然跳一大截。
                    val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.1f)
                    pending += dt
                    sinceCheck += dt
                    // 留一点余量：120Hz 下 4 帧加起来是 33.32ms，按浮点算会略小于 1/30 秒，
                    // 不留余量就变成每 5 帧才推进一次，实际只有 24fps。
                    if (pending >= interval - FrameSlack) {
                        clock.screenTime += pending
                        pending = 0f
                        // 玻璃那份隔几拍才追一次。只有它变了，玻璃才需要重新模糊、折射。
                        if (clock.screenTime - clock.glassTime >= glassInterval - FrameSlack) {
                            clock.glassTime = clock.screenTime
                        }
                    }
                    // 省电模式可能在前台时被打开（下拉通知栏切开关不会让 Activity 暂停），
                    // 隔几秒查一次。这是一次跨进程调用，不能每帧都查。
                    if (sinceCheck >= RecheckSeconds) {
                        sinceCheck = 0f
                        if (!shouldAnimate(context)) return@repeatOnLifecycle
                    }
                }
                lastFrameNanos = frameNanos
            }
        }
    }
    return clock
}

/** 系统层面允许我们做持续动画吗。任何一项不允许，背景（和卡片里的光雾）就保持静态。 */
internal fun shouldAnimate(context: Context): Boolean {
    // 用户在开发者选项或无障碍里关掉了动画：这是明确的意愿，必须尊重。
    val animatorScale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    if (animatorScale == 0f) return false

    val power = context.getSystemService(PowerManager::class.java)
    if (power?.isPowerSaveMode == true) return false

    val activity = context.getSystemService(ActivityManager::class.java)
    if (activity?.isLowRamDevice == true) return false

    return true
}

/**
 * 背景的三个部分：底色、几团漂移的光晕、点阵。
 *
 * 用绘制而不是堆 Composable：这些是纯装饰，不需要参与布局和测量，直接画进画布最省。
 */
private fun DrawScope.drawBase(colors: KittenColors) {
    // 底色：极浅的蓝白。上面的玻璃比它更白一点，才有"浮起来"的对比。
    drawRect(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
}

private fun DrawScope.drawGlows(colors: KittenColors, time: Float) {
    // 光晕的位置故意错开并超出边界——被屏幕裁掉一部分，比几个完整的圆自然得多。
    // 半径给得大，边缘才够柔和，否则折射时会露出生硬的圆形轮廓。白色亮斑最后画，
    // 压在蓝色上面，才是"一片光扫过去"而不是"蓝色被冲淡"。
    drawBlob(colors.backgroundGlowPrimary, Blobs[0], time)
    drawBlob(colors.backgroundGlowSecondary, Blobs[1], time)
    drawBlob(colors.backgroundGlowTertiary, Blobs[2], time)
    drawBlob(colors.backgroundGlowHighlight, Blobs[3], time)
}

/**
 * 一团光晕的运动参数。位置和漂移幅度按屏幕宽高的比例给，换分辨率不用重调。
 *
 * 横纵两个方向用不同的周期，轨迹就是一条缓慢变形的利萨如曲线，而不是来回直线运动。
 */
private class AuroraBlob(
    val baseX: Float,
    val baseY: Float,
    val driftX: Float,
    val driftY: Float,
    val periodX: Float,
    val periodY: Float,
    val phase: Float,
    val radius: Dp,
    /** 半径呼吸的幅度（比例）。 */
    val breathe: Float,
)

private fun DrawScope.drawBlob(color: Color, blob: AuroraBlob, time: Float) {
    val x = blob.baseX + blob.driftX * sin(TwoPi * time / blob.periodX + blob.phase)
    val y = blob.baseY + blob.driftY * sin(TwoPi * time / blob.periodY + blob.phase * 1.7f)
    val breathing = 1f + blob.breathe * sin(TwoPi * time / (blob.periodX * 0.7f) + blob.phase)
    glow(color, center = Offset(size.width * x, size.height * y), radius = blob.radius * breathing)
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Dp) {
    val r = radius.toPx()
    drawCircle(
        // 中心到边缘完全透明，拼接处才不会有可见的圆边。
        brush = Brush.radialGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            center = center,
            radius = r,
        ),
        radius = r,
        center = center,
    )
}

/** 铺满 [size] 的等距点阵，整体居中，左右留白对称。 */
private fun dotGrid(size: Size, spacing: Float): List<Offset> {
    if (spacing <= 0f || size.width <= 0f || size.height <= 0f) return emptyList()
    val cols = (size.width / spacing).toInt() + 1
    val rows = (size.height / spacing).toInt() + 1
    val originX = (size.width - (cols - 1) * spacing) / 2f
    val originY = (size.height - (rows - 1) * spacing) / 2f
    return buildList(cols * rows) {
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                add(Offset(originX + col * spacing, originY + row * spacing))
            }
        }
    }
}

/*
 * 四团光晕。前三团绕着改成动态之前的位置游走（左上的天依蓝、右侧的浅青、左下的
 * 浅紫），第四团是新加的白色亮斑，从中间往四周游走。
 *
 * 周期都在 20～45 秒之间、两两互不成倍数，叠在一起要很久才会回到同一个画面。
 */
private val Blobs = listOf(
    AuroraBlob(0.15f, 0.12f, 0.16f, 0.08f, 23f, 31f, 0.0f, 320.dp, 0.10f),
    AuroraBlob(0.95f, 0.34f, 0.12f, 0.14f, 29f, 37f, 1.3f, 280.dp, 0.12f),
    AuroraBlob(0.30f, 0.82f, 0.18f, 0.07f, 41f, 27f, 2.6f, 360.dp, 0.08f),
    AuroraBlob(0.55f, 0.55f, 0.28f, 0.22f, 43f, 33f, 4.1f, 240.dp, 0.15f),
)

private val TwoPi = (2 * PI).toFloat()

/** 屏幕上的背景推进的帧率。光晕很慢，30fps 足够顺滑。 */
private const val AuroraFps = 30f

/**
 * 玻璃看到的背景推进的帧率。
 *
 * 玻璃里看到的是模糊过、又叠了一层白的背景，光晕晚零点几秒更新根本看不出来；但每更新
 * 一次，屏幕上所有玻璃都要重新模糊、折射，这是整个背景动画里最贵的部分。
 */
private const val GlassFps = 6f

/** 前台时隔多久重新检查一次省电模式。 */
private const val RecheckSeconds = 5f

/** 判断"够一个推进间隔了"时的余量（秒），吸收帧间隔的浮点误差。 */
private const val FrameSlack = 0.002f

private val DotSpacing = 22.dp
private val DotSize = 1.6.dp
