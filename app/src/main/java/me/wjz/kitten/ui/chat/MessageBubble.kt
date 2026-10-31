package me.wjz.kitten.ui.chat

import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.ImageAttachment
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.component.rememberImageBitmap
import me.wjz.kitten.ui.theme.KittenTheme
import java.io.File
import kotlin.math.max

/**
 * 一条消息。
 *
 * - 用户的话是靠右、带小尾巴、染成浅天依蓝的半透明气泡（[ChatBubble]，背后只模糊、不做液态玻璃），
 *   是"我说的话"。气泡里的内容一段一段往下排：有图片的话图片在上、字在下。
 * - 模型的回复不套气泡，字直接铺在页面上、占满整行（[AssistantMessage]），支持 Markdown。一来一回
 *   靠"有没有气泡"就分得清，回复里的列表、代码块、表格也有地方摆开。只有它说的话：调了哪些工具是
 *   **过程**，不是对话，排在外面的清单里（[ToolCallList]）。
 *
 * @param lifted 这一条的长按菜单正开着：用户气泡一直鼓着，模型的回复一直垫着底色。
 * @param onLongPress 长按这一条，参数是手指的窗口坐标。为 null 时不响应长按（比如还在生成的回复）。
 * @param onOpenImage 点了气泡里的一张图片，看大图。
 * @param streaming 这是一条还在流式生成的回复：字一个个冒出来，见 [ChatMarkdown]。
 */
@Composable
fun MessageBubble(
    message: ChatMessage,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: ((positionInWindow: Offset) -> Unit)?,
    onOpenImage: (Uri) -> Unit,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    when (message) {
        is ChatMessage.User -> UserBubble(message, backdrop, lifted, onLongPress, onOpenImage, modifier)
        is ChatMessage.Assistant -> AssistantMessage(message, lifted, onLongPress, streaming, modifier)
        // 工具结果不做成气泡：调了什么、结果如何，在工具清单里（见 ChatScreen 的 buildChatItems）。
        is ChatMessage.ToolResult -> Unit
    }
}

@Composable
private fun UserBubble(
    message: ChatMessage.User,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: ((Offset) -> Unit)?,
    onOpenImage: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        ChatBubble(
            side = BubbleSide.End,
            backdrop = backdrop,
            tinted = true,
            lifted = lifted,
            onLongPress = onLongPress,
            // 不占满整行，留出左边的空白，视觉上才有"从右边来"的感觉。
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            if (message.images.isNotEmpty()) {
                BubbleImages(images = message.images, onOpen = onOpenImage, onLongPress = onLongPress)
            }
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = KittenTheme.colors.onAccent,
                    modifier = Modifier.padding(BubbleTextPadding),
                )
            }
        }
    }
}

/**
 * 气泡里的图片：四周和气泡的边留 [ImagesInset]，自己带圆角（[ImagesShape]，比气泡的圆角小一圈，
 * 两圈边同心）。
 *
 * - 一张：按原始宽高比显示，宽 [ImagesWidth]；太长、太扁的截到 [MinAspect]..[MaxAspect] 之间，
 *   居中裁切，免得一张长截图把气泡撑得一屏都装不下。
 * - 好几张：方格，两张、四张一行两格，其他一行三格。整块方格是圆角，里面的格子是直角。
 *
 * 点一张看大图；按在图片上长按和长按气泡一样弹菜单（图片先接住了按下，气泡那边收不到）。
 */
@Composable
private fun BubbleImages(
    images: List<ImageAttachment>,
    onOpen: (Uri) -> Unit,
    onLongPress: ((Offset) -> Unit)?,
) {
    if (images.size == 1) {
        val image = images.first()
        val aspect = (image.width.toFloat() / image.height.coerceAtLeast(1)).coerceIn(MinAspect, MaxAspect)
        BubbleImage(
            image = image,
            onOpen = onOpen,
            onLongPress = onLongPress,
            modifier = Modifier
                .padding(ImagesInset)
                .width(ImagesWidth)
                .aspectRatio(aspect)
                .clip(ImagesShape),
        )
        return
    }
    val columns = if (images.size == 2 || images.size == 4) 2 else 3
    val cell = (ImagesWidth - ImagesGap * (columns - 1)) / columns
    Column(
        modifier = Modifier
            .padding(ImagesInset)
            .width(ImagesWidth)
            .clip(ImagesShape),
        verticalArrangement = Arrangement.spacedBy(ImagesGap),
    ) {
        images.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ImagesGap)) {
                row.forEach { image ->
                    BubbleImage(image = image, onOpen = onOpen, onLongPress = onLongPress, modifier = Modifier.size(cell))
                }
            }
        }
    }
}

