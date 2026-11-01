package me.wjz.kitten.ui.live

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onVisibilityChanged
import androidx.compose.ui.util.lerp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import me.wjz.kitten.agent.AgentScreen
import me.wjz.kitten.agent.ScreenMark
import me.wjz.kitten.agent.ScreenMarks
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 直播 Agent 正在操作的副屏：任务卡片旁的缩略图、全屏直播都是它，只是大小不一样。
 *
 * 画面是特权进程画到这里的 TextureView 上的，按它的大小画（缩略图就画一张小图），见 [LivePreviews]。
 * 动作标在画面上面：Kitten 点了哪就在哪泛一圈，滑了就画一道（见 [ScreenMarks]）。
 *
 * ## 看不见就不画
 *
 * 滑出了屏幕（列表里往上翻走了）、或者 Kitten 退到了后台，就告诉特权那边不看了；回来再接上。没人看的
 * 时候副屏回到直出，一点额外开销都没有。
 *
 * 用 TextureView 而不是 SurfaceView：它是普通 View 的一层，能跟着 Compose 裁圆角、缩放、淡入淡出。
 *
 * @param screen 要看的副屏，宽高比和它一样；调用方按这个比例给大小。
 */
@Composable
internal fun LiveScreen(screen: AgentScreen, modifier: Modifier = Modifier) {
    val owner = remember { Any() }
    // TextureView 交出来的 Surface。销毁了是 null。
    var surface by remember { mutableStateOf<Surface?>(null) }
    var onScreen by remember { mutableStateOf(false) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val watching = onScreen && lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val current = surface
    DisposableEffect(screen.displayId, current, watching) {
        if (current != null && watching) {
            LivePreviews.attach(screen.displayId, owner, current)
            onDispose { LivePreviews.detach(screen.displayId, owner) }
        } else {
            onDispose { }
        }
    }

    Box(
        modifier = modifier
            // 露出一个像素就算看得见：缩略图露出半截时，那半截也该是动的。
            .onVisibilityChanged(minFractionVisible = 0f) { onScreen = it }
            // 第一帧到之前是黑的，和副屏没东西时一个颜色，不会先闪一下白。
            .background(Color.Black),
    ) {
        key(screen.displayId) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture)
                            }

                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
                                Unit

                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                val old = surface
                                surface = null
                                // 特权那边可能还在往上画：先让它停，停稳了再放掉。返回 false，自己来放。
                                LivePreviews.detach(screen.displayId, owner) {
                                    old?.release()
                                    texture.release()
                                }
                                return false
                            }

                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        LiveMarks(screen = screen, modifier = Modifier.fillMaxSize())
    }
}

/** 正在画的一个动作标记，[progress] 从 0 走到 1 就淡没了。 */
private class ActiveMark(val mark: ScreenMark) {
    val progress = Animatable(0f)
}

/**
 * 画面上的动作标记。大小按画面的宽度算，不按 dp：缩略图上是一个小圈，全屏上是一个大圈，和画面里的
 * 内容一起缩放，看着是点在那个按钮上的。
 */
@Composable
private fun LiveMarks(screen: AgentScreen, modifier: Modifier) {
    val colors = KittenTheme.colors
    val marks = remember(screen.displayId) { mutableStateListOf<ActiveMark>() }
    LaunchedEffect(screen.displayId) {
        ScreenMarks.marks.filter { it.displayId == screen.displayId }.collect { mark ->
            val active = ActiveMark(mark)
            marks += active
            launch {
                active.progress.animateTo(1f, tween(MarkMillis, easing = LinearOutSlowInEasing))
                marks -= active
            }
        }
    }
    Canvas(modifier = modifier) {
        val scaleX = size.width / screen.width
        val scaleY = size.height / screen.height
        val unit = size.width
        marks.forEach { active ->
            val p = active.progress.value
            val alpha = 1f - p
            when (val mark = active.mark) {
                is ScreenMark.Touch -> {
                    val center = Offset(mark.x * scaleX, mark.y * scaleY)
                    drawCircle(
                        color = colors.accent,
                        radius = lerp(TouchRingStart, TouchRingEnd, p) * unit,
                        center = center,
                        alpha = alpha,
                        style = Stroke(width = (RingWidth * unit).coerceAtLeast(1f)),
                    )
                    // 长按多一层实心的，和点一下分得开。
                    if (mark.long) {
                        drawCircle(colors.accent, radius = TouchRingEnd * unit, center = center, alpha = alpha * LongPressFill)
                    }
                    drawCircle(colors.accent, radius = DotRadius * unit, center = center, alpha = alpha)
                }
                is ScreenMark.Slide -> {
                    val from = Offset(mark.fromX * scaleX, mark.fromY * scaleY)
                    val to = Offset(mark.toX * scaleX, mark.toY * scaleY)
                    drawLine(
                        color = colors.accent,
                        start = from,
                        end = to,
                        strokeWidth = (TrailWidth * unit).coerceAtLeast(1f),
                        cap = StrokeCap.Round,
                        alpha = alpha * TrailAlpha,
                    )
                    drawCircle(colors.accent, radius = DotRadius * unit, center = to, alpha = alpha)
                }
            }
        }
    }
}

/** 一个标记从出现到淡没多久（毫秒）：够看清点在哪，又不会在下一步动作时还挂着。 */
private const val MarkMillis = 700

/**
 * 标记的大小，都是画面宽度的几分之几：点一下的圈从多大泛到多大、圈多粗、中间那个点多大、滑动那道线多粗。
 * 全屏（宽一千多像素）上圈泛到八十多像素，正好盖住一个按钮。
 */
private const val TouchRingStart = 0.02f
private const val TouchRingEnd = 0.08f
private const val RingWidth = 0.008f
private const val DotRadius = 0.012f
private const val TrailWidth = 0.014f

/** 长按那层实心圈、滑动那道线的透明度：压在画面上不能太实，不然挡住了按钮上的字。 */
private const val LongPressFill = 0.25f
private const val TrailAlpha = 0.7f
