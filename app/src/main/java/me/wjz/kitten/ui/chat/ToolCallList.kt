package me.wjz.kitten.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import me.wjz.kitten.agent.describe
import me.wjz.kitten.llm.ToolCall
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/** 一次工具调用走到哪了。 */
internal enum class ToolCallStatus {
    /** 同一步里排在后面、还没轮到的。一步里的几个工具是挨个执行的。 */
    Pending,

    /** 正在执行。 */
    Running,

    /** 执行完了。 */
    Done,

    /** 执行了，但工具报了错（结果以 `ERROR` 开头，见 [me.wjz.kitten.agent.AgentTool]）。 */
    Failed,

    /** 没等到结果任务就结束了：用户喊了停、或者模型调用中途失败。 */
    Stopped,
}

/**
 * 清单里的一行：哪个调用、走到哪了、结果是什么。
 *
 * @param result 工具返回给模型的那段文字。历史里存的是截短过的（见 ChatViewModel 的 forHistory），
 *   还没执行完时是 null。
 */
@Immutable
internal class ToolCallItem(
    val call: ToolCall,
    val status: ToolCallStatus,
    val result: String?,
)

/**
 * Agent 调过的工具，一行一个，像一张清单：模型说着话、中间一步步调工具的时候，用户看得见它在
 * 干什么、干到哪了，而不是对着"正在思考"干等。连着好几步都没说话的，合在同一张清单里（见
 * ChatScreen 的 buildChatItems）。
 *
 * 不放进气泡：这是过程，不是对话。做成一行行浅色小字，和气泡里的正文一眼分得开。
 *
 * 状态看字，不另画标记：绝大多数调用都是顺利跑完的，每行前面挂个绿点只是噪音。
 * - 跑完的：浅色小字，什么都不加；
 * - 失败的：整行字变红；
 * - 正在执行的：一道天依蓝的流光从左到右扫过这行字（[shimmer]）；
 * - 没轮到的、被中断的：字再淡一档，被中断的后面跟个"已中断"。
 *
 * 执行完的点一下展开，看这一步返回了什么——模型点错了、读漏了，翻这里最快找到原因。
 *
 * ## 默认只露最新的一行
 *
 * 一个任务十几步，全摊开的话一屏都是清单，真正说的话被挤到看不见。所以一串调用默认只显示
 * 最新的那一行：新的一步进来，旧的那行往上滑走、淡出，新的从下面升上来，看得出"又往前走了
 * 一步"。
 *
 * 一串不止一步时，最新那一行的右边有个箭头和步数（[GroupToggle]），点了展开整串，箭头向右
 * 转成向下；再点收回去。只有这一个图标：每一行自己的结果照样能点开看，只是不再画尖角，免得
 * 一行里两个图标分不清哪个管什么。
 *
 * 展开时新的一步直接接在下面，不做滑动：整串都摊着，旧的那行本来就该留在原地。
 */
@Composable
internal fun ToolCallList(
    calls: List<ToolCallItem>,
    modifier: Modifier = Modifier,
    collapsible: Boolean = true,
) {
    if (calls.isEmpty()) return
    // 任务详情的模态窗里本来就是来看完整过程的，一行行全摊开，不折叠。
    if (!collapsible) {
        Column(modifier = modifier.fillMaxWidth()) {
            calls.forEach { item -> key(item.call.id) { ToolCallRow(item) } }
        }
        return
    }
    // 列表项滚出屏幕再滚回来也记得展没展开（LazyColumn 会按 key 帮每一项存着）。
    var expanded by rememberSaveable { mutableStateOf(false) }
    val latest = calls.last()
    val earlier = calls.dropLast(1)
    val toggle: (@Composable () -> Unit)? = if (earlier.isNotEmpty()) {
        { GroupToggle(count = calls.size, expanded = expanded, onToggle = { expanded = !expanded }) }
    } else {
        null
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 展开时，之前的那些从最新那一行的上面长出来；收起时往下缩回去。
        AnimatedVisibility(
            visible = expanded && earlier.isNotEmpty(),
            enter = expandVertically(GroupSpec, expandFrom = Alignment.Bottom) + fadeIn(GroupFadeSpec),
            exit = shrinkVertically(GroupSpec, shrinkTowards = Alignment.Bottom) + fadeOut(GroupFadeSpec),
        ) {
            Column {
                earlier.forEach { item -> key(item.call.id) { ToolCallRow(item) } }
            }
        }
        // 按调用 id 区分：同一步的状态变了（执行中 → 完成）只是原地更新，换了新的一步才滑。
        AnimatedContent(
            targetState = latest,
            contentKey = { it.call.id },
            transitionSpec = {
                if (expanded) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (slideInVertically(RowSlideSpec) { it } + fadeIn(RowFadeSpec)) togetherWith
                        (slideOutVertically(RowSlideSpec) { -it } + fadeOut(RowFadeSpec)) using
                        // 裁在这一行的高度里：滑走的旧行不该盖到上面的气泡上。
                        SizeTransform(clip = true)
                }
            },
            label = "latestToolCall",
        ) { item ->
            ToolCallRow(item, trailing = toggle)
        }
    }
}