/** 气泡里的一张图片。按下压暗一点，松手才打开大图。 */
@Composable
private fun BubbleImage(
    image: ImageAttachment,
    onOpen: (Uri) -> Unit,
    onLongPress: ((Offset) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    val uri = remember(image.path) { Uri.fromFile(File(image.path)) }
    val currentOnOpen by rememberUpdatedState(onOpen)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val press = rememberGlassPressState()
    // 这一格在窗口里的位置，把长按时手指的位置换成窗口坐标。只在长按时读。
    var bounds by remember { mutableStateOf(Rect.Zero) }

    BoxWithConstraints(
        modifier = modifier
            .onGloballyPositioned { bounds = Rect(it.positionInWindow(), it.size.toSize()) }
            .semantics {
                role = Role.Button
                contentDescription = "图片"
                onClick(label = "查看大图") {
                    currentOnOpen(uri)
                    true
                }
                if (onLongPress != null) {
                    onLongClick(label = "更多操作") {
                        currentOnLongPress?.invoke(bounds.center)
                        true
                    }
                }
            }
            .pointerInput(press) {
                detectGlassPress(
                    state = press,
                    onLongPress = { currentOnLongPress?.invoke(bounds.topLeft + it) },
                ) { currentOnOpen(uri) }
            }
            // 还没解出来时先垫一块浅色。
            .background(colors.controlTint)
            .drawWithContent {
                drawContent()
                drawRect(colors.viewerScrim, alpha = press.pressProgress.coerceIn(0f, 1f) * PressedDim)
            },
    ) {
        // 按这一格的大小解码，长边对齐。
        val decodePx = with(LocalDensity.current) { maxOf(maxWidth, maxHeight).roundToPx() }
        val bitmap = rememberImageBitmap(uri, decodePx)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 气泡里图片区域的宽度：一张图的宽，也是方格整体的宽。 */
private val ImagesWidth = 220.dp

/** 方格之间的缝。 */
private val ImagesGap = 2.dp

/** 图片和气泡四边之间留多少。 */
private val ImagesInset = 4.dp

/** 图片的圆角：气泡的圆角（16dp）减去留白，两圈边才同心。 */
private val ImagesShape = RoundedCornerShape(GlassRadius.Large - ImagesInset)

/** 一张图时宽高比最多截到多扁、多长（宽 / 高）。 */
private const val MinAspect = 0.5f
private const val MaxAspect = 2f

/** 按下图片时压暗多少（用看大图那层深色）。 */
private const val PressedDim = 0.35f

/**
 * 模型的一段回复：不套气泡，Markdown 直接排在页面上（[ChatMarkdown]）。
 *
 * 字往里缩 [ReplyInset]，和工具清单那一行的字左边对齐，一路看下来是一条线。
 *
 * ## 长按
 *
 * 和气泡一样能长按弹菜单（复制、删除）。没有气泡可以鼓，换成垫一层淡天依蓝：按下就浮出来，滑走或者
 * 页面开始滚就退掉；菜单开着时（[lifted]）一直垫着，看得出菜单是对哪一条的。点一下这里不管（点在链接上
 * 由链接自己处理）。底色铺满这一条，比字往外多出 [ReplyInset]，不贴着字。
 */
@Composable
private fun AssistantMessage(
    message: ChatMessage.Assistant,
    lifted: Boolean,
    onLongPress: ((Offset) -> Unit)?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    // 只画说的话。thinking 块（ChatMessage.Assistant.reasoning）不在界面上出现，调的工具在外面的清单里。
    val text = message.text?.takeIf { it.isNotBlank() } ?: return
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    // 这一条在窗口里的位置，把长按时手指的位置换成窗口坐标。只在长按时读，滚动时更新它不会引起重组。
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = ReplyLiftSpec,
        label = "replyLift",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onLongPress != null) {
                    Modifier
                        .onGloballyPositioned { bounds = Rect(it.positionInWindow(), it.size.toSize()) }
                        .semantics {
                            // 读屏用户没法长按到某个点，菜单从这一条的正中间弹出来。
                            onLongClick(label = "更多操作") {
                                currentOnLongPress?.invoke(bounds.center)
                                true
                            }
                        }
                        .pointerInput(press) {
                            detectGlassPress(
                                state = press,
                                onLongPress = { currentOnLongPress?.invoke(bounds.topLeft + it) },
                                onClick = {},
                            )
                        }
                } else {
                    Modifier
                },
            )
            .drawBehind {
                val shown = max(press.pressProgress, lift).coerceIn(0f, 1f)
                if (shown > 0f) {
                    drawRoundRect(
                        color = colors.sliderIdle,
                        cornerRadius = CornerRadius(ReplyHighlightRadius.toPx()),
                        alpha = shown,
                    )
                }
            }
            .padding(horizontal = ReplyInset, vertical = ReplyPaddingVertical),
    ) {
        // 历史消息也走它，只是直接整段显示：生成完的回复换成正式消息时还是同一个组件，没冒完的字
        // 接着冒，不会一下子全跳出来。
        ChatMarkdown(text = text, streaming = streaming)
    }
}

/**
 * 回复的字往里缩多少：和工具清单那一行的字左边对齐（清单行的左右留白是 8dp）。长按时垫的底色比字
 * 往外多出这一截。
 */
private val ReplyInset = 8.dp

/** 回复上下留多少：长按垫底色时，底色不贴着第一行、最后一行的字。 */
private val ReplyPaddingVertical = 6.dp

/** 长按时垫的那层底色的圆角，和以前的气泡同一档。 */
private val ReplyHighlightRadius = GlassRadius.Large

/** 菜单开、关时底色浮出、退掉的弹簧。和气泡鼓起、收回一样快，但不回弹：底色深浅晃一下只会像闪了一下。 */
private val ReplyLiftSpec = spring<Float>(dampingRatio = 1f, stiffness = 500f)
