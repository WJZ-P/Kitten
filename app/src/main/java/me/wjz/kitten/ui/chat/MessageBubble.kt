package me.wjz.kitten.ui.chat

import android.net.Uri
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
import androidx.compose.ui.draw.drawWithContent
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

/**
 * 一条消息。
 *
 * 用户的话和模型的回复都是带小尾巴的半透明气泡（[ChatBubble]，背后只模糊、不做液态玻璃），
 * 尾巴指向说话的那一边：
 * - 用户消息靠右，染成浅天依蓝，是"我说的话"；
 * - 模型回复靠左，薄薄一层底色，和背景融为一体。气泡里只有它说的话：调了哪些工具是**过程**，
 *   不是对话，排在气泡外面的清单里（[ToolCallList]）。
 *
 * 气泡里的内容一段一段往下排：用户消息里有图片的话，图片在上、字在下，见 [ChatBubble]。
 *
 * @param lifted 这一条的长按菜单正开着：气泡一直鼓着。
 * @param onLongPress 长按气泡，参数是手指的窗口坐标。为 null 时不响应长按（比如还在生成的回复）。
 * @param onOpenImage 点了气泡里的一张图片，看大图。
 * @param streaming 这是一条还在流式生成的回复：字一个个冒出来，见 [StreamingText]。
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
        is ChatMessage.Assistant -> AssistantBubble(message, backdrop, lifted, onLongPress, streaming, modifier)
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

@Composable
private fun AssistantBubble(
    message: ChatMessage.Assistant,
    backdrop: Backdrop,
    lifted: Boolean,
    onLongPress: ((Offset) -> Unit)?,
    streaming: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        ChatBubble(
            side = BubbleSide.Start,
            backdrop = backdrop,
            lifted = lifted,
            onLongPress = onLongPress,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            // 只画说的话。thinking 块（ChatMessage.Assistant.reasoning）不在界面上出现，调的工具在
            // 气泡外面的清单里。
            Column(modifier = Modifier.padding(BubbleTextPadding)) {
                message.text?.takeIf { it.isNotBlank() }?.let {
                    // 历史消息也走它，只是直接整段显示：生成完的回复换成正式消息时还是同一个
                    // 组件，没冒完的字接着冒，不会一下子全跳出来。
                    StreamingText(
                        text = it,
                        streaming = streaming,
                        style = MaterialTheme.typography.bodyMedium,
                        color = KittenTheme.colors.textPrimary,
                    )
                }
            }
        }
    }
}