/**
 * 展开 / 收起整串的开关：步数加一个箭头，收着时向右，展开时转 90° 向下。
 *
 * 按下垫一层淡天依蓝，松手才切换。和行本身的点击区域是并排的两块，不嵌套，点哪个就是哪个。
 */
@Composable
private fun GroupToggle(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnToggle by rememberUpdatedState(onToggle)
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = ArrowSpec,
        label = "toolGroupArrow",
    )
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (expanded) "已展开" else "已收起"
                onClick(label = if (expanded) "收起" else "展开全部 $count 步") {
                    currentOnToggle()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnToggle() } }
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) {
                    drawRoundRect(colors.sliderIdle, cornerRadius = CornerRadius(RowRadius.toPx()), alpha = pressed)
                }
            }
            .heightIn(min = RowMinHeight)
            .padding(horizontal = TogglePaddingHorizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "$count 步",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
            maxLines = 1,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier
                .size(ArrowSize)
                .graphicsLayer { rotationZ = rotation },
        )
    }
}

/**
 * 清单里的一行：一句人话（[describe]），字的颜色和流光表示状态（见 [ToolCallList]），被中断的
 * 后面再跟一个小标签。
 *
 * 有结果的行能点：按下垫一层淡天依蓝，松手才展开 / 收起这一步的结果（和下拉菜单的选项一样）。
 * 还没结果的行没什么可看的，不响应。
 *
 * @param trailing 行尾额外放的东西（整串的开关，见 [GroupToggle]）。放在能点的那块外面、和它并排，
 *   点它不会顺带展开这一行的结果。
 */
@Composable
private fun ToolCallRow(item: ToolCallItem, trailing: (@Composable () -> Unit)? = null) {
    val colors = KittenTheme.colors
    val result = item.result
    val failed = item.status == ToolCallStatus.Failed
    var expanded by remember { mutableStateOf(false) }
    val press = rememberGlassPressState()
    val shimmerProgress = rememberShimmerProgress(active = item.status == ToolCallStatus.Running)
    val textColor = item.status.textColor()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 能点的这一块占满除开关以外的整行，按下的底色也只垫在这一块上。
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (result != null) {
                            Modifier
                                .semantics(mergeDescendants = true) {
                                    role = Role.Button
                                    // 失败只靠红色表示，读屏的用户看不见颜色，这里用话说出来。
                                    stateDescription = (if (failed) "失败，" else "") +
                                        (if (expanded) "已展开" else "已收起")
                                    onClick(label = if (expanded) "收起结果" else "查看结果") {
                                        expanded = !expanded
                                        true
                                    }
                                }
                                .pointerInput(press) { detectGlassPress(press) { expanded = !expanded } }
                        } else {
                            Modifier
                        },
                    )
                    .drawBehind {
                        val pressed = press.pressProgress.coerceIn(0f, 1f)
                        if (pressed > 0f) {
                            drawRoundRect(colors.sliderIdle, cornerRadius = CornerRadius(RowRadius.toPx()), alpha = pressed)
                        }
                    }
                    .heightIn(min = RowMinHeight)
                    .padding(horizontal = RowPaddingHorizontal),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                Text(
                    text = item.call.describe(),
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .then(
                            if (shimmerProgress != null) {
                                Modifier.shimmer(progress = { shimmerProgress.value }, color = colors.accent)
                            } else {
                                Modifier
                            },
                        ),
                )
                if (item.status == ToolCallStatus.Stopped) StatusTag("已中断", colors.textTertiary)
            }
            trailing?.invoke()
        }
        AnimatedVisibility(
            visible = expanded && result != null,
            enter = expandVertically(spring(stiffness = 500f)) + fadeIn(spring(stiffness = 500f)),
            exit = shrinkVertically(spring(stiffness = 700f)) + fadeOut(spring(stiffness = 700f)),
        ) {
            // 结果多半是界面树这种一行一项的东西，等宽字对得齐。
            Text(
                text = result.orEmpty().trim(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = colors.textSecondary,
                maxLines = ResultMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    // 和这一行的字左边对齐。
                    .padding(start = ResultIndent, end = RowPaddingHorizontal, top = 2.dp, bottom = 6.dp)
                    .clip(ResultShape)
                    .background(colors.controlTint)
                    .padding(ResultPadding),
            )
        }
    }
}

