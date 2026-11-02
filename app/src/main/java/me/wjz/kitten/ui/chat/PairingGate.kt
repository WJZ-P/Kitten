package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import me.wjz.kitten.AppVisibility
import me.wjz.kitten.R
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.client.WirelessDebugController
import me.wjz.kitten.ui.component.LocalFrostedBackdrop
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 配对引导要的几样状态，见 [rememberPairingGate]。
 *
 * @param shown 引导页该不该摆着。
 * @param ready 特权进程连上了。输入框看它，不看 [shown]：退场那一秒多引导页还摆着，输入框已经能用了。
 * @param lit 大圆亮起来：连上了，而且用户看得见。
 * @param busy 正在配对、连接。
 * @param failure 上一次配对为什么没成。没失败过、或者正在连，是 null。
 */
@Immutable
internal data class PairingGateState(
    val shown: Boolean,
    val ready: Boolean,
    val lit: Boolean,
    val busy: Boolean,
    val failure: String?,
)

/**
 * 对话页上的配对引导该不该摆、摆成什么样。
 *
 * 增强模式必开：没有特权进程，Kitten 开不了后台副屏，也点不了屏幕，所以连上之前对话页只放这块引导，输入框禁用。
 *
 * ## 什么时候摆出来
 *
 * 没连上，而且不是"配对过、正在自己连回去"。配对过的用户每次冷启动都要先自动重连一两秒（见
 * [WirelessDebugController.reconnecting]），连上之后还要等特权进程交回 binder（[PrivilegeState.Connecting]），
 * 这两段都不算没连上，不然老用户每次打开都会闪一下引导页。
 *
 * 摆出来之后只有连上了才收：中间自动重连一下、连接中、又失败，引导页都一直在，不来回闪。
 *
 * ## 退场
 *
 * 连上的那一刻用户多半还在系统设置里（配对码填在通知里，见 AdbPairingService），看不见 Kitten。所以要等
 * 连上了、并且回到了 Kitten，大圆才亮起来（[PairingGateState.lit]），亮着停 [LitHoldMillis]，用户看清
 * "连上了"再退场。
 */
@Composable
internal fun rememberPairingGate(): PairingGateState {
    val context = LocalContext.current
    val controller = remember(context) { WirelessDebugController.getInstance(context) }
    val privilege by PrivilegeManager.state.collectAsState()
    val step by controller.step.collectAsState()
    val reconnecting by controller.reconnecting.collectAsState()
    val foreground by AppVisibility.inForeground.collectAsState()

    val ready = privilege is PrivilegeState.Ready
    val launching = privilege is PrivilegeState.Connecting
    val needed = !ready && !reconnecting && !launching
    var shown by remember { mutableStateOf(needed) }
    LaunchedEffect(needed) { if (needed) shown = true }

    val lit = ready && foreground
    LaunchedEffect(lit) {
        if (lit && shown) {
            delay(LitHoldMillis)
            shown = false
        }
    }

    val busy = !ready && (reconnecting || launching || step.isInProgress())
    val failure = (step as? WirelessDebugController.Step.Failed)?.message
        ?: (privilege as? PrivilegeState.Error)?.message
    return PairingGateState(
        shown = shown,
        ready = ready,
        lit = lit,
        busy = busy,
        failure = failure.takeIf { !ready && !busy },
    )
}

private fun WirelessDebugController.Step.isInProgress(): Boolean = when (this) {
    WirelessDebugController.Step.Pairing,
    WirelessDebugController.Step.Connecting,
    WirelessDebugController.Step.Launching,
    WirelessDebugController.Step.Launched -> true
    WirelessDebugController.Step.Idle,
    is WirelessDebugController.Step.Failed -> false
}

/**
 * 配对引导：中间一个大圆，点了去配对（[onPair]，挂上填配对码的通知、跳到「无线调试」）；下面一句为什么要配、
 * 四步教程。内容不够一屏时上下居中，屏幕矮就整块滚。
 *
 * @param animating 外圈转不转。对话不是当前 tab 时停下：看不见的东西不动。
 * @param bottomPadding 浮在下面的输入栏的高度，最后一张卡片要能滚到它上面。
 */
