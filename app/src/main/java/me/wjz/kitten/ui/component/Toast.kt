package me.wjz.kitten.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import me.wjz.kitten.ui.theme.KittenTheme

/** Toast 弹在哪里。 */
enum class ToastPosition {
    /** 底部弹出，停在底部栏上面；键盘弹起时贴着键盘。随手的提示、出错都用它。 */
    Bottom,

    /** 屏幕正中间。要用户停下来看一眼的结果（比如"已保存"）用它。 */
    Center,
}

/** 提示的语气，决定前面那个小圆点的颜色；普通提示不带圆点。 */
enum class ToastTone { Info, Success, Error }

/** 正在显示的一条。按身份比较：内容一样的两条也是两条。 */
class ToastData internal constructor(
    val message: String,
    val position: ToastPosition,
    val tone: ToastTone,
    val durationMillis: Long,
)

/**
 * 弹 Toast 的入口。界面上任何地方拿 [LocalToast] 调 [show] 就行，画在哪里、什么时候收起由
 * [ToastHost] 管。
 *
 * 一次只显示一条：新的一条会把正在显示的那条顶掉（先收起旧的，再弹出新的），不排队——
 * 提示是"现在发生了什么"，排在后面的那条等轮到它时多半已经过时了。
 */
@Stable
class ToastState {

    internal var current by mutableStateOf<ToastData?>(null)
        private set

    /**
     * @param durationMillis 停多久。不传时按长短来：一句话的短提示停 [ToastShortMillis]，
     *   长的（比如出错原因）停 [ToastLongMillis]，读得完。
     */
    fun show(
        message: String,
        position: ToastPosition = ToastPosition.Bottom,
        tone: ToastTone = ToastTone.Info,
        durationMillis: Long = if (message.length > LongMessageLength) ToastLongMillis else ToastShortMillis,
    ) {
        current = ToastData(message, position, tone, durationMillis)
    }

    /** 收起 [toast]。它已经被别的一条顶掉了的话什么都不做。 */
    fun dismiss(toast: ToastData) {
        if (current === toast) current = null
    }
}

/** 界面里弹 Toast 用的。由 KittenApp 提供，挂在 [ToastHost] 上。 */
val LocalToast = staticCompositionLocalOf<ToastState> {
    error("LocalToast 没有提供：外面要有 KittenApp 里那个 ToastHost")
}

/**
 * 把 [state] 里的 Toast 画出来：一块浅天依蓝的圆角条，深海蓝的字，带阴影，底部或者正中间弹出。
 * 颜色见 KittenColors 的 toast / onToast。
 *
 * ## 为什么不做成玻璃、也不用系统的 Toast
 *
 * - 不做玻璃：玻璃得读背后的 backdrop，Toast 就得挂进浮层体系里、跟着各层的录制走。它只是
 *   一句提示，一块实色的底配上阴影就读得清，用不着那一套。
 * - 不用系统的：系统 Toast 另开窗口，Android 11 起文字 Toast 由系统自己画，样式改不了，
 *   位置也只能在底部，做不出居中那种。
 *
 * 所以它是界面最上面单独一层：放在 KittenApp 里所有东西（包括浮层上的模态窗、下拉菜单）的
 * 后面，盖在它们上面。整层铺满屏幕，但只有 Toast 那一小块接触摸（点一下收起），其余地方的
 * 触摸照样落到下面，弹着 Toast 也能接着操作。
 *
 * @param bottomInsets 底部那种要让出来的位置：底部栏，键盘弹起时是键盘。
 */
@Composable
fun ToastHost(
    state: ToastState,
    bottomInsets: WindowInsets,
    modifier: Modifier = Modifier,
) {
    // 屏幕上正在画的那一条。和 state.current 分开：收起的动画放完之前还得接着画。
    var shown by remember { mutableStateOf<ToastData?>(null) }
    val progress = remember { Animatable(0f) }
    val requested = state.current

    LaunchedEffect(requested) {
        if (requested == null) {
            progress.animateTo(0f, ExitSpec)
            shown = null
            return@LaunchedEffect
        }
        // 正在显示别的一条：先收起它，再弹新的。
        if (shown != null && shown !== requested) progress.animateTo(0f, ExitSpec)
        shown = requested
        progress.animateTo(1f, EnterSpec)
        delay(requested.durationMillis)
        state.dismiss(requested)
    }

    val toast = shown ?: return
    val colors = KittenTheme.colors
    val atBottom = toast.position == ToastPosition.Bottom
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(if (atBottom) bottomInsets else WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = if (atBottom) Alignment.BottomCenter else Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = ToastMaxWidth)
                .graphicsLayer {
                    val p = progress.value
                    alpha = p.coerceIn(0f, 1f)
                    val scale = lerp(ToastStartScale, 1f, p)
                    scaleX = scale
                    scaleY = scale
                    if (atBottom) translationY = (1f - p) * ToastRise.toPx()
                }
                .pointerInput(toast) { detectTapGestures { state.dismiss(toast) } }
                // 读屏自动念出来：子节点的文字合并进来，整块作为一个播报区域。
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .shadow(elevation = 2.dp, shape = ToastShape, clip = false)
                .background(colors.toast, ToastShape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val dot = when (toast.tone) {
                ToastTone.Info -> null
                ToastTone.Success -> colors.positive
                ToastTone.Error -> colors.danger
            }
            if (dot != null) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dot, CircleShape),
                )
            }
            Text(
                text = toast.message,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onToast,
                maxLines = ToastMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 短提示停多久（毫秒）。 */
const val ToastShortMillis = 1500L

/** 长提示停多久（毫秒）：出错原因这类要读一会儿的。 */
const val ToastLongMillis = 3500L

/** 超过这么多字算长提示。 */
private const val LongMessageLength = 30

/** 最宽多少：平板上铺满会宽得没法读，和模态窗的上限一样。 */
private val ToastMaxWidth = 480.dp

/** 最多几行，再多就省略：Toast 不是用来读长文的。 */
private const val ToastMaxLines = 4

/** 圆角和底部栏、模态窗同一档。 */
private val ToastShape = RoundedCornerShape(GlassRadius.Large)

/** 弹出时从这个大小长到原尺寸。 */
private const val ToastStartScale = 0.9f

/** 底部那种弹出时从下面浮上来的距离。 */
private val ToastRise = 24.dp

/** 弹出带一点过冲，像被托上来的；收起不过冲。 */
private val EnterSpec = spring<Float>(dampingRatio = 0.75f, stiffness = 500f)
private val ExitSpec = spring<Float>(dampingRatio = 1f, stiffness = 700f)
