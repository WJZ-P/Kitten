package me.wjz.kitten.ui.live

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.wjz.kitten.agent.AgentScreen
import me.wjz.kitten.ui.chat.ChatCardPadding
import me.wjz.kitten.ui.chat.chatCardGlass
import me.wjz.kitten.ui.chat.rememberShimmerProgress
import me.wjz.kitten.ui.chat.shimmer
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassOverlay
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.downsampledBlur
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.icon.Stop
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 全屏看 Kitten 在副屏上干什么：任务卡片旁的缩略图点了打开。
 *
 * 副屏和主屏一样大，这里按它的宽高比放到最大，看着就像自己打开了那个应用，字都看得清。下面一条写着这会儿
 * 在干什么，旁边一个停止。背后和看大图一样整块糊开、压暗（[me.wjz.kitten.ui.component.ImageViewer]），点画面
 * 外面、按返回、点右上角的关闭都收起来。
 *
 * 这里的画面接手之后，缩略图那边停在最后一帧不动（见 [LivePreviews]），收起来再接着动。任务办完副屏收掉了，
 * [screen] 变成 null，这里跟着收起来。
 *
 * 画在 GlassOverlayHost 上，外面必须有一个 host。
 *
 * @param screen 要看的副屏，null 表示关着。关的时候收起动画还要用，所以里面记着最后一份。
 * @param status 下面那一条写什么：第几步、在干什么。
 * @param busy 这会儿是不是在动手（在等用户回答时不是）：是的话那一条字上扫流光。
 * @param onStop 点了停止。
 */
@Composable
internal fun LiveScreenViewer(
    screen: AgentScreen?,
    status: String,
    busy: Boolean,
    onStop: () -> Unit,
    onDismissRequest: () -> Unit,
    backdrop: Backdrop,
) {
    val visible = screen != null
    val currentVisible by rememberUpdatedState(visible)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    var lastScreen by remember { mutableStateOf(screen) }
    if (screen != null && screen != lastScreen) lastScreen = screen

    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        progress.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = if (visible) OpenSpec else CloseSpec,
        )
    }
    // 收起的动画放完之前还得挂着。
    val shown by remember { derivedStateOf { currentVisible || progress.value != 0f } }

    BackHandler(enabled = visible) { currentOnDismiss() }

    val target = lastScreen
    GlassOverlay(visible = shown && target != null) { _, behind ->
        if (target != null) {
            ViewerLayer(
                screen = target,
                status = status,
                busy = busy,
                surroundings = rememberCombinedBackdrop(backdrop, behind),
                progress = { progress.value },
                onStop = onStop,
                onDismiss = { currentOnDismiss() },
            )
        }
    }
}

@Composable
private fun ViewerLayer(
    screen: AgentScreen,
    status: String,
    busy: Boolean,
    surroundings: Backdrop,
    progress: () -> Float,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KittenTheme.colors
    // 糊开、压暗后的背景，录下来给下面那一条和按钮折射，见 ImageViewer。
    val scrimBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：糊开、压暗，吞掉所有触摸，抬手时收起。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = "收起直播"
                    onClick {
                        onDismiss()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        onDismiss()
                    }
                }
                .layerBackdrop(scrimBackdrop)
                .downsampledBlur(surroundings, ScrimBlur, progress = progress, downscale = ScrimDownscale)
                .drawBehind { drawRect(colors.viewerScrim, alpha = progress().coerceIn(0f, 1f)) },
        )

        // 画面在上、状态条在下，一起居中在扣掉系统栏的区域里。画面按副屏的宽高比放到最大，给状态条留出位置。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(ViewerPadding),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = screen.width.toFloat() / screen.height.coerceAtLeast(1)
            val availableHeight = maxHeight - StatusBarHeight - StatusGap
            val fitsWidth = aspect > maxWidth / availableHeight
            val width = if (fitsWidth) maxWidth else availableHeight * aspect
            val height = if (fitsWidth) maxWidth / aspect else availableHeight
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(StatusGap),
                modifier = Modifier.graphicsLayer {
                    val p = progress()
                    alpha = p.coerceIn(0f, 1f)
                    val s = lerp(StartScale, 1f, p)
                    scaleX = s
                    scaleY = s
                },
            ) {
                LiveScreen(
                    screen = screen,
                    modifier = Modifier
                        .size(width, height)
                        .clip(ViewerShape),
                )
                StatusBar(
                    status = status,
                    busy = busy,
                    onStop = onStop,
                    backdrop = scrimBackdrop,
                    modifier = Modifier
                        .width(width)
                        .height(StatusBarHeight),
                )
            }
        }

        GlassIconButton(
            icon = Icons.Filled.Close,
            contentDescription = "收起直播",
            onClick = onDismiss,
            backdrop = scrimBackdrop,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(8.dp)
                .graphicsLayer { alpha = progress().coerceIn(0f, 1f) },
        )
    }
}

/** 画面下面那一条：这会儿在干什么，右边一个停止。 */
@Composable
private fun StatusBar(
    status: String,
    busy: Boolean,
    onStop: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(StatusGap),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .height(StatusBarHeight)
                .chatCardGlass(backdrop, colors, frosted = null)
                .padding(horizontal = ChatCardPadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            val shimmer = rememberShimmerProgress(active = busy)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (shimmer != null) {
                    Modifier.shimmer(progress = { shimmer.value }, color = colors.accent)
                } else {
                    Modifier
                },
            )
        }
        GlassIconButton(
            icon = KittenIcons.Stop,
            contentDescription = "停止任务",
            onClick = onStop,
            backdrop = backdrop,
            size = StatusBarHeight,
            // 和那一条一样高、齐平；按钮本身已经够大，触摸区不用再往外扩。
            touchSize = StatusBarHeight,
        )
    }
}

/** 画面离屏幕边（扣掉系统栏之后）留多少，和看大图一样。 */
private val ViewerPadding = 16.dp

/** 画面的圆角，和看大图同一档。 */
private val ViewerShape = RoundedCornerShape(GlassRadius.Large)

/** 下面那一条多高、和画面隔多远。高度和停止按钮的圆一样，两个并排齐平。 */
private val StatusBarHeight = 44.dp
private val StatusGap = 10.dp

/** 打开时从多大长到原尺寸，和看大图一样。 */
private const val StartScale = 0.85f

/** 背后的模糊和降采样，和看大图一样。 */
private val ScrimBlur = 4.dp
private const val ScrimDownscale = 4f

/** 打开带一点过冲，关闭干脆利落，和看大图一样。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 800f)