@Composable
internal fun PairingGate(
    gate: PairingGateState,
    onPair: () -> Unit,
    animating: Boolean,
    backdrop: Backdrop,
    bottomPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val orbSize = min(min(maxWidth * OrbWidthFraction, maxHeight * OrbHeightFraction), OrbMaxSize)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // 至少一屏高，Arrangement 才居中得了：能滚的 Column 高度不设限。
                .heightIn(min = maxHeight)
                .padding(horizontal = GateGutter)
                .padding(top = GateGutter, bottom = bottomPadding + GateGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(GateGap, Alignment.CenterVertically),
        ) {
            PairingOrb(
                lit = gate.lit,
                label = when {
                    gate.lit -> "连上啦"
                    gate.busy -> "连接中…"
                    else -> "点我去配对"
                },
                onClick = onPair,
                animating = animating,
                modifier = Modifier.size(orbSize),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "先给 Kitten 开个门～",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "她要在后台另一块屏幕上替你干活，得先用「无线调试」配个对。只需要配一次就行～",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                gate.failure?.let { reason ->
                    Text(
                        text = "刚才没连上（$reason），再点一下大圆试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.warning,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .chatCardGlass(backdrop, colors, frosted = LocalFrostedBackdrop.current)
                    .padding(ChatCardPadding),
                verticalArrangement = Arrangement.spacedBy(StepGap),
            ) {
                PairingSteps.forEachIndexed { index, step -> PairingStep(number = index + 1, text = step) }
            }
        }
    }
}

/**
 * 配对的几步。小米的开发者选项藏得和别家不一样，单独点一句：Kitten 主要在 HyperOS 上测。
 *
 * 第三步的"弹窗先别关"不能省：配对码只在系统那个弹窗开着时有效，回到 Kitten 弹窗就关了、码也作废，所以码
 * 要填在通知里（见 AdbPairingService）。
 */
private val PairingSteps = listOf(
    "打开「开发者选项」：在「关于手机」里连点版本号，小米是「我的设备 › 全部参数与信息」里的「OS 版本」，点到提示已开启为止。",
    "连上 Wi-Fi，点上面的大圆，打开高亮的「无线调试」。",
    "点「使用配对码配对设备」，弹窗先别关。下拉通知栏，在 Kitten 的通知里填上那 6 位配对码。",
    "填完就能回来啦，Kitten 会自己连上～以后都不用再配。",
)

@Composable
private fun PairingStep(number: Int, text: String) {
    val colors = KittenTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(StepNumberGap)) {
        Box(
            modifier = Modifier
                .size(StepNumberSize)
                .background(colors.accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onAccent,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 中间的大圆：外面一圈虚线一直在转，里面一个实心圆，圆里是 Kitten 的猫脸和一句话。照着 NekoCrypt 首页那个猫爪
 * 按钮做的，颜色只用纯色。
 *
 * 没连上时灰灰的：圈小一点、转得慢、缝大；连上了（[lit]）变成天依蓝：圈张开、转快、缝收窄。
 *
 * 圆不带阴影：底色是半透明的，阴影就画在它正下方，透过底色看得见阴影的形状——系统画大圆的阴影是拼出来的
 * 多边形，看着像圆里套了个八边形。
 *
 * 按下圆微微缩，在圆里抬手才算点（和别的按钮一样，见 detectGlassPress）。
 */
@Composable
private fun PairingOrb(
    lit: Boolean,
    label: String,
    onClick: () -> Unit,
    animating: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnClick by rememberUpdatedState(onClick)

    val ringColor by animateColorAsState(
        if (lit) colors.accent else colors.textTertiary.copy(alpha = RingIdleAlpha),
        tween(ColorMillis),
        label = "ringColor",
    )
    val discColor by animateColorAsState(if (lit) colors.accent else colors.controlTint, tween(ColorMillis), label = "discColor")
    val contentColor by animateColorAsState(
        if (lit) colors.onAccent else colors.textTertiary,
        tween(ColorMillis),
        label = "contentColor",
    )
    val ringScale by animateFloatAsState(if (lit) 1f else RingIdleScale, tween(GrowMillis), label = "ringScale")
    val gap by animateFloatAsState(if (lit) LitGapDegrees else IdleGapDegrees, tween(GrowMillis), label = "gap")
    // 转速慢慢提上去，不是一下子跳快。
    val speed by animateFloatAsState(if (lit) LitDegreesPerSecond else IdleDegreesPerSecond, tween(SpeedMillis), label = "speed")

    // 角度只在画的时候读，转起来只重画这一圈，不重组。
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle = (angle + speed * (now - last) / 1_000_000_000f) % 360f
                last = now
            }
        }
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val side = min(maxWidth, maxHeight)
        val faceSize = side * FaceFraction
        Canvas(modifier = Modifier.size(side)) {
            val stroke = RingStroke.toPx()
            val radius = (size.minDimension - stroke) / 2f * ringScale
            val arcSize = Size(radius * 2f, radius * 2f)
            val topLeft = Offset(center.x - radius, center.y - radius)
            val segment = 360f / RingSegments
            rotate(angle) {
                repeat(RingSegments) { i ->
                    drawArc(
                        color = ringColor,
                        startAngle = i * segment + gap / 2f,
                        sweepAngle = segment - gap,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .size(side * DiscFraction)
                .graphicsLayer {
                    val scale = lerp(1f, OrbPressedScale, press.pressProgress)
                    scaleX = scale
                    scaleY = scale
                }
                .background(discColor, CircleShape)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick(label = label) {
                        currentOnClick()
                        true
                    }
                }
                .pointerInput(press) { detectGlassPress(press) { currentOnClick() } },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 直接用桌面图标的前景层（只有白色描边，染成什么色都行）。它是 108 见方、猫脸只占中间一小块，
                // 所以画得比占的位置大，四周透明的那圈溢出去，排版只算猫脸那么大。
                Icon(
                    painter = painterResource(R.drawable.ic_launcher_foreground),
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier
                        .size(faceSize)
                        .wrapContentSize(unbounded = true)
                        .requiredSize(faceSize * LauncherIconPerFace),
                )
                Spacer(modifier = Modifier.height(FaceLabelGap))
                AnimatedContent(
                    targetState = label,
                    transitionSpec = {
                        (slideInVertically { it } + fadeIn(tween(LabelMillis)))
                            .togetherWith(slideOutVertically { -it } + fadeOut(tween(LabelMillis)))
                    },
                    label = "orbLabel",
                ) { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                }
            }
        }
    }
}

/** 连上之后大圆亮着停多久再退场：够用户看清"连上啦"，又不至于让人干等。 */
private const val LitHoldMillis = 1200L

/**
 * 大圆占多大：页面宽度的四分之三，也不超过页面高度的一半（矮屏上给下面的教程留地方），最大 300dp。
 */
private const val OrbWidthFraction = 0.75f
private const val OrbHeightFraction = 0.5f
private val OrbMaxSize = 300.dp

/** 中间实心圆、猫脸相对整个大圆（含外圈）有多大。 */
private const val DiscFraction = 0.8f
private const val FaceFraction = 0.3f

/**
 * 桌面图标前景层的边长是猫脸宽度的几倍：前景 108 见方，猫脸 56 宽、再缩 0.85（见 ic_launcher_foreground.xml），
 * 108 / (56 × 0.85) ≈ 2.27。
 */
private const val LauncherIconPerFace = 2.27f
private val FaceLabelGap = 6.dp

/** 按下时圆缩到多大。 */
private const val OrbPressedScale = 0.95f

/**
 * 外圈：几段虚线、多粗，没连上时缩到多大、多淡，两段之间空几度。
 *
 * 照 NekoCrypt 的十二段。缝在没连上时大一点，看着松散；连上了收窄，像合拢了。
 */
private const val RingSegments = 12
private val RingStroke = 5.dp
private const val RingIdleScale = 0.92f
private const val RingIdleAlpha = 0.5f
private const val IdleGapDegrees = 12f
private const val LitGapDegrees = 7f

/**
 * 外圈转速（度 / 秒）：没连上时慢悠悠，一圈一分钟多；连上了快好几倍，一眼看得出"活了"。
 * NekoCrypt 是 5 → 15，这边连上后只亮一秒多就退场，差得大一点才看得出来。
 */
private const val IdleDegreesPerSecond = 6f
private const val LitDegreesPerSecond = 40f

/** 变色、圈张开、转速提上去、圆里那句话换掉各用多久。转速慢一点提，看得出在加速。 */
private const val ColorMillis = 500
private const val GrowMillis = 600
private const val SpeedMillis = 900
private const val LabelMillis = 250

/** 页面四周留白、几块之间空多少；教程每一步之间、序号和字之间、序号圆多大。 */
private val GateGutter = 24.dp
private val GateGap = 20.dp
private val StepGap = 12.dp
private val StepNumberGap = 10.dp
private val StepNumberSize = 20.dp
