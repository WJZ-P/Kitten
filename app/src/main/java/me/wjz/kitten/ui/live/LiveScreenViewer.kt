package me.wjz.kitten.ui.live

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import androidx.core.content.edit
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import me.wjz.kitten.agent.AgentScreen
import me.wjz.kitten.ui.chat.rememberShimmerProgress
import me.wjz.kitten.ui.chat.shimmer
import me.wjz.kitten.ui.component.GlassOverlay
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.downsampledBlur
import me.wjz.kitten.ui.icon.KeyboardArrowDown
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.icon.Stop
import me.wjz.kitten.ui.settings.RowAction
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.roundToInt

/**
 * 全屏看 Kitten 在副屏上干什么：任务卡片旁的缩略图点了打开。
 *
 * 副屏和主屏一样大，这里按它的宽高比铺到差不多满屏，看着就像自己打开了那个应用，字都看得清。画面上浮着
 * 一条操作区（[ControlPanel]），像看视频时的播放控制条：这会儿在干什么、停止、收起。默认在底部居中，
 * 挡住了想看的地方就拖开，松手记下位置，下次打开还在那。
 *
 * 背后和看大图一样整块糊开、压暗（[me.wjz.kitten.ui.component.ImageViewer]）。按返回、点画面外面、点操作区上
 * 的收起都收起来。
 *
 * 这里的画面接手之后，缩略图那边停在最后一帧不动（见 [LivePreviews]），收起来再接着动。任务办完副屏收掉了，
 * [screen] 变成 null，这里跟着收起来。
 *
 * 画在 GlassOverlayHost 上，外面必须有一个 host。
 *
 * @param screen 要看的副屏，null 表示关着。关的时候收起动画还要用，所以里面记着最后一份。
 * @param status 操作区上写什么：第几步、在干什么。
 * @param busy 这会儿是不是在动手（在等用户回答时不是）：是的话那一行字上扫流光。
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

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：糊开、压暗，吞掉所有触摸，抬手时收起。画面差不多铺满，露出来的只有四周一圈。
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
                .downsampledBlur(surroundings, ScrimBlur, progress = progress, downscale = ScrimDownscale)
                .drawBehind { drawRect(colors.viewerScrim, alpha = progress().coerceIn(0f, 1f)) },
        )

        // 画面按副屏的宽高比放到最大，居中在扣掉系统栏的区域里。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(ViewerPadding),
            contentAlignment = Alignment.Center,
        ) {
            val aspect = screen.width.toFloat() / screen.height.coerceAtLeast(1)
            val fitsWidth = aspect > maxWidth / maxHeight
            val width = if (fitsWidth) maxWidth else maxHeight * aspect
            val height = if (fitsWidth) maxWidth / aspect else maxHeight
            // 画面和操作区一起缩放淡入。
            //
            // 透明度用默认的合成方式（半透明时先画进一张离屏图再整张淡）。换成过逐笔乘透明度（ModulateAlpha），
            // 之后收起动画一开始直播画面就变成纯黑：画面是 TextureView，大概是它不吃这种淡法。
            Box(
                modifier = Modifier
                    .size(width, height)
                    .graphicsLayer {
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
                        .fillMaxSize()
                        .clip(ViewerShape),
                )
                ControlPanel(
                    status = status,
                    busy = busy,
                    onStop = onStop,
                    onCollapse = onDismiss,
                )
            }
        }
    }
}

/**
 * 浮在画面上的操作区：这会儿在干什么，右边停止、收起。一条胶囊，像唤起语音助手时底下那条"我在听…"。
 *
 * 能拖：按住空白处一拖就跟着手指走，拖到画面边上为止；松手记下位置（[LivePanelPosition]），下次打开还在那。
 * 拖的时候微微鼓起，告诉用户"抓住了"。按在按钮上是点按钮，不会拖动。
 *
 * 不做成玻璃：它底下是一直在动的直播画面，玻璃要把画面每帧录一份、再糊开，拖起来会卡。实心的浅色底加一层
 * 阴影，压在什么画面上都分得出来。
 */