/**
 * 流光：一道光带从左到右扫过这行字，只亮在字的笔画上，背后不动。
 *
 * 先照常画字，再在上面画一条两头透明、中间是 [color] 的竖条，用 SrcAtop 叠——只盖在已经画了东西
 * （字）的地方。要单独一层离屏画，SrcAtop 才只认这行字，不然会连背后的东西一起染了。
 *
 * [progress] 0..1：光带的中心从左边外面半个带宽走到右边外面半个带宽，进场出场都是完整的。只在
 * 绘制阶段读，扫的时候不重组。
 */
internal fun Modifier.shimmer(progress: () -> Float, color: Color): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val half = ShimmerBandWidth.toPx() / 2f
            val center = -half + (size.width + half * 2f) * progress()
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, color, Color.Transparent),
                    startX = center - half,
                    endX = center + half,
                ),
                blendMode = BlendMode.SrcAtop,
            )
        }

/**
 * 流光的进度（0..1，见 [shimmer]）。[active] 为 false 时返回 null、不起动画：同一时刻最多一行
 * 在执行，别的行不占时钟。清单的行和任务卡片上那一行共用。
 */
@Composable
internal fun rememberShimmerProgress(active: Boolean): State<Float>? =
    if (active) {
        rememberInfiniteTransition(label = "toolCallShimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            // 每扫完一遍歇一下再扫：一刻不停地扫，看着像在催。歇着的时候光带停在左边外面，看不见。
            animationSpec = infiniteRepeatable(
                tween(ShimmerSweepMillis, delayMillis = ShimmerPauseMillis, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "toolCallShimmerProgress",
        )
    } else {
        null
    }

/** 一行工具调用的字是什么颜色，见 [ToolCallList] 的类注释。清单的行和任务卡片上那一行共用。 */
@Composable
internal fun ToolCallStatus.textColor(): Color {
    val colors = KittenTheme.colors
    return when (this) {
        ToolCallStatus.Failed -> colors.danger
        ToolCallStatus.Pending, ToolCallStatus.Stopped -> colors.textTertiary
        ToolCallStatus.Running, ToolCallStatus.Done -> colors.textSecondary
    }
}

/** 行尾的小标签（"已中断"）。 */
@Composable
private fun StatusTag(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
    )
}

/**
 * 一行至少多高。比消息里的字（bodySmall，12sp）高出一截，好按；也不能太高，十几步的任务排下来
 * 一屏都是清单。
 */
private val RowMinHeight = 30.dp

/** 行的左右留白。左边这一截让字和气泡里的字大致对齐。 */
private val RowPaddingHorizontal = 8.dp

/** 字、标签、尖角之间的间距。 */
private val RowGap = 8.dp

/** 按下时那层底色的圆角，和卡片里的小控件同一档。 */
private val RowRadius = GlassRadius.Small

/**
 * 流光的光带多宽、扫一遍多久、两遍之间歇多久（毫秒）。光带比两三个字宽一点：太窄像一道细线
 * 闪过去，太宽整行一起变色，看不出"在流"。
 */
private val ShimmerBandWidth = 56.dp
private const val ShimmerSweepMillis = 1100
private const val ShimmerPauseMillis = 400

/** 整串开关上的箭头多大，转过去的弹簧。 */
private val ArrowSize = 16.dp
private val ArrowSpec = spring<Float>(dampingRatio = 0.8f, stiffness = 600f)

/** 整串开关左右的留白：按下时那层底色比"N 步 ›"宽一圈，也好按。 */
private val TogglePaddingHorizontal = 6.dp

/**
 * 收着时换一步的动画：旧的往上滑走、新的从下面升上来，滑的距离是一整行。
 *
 * 弹簧给得偏硬、几乎不回弹：一步接一步可能很快，软了会叠成一团。淡入淡出比位移快一点，旧的那
 * 行还没滑完就差不多看不见了，不会和新的那行挤在一起。
 *
 * 任务卡片上最下面那一行（见 [TaskCard]）也用这一套，两处换一步的动法一样。
 */
internal val RowSlideSpec = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)
internal val RowFadeSpec = spring<Float>(stiffness = 900f)

/** 展开 / 收起整串时之前那些行长出来、缩回去的弹簧。 */
private val GroupSpec = spring(
    dampingRatio = 0.9f,
    stiffness = 500f,
    visibilityThreshold = IntSize.VisibilityThreshold,
)
private val GroupFadeSpec = spring<Float>(stiffness = 700f)

/** 展开的结果往里缩多少，和这一行的字左边对齐。 */
private val ResultIndent = RowPaddingHorizontal

/**
 * 展开的结果最多显示几行。历史里存的结果本来就截到 400 字以内（见 ChatViewModel 的
 * MAX_STORED_TOOL_RESULT），这里再兜一道，免得一条结果占掉一屏。
 */
private const val ResultMaxLines = 12

private val ResultPadding = 10.dp
private val ResultShape = RoundedCornerShape(GlassRadius.Small)