@Composable
private fun ControlPanel(
    status: String,
    busy: Boolean,
    onStop: () -> Unit,
    onCollapse: () -> Unit,
) {
    val colors = KittenTheme.colors
    val context = LocalContext.current
    val density = LocalDensity.current
    // 在能挪动的范围里的位置，横竖都是 0～1：换了屏幕大小（转屏、分屏）也放得对。
    var position by remember { mutableStateOf(LivePanelPosition.load(context)) }
    var dragging by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (dragging) 1f else 0f, LiftSpec, label = "livePanelLift")

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val panelWidth = min(maxWidth - PanelMargin * 2, PanelMaxWidth)
        val freeX = with(density) { (maxWidth - panelWidth - PanelMargin * 2).toPx() }.coerceAtLeast(0f)
        val freeY = with(density) { (maxHeight - PanelHeight - PanelMargin * 2).toPx() }.coerceAtLeast(0f)
        val margin = with(density) { PanelMargin.toPx() }

        Row(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (margin + position.x * freeX).roundToInt(),
                        (margin + position.y * freeY).roundToInt(),
                    )
                }
                .size(panelWidth, PanelHeight)
                .pointerInput(freeX, freeY) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            LivePanelPosition.save(context, position)
                        },
                        onDragCancel = {
                            dragging = false
                            LivePanelPosition.save(context, position)
                        },
                    ) { change, delta ->
                        change.consume()
                        position = Offset(
                            x = if (freeX > 0f) (position.x + delta.x / freeX).coerceIn(0f, 1f) else position.x,
                            y = if (freeY > 0f) (position.y + delta.y / freeY).coerceIn(0f, 1f) else position.y,
                        )
                    }
                }
                .graphicsLayer {
                    val s = lerp(1f, DraggingScale, lift)
                    scaleX = s
                    scaleY = s
                    shadowElevation = PanelElevation.toPx()
                    shape = PanelShape
                }
                .background(colors.livePanel, PanelShape)
                .border(PanelBorder, colors.hairline, PanelShape)
                .padding(start = PanelPaddingStart, end = PanelPaddingEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PanelActionGap),
        ) {
            val shimmer = rememberShimmerProgress(active = busy)
            Text(
                text = status,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (shimmer != null) Modifier.shimmer(progress = { shimmer.value }, color = colors.accent) else Modifier,
                    ),
            )
            RowAction(icon = KittenIcons.Stop, label = "停止任务", tint = colors.danger, onClick = onStop)
            RowAction(icon = KittenIcons.KeyboardArrowDown, label = "收起直播", tint = colors.textSecondary, onClick = onCollapse)
        }
    }
}

/**
 * 操作区拖到了哪，存在本地：用户拖过一次就记住，下次打开还在那。存的是在能挪动的范围里的比例（见
 * [ControlPanel]），不是像素。
 */
private object LivePanelPosition {
    private const val PREFS = "kitten_ui"
    private const val KEY_X = "live_panel_x"
    private const val KEY_Y = "live_panel_y"

    /** 没拖过时放在底部居中，和看视频时的播放控制条一个位置。 */
    private val Default = Offset(0.5f, 1f)

    fun load(context: Context): Offset {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Offset(
            prefs.getFloat(KEY_X, Default.x).coerceIn(0f, 1f),
            prefs.getFloat(KEY_Y, Default.y).coerceIn(0f, 1f),
        )
    }

    fun save(context: Context, position: Offset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putFloat(KEY_X, position.x)
            putFloat(KEY_Y, position.y)
        }
    }
}

/** 画面离屏幕边（扣掉系统栏之后）留多少：差不多满屏，留一圈窄边看得出这是一层浮上来的东西、点了能收。 */
private val ViewerPadding = 8.dp

/** 画面的圆角，和看大图同一档。 */
private val ViewerShape = RoundedCornerShape(GlassRadius.Large)

/**
 * 操作区：多高、最宽多宽、离画面边至少多远、里面的留白、两个按钮之间的空。
 *
 * 高 52dp：一行字加两个按钮（触摸区 40dp）正好，比输入框矮一点，看着是控制条不是输入框。最宽 360dp：画面
 * 很宽时（横屏、平板）不至于拉成一长条。左边留白给字，右边按钮自带一圈触摸区，留得少一点。
 */
private val PanelHeight = 52.dp
private val PanelMaxWidth = 360.dp
private val PanelMargin = 12.dp
private val PanelPaddingStart = 20.dp
private val PanelPaddingEnd = 8.dp
private val PanelActionGap = 2.dp

/** 操作区是一条胶囊；阴影给一点，压在白底的页面上也分得出边；描一道细边，压在浅色画面上不糊成一片。 */
private val PanelShape = RoundedCornerShape(50)
private val PanelElevation = 8.dp
private val PanelBorder = 1.dp

/** 拖着操作区时鼓多大，和按下卡片差不多，告诉用户抓住了。 */
private const val DraggingScale = 1.04f
private val LiftSpec = spring<Float>(dampingRatio = 0.7f, stiffness = 600f)

/** 打开时从多大长到原尺寸，和看大图一样。 */
private const val StartScale = 0.85f

/** 背后的模糊和降采样，和看大图一样。 */
private val ScrimBlur = 4.dp
private const val ScrimDownscale = 4f

/** 打开带一点过冲，关闭干脆利落，和看大图一样。 */
private val OpenSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 500f)
private val CloseSpec = spring<Float>(dampingRatio = 1f, stiffness = 800f)
