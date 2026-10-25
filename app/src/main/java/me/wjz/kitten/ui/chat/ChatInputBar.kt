package me.wjz.kitten.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSurface
import me.wjz.kitten.ui.component.LocalToast
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.component.rememberImageBitmap
import me.wjz.kitten.ui.icon.ArrowUpward
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.icon.Stop
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 底部输入栏：一块玻璃，分上下两层。
 *
 * - 上层是要发的内容：选了图片的话最上面一排缩略图（附件区，每张右上角一个"×"），下面是
 *   输入的文字。
 * - 下层是按钮：左边"+"，右边发送，发送左边是切换模型（[modelSwitcher]）。
 *
 * 用 [BasicTextField] 而不是 Material 的 `TextField`：后者自带一整套容器样式
 * （背景、下划线、浮动标签），放在玻璃上会多出一层不透明的底，把折射效果盖住。
 * 这里只需要光标和文字，容器交给 [GlassSurface]。
 *
 * 它浮在消息列表上面，[backdrop] 是"背景 + 消息列表"：气泡从它背后滚过时，边缘把气泡和字
 * 掰弯，液态玻璃才看得出来。见 [ChatScreen]。里面的两个按钮折射的是这块玻璃自己（surfaceBackdrop）。
 *
 * ## "+"
 *
 * 现在点了直接拉起系统的照片选择器，可以多选，选好的图片进附件区。以后东西多了（拍照、
 * 文件……）再换成先弹一个面板。系统照片选择器是 Android 13 起自带的，用它不用申请任何存储
 * 权限，拿到的只是用户挑中的那几张。
 *
 * 点附件区的缩略图看大图（[onOpenImage]）。只有图片、不打字也能发。
 *
 * ## 发送 / 停止
 *
 * 回复期间发送按钮换成停止（[responding]），点了喊停这一轮。换的时候图标上下滑：发出去，箭头往上
 * 飞走、方块从下面升上来；停下来反过来，方块往下沉、箭头从上面落回来（见 [sendStopTransition]）。
 *
 * 例外：Agent 在等用户回答、用户已经打了字时，按钮还是发送——打的字就是答案（见 ChatViewModel.send）。
 *
 * @param onSend 发送：去掉首尾空白的文字（可能是空串），和选好的图片（相册给的地址，复制、
 *   压缩交给 ViewModel）。
 * @param responding 模型正在回复（或者 Agent 正在干活）。
 * @param onStop 点了停止。
 * @param modelSwitcher 发送按钮左边放的东西，一般是 [ModelSwitcher]。参数是这块玻璃自己那层，
 *   里面的玻璃按钮该折射它，和"+"、发送一样。做成一个槽，输入栏就不用知道模型配置长什么样。
 */
@Composable
fun ChatInputBar(
    backdrop: Backdrop,
    enabled: Boolean,
    hint: String?,
    onSend: (text: String, images: List<Uri>) -> Unit,
    onOpenImage: (Uri) -> Unit,
    responding: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    modelSwitcher: @Composable (buttonBackdrop: Backdrop) -> Unit = {},
) {
    // 输入内容留在这一层而不是 ViewModel：它是纯粹的界面中间态，
    // 没发出去之前不属于业务数据。抬到 ViewModel 只会让每次按键都触发一轮状态更新。
    var text by remember { mutableStateOf("") }
    // 选好的图片，和输入的字一样是还没发出去的中间态。
    val attachments = remember { mutableStateListOf<Uri>() }
    val surfaceBackdrop = rememberLayerBackdrop()
    val toast = LocalToast.current
    val canSend = enabled && (text.isNotBlank() || attachments.isNotEmpty())
    // 回复期间是停止；在等用户回答而且已经打了字（canSend）时还是发送，见类注释。
    val showStop = responding && !canSend

    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxAttachments),
    ) { picked ->
        // 已经选过的不重复加；加起来超过上限的，后选的那几张不要。
        val merged = (attachments + picked).distinct()
        if (merged.size > MaxAttachments) toast.show("最多添加 $MaxAttachments 张图片")
        attachments.clear()
        attachments.addAll(merged.take(MaxAttachments))
    }

    fun submit() {
        if (!canSend) return
        onSend(text.trim(), attachments.toList())
        text = ""
        attachments.clear()
    }

    GlassSurface(
        backdrop = backdrop,
        cornerRadius = InputBarRadius,
        blurRadius = InputBarBlur,
        refraction = InputBarRefraction,
        exportedBackdrop = surfaceBackdrop,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column {
            if (attachments.isNotEmpty()) {
                AttachmentStrip(
                    uris = attachments,
                    onOpen = onOpenImage,
                    onRemove = { attachments.remove(it) },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            val textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = InputFontSize,
                lineHeight = InputLineHeight,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = hint ?: "说点什么…",
                        style = textStyle,
                        color = KittenTheme.colors.textTertiary,
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    enabled = enabled,
                    textStyle = textStyle.copy(color = KittenTheme.colors.textPrimary),
                    cursorBrush = SolidColor(KittenTheme.colors.accentStrong),
                    // 多行但有上限：长文本不该把整个屏幕顶掉。
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassIconButton(
                    icon = Icons.Filled.Add,
                    contentDescription = "添加图片",
                    onClick = {
                        pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    backdrop = surfaceBackdrop,
                    size = ActionButtonSize,
                )
                Spacer(modifier = Modifier.weight(1f))
                modelSwitcher(surfaceBackdrop)
                Spacer(modifier = Modifier.width(ModelSwitcherGap))
                GlassIconButton(
                    icon = if (showStop) KittenIcons.Stop else KittenIcons.ArrowUpward,
                    contentDescription = if (showStop) "停止" else "发送",
                    onClick = if (showStop) onStop else ::submit,
                    backdrop = surfaceBackdrop,
                    size = ActionButtonSize,
                    style = GlassButtonStyle.Primary,
                    enabled = showStop || canSend,
                    iconTransition = { sendStopTransition() },
                )
            }
        }
    }
}

/** 附件区：一排缩略图，多了左右滑。 */
@Composable
private fun AttachmentStrip(
    uris: List<Uri>,
    onOpen: (Uri) -> Unit,
    onRemove: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uris, key = { it.toString() }) { uri ->
            AttachmentThumbnail(uri = uri, onOpen = { onOpen(uri) }, onRemove = { onRemove(uri) })
        }
    }
}

/**
 * 一张已选图片的缩略图，右上角一个"×"。点缩略图看大图：按下就微微缩一下，松手才打开。
 *
 * "×"是一个深色小圆、不做玻璃（同下拉菜单里的"×"）：它压在照片上，照片什么颜色都有，深底
 * 白字压在什么上面都看得清。颜色借的是 Toast 那一对，同样是"压在什么上面都要看得清"。
 */
@Composable
private fun AttachmentThumbnail(uri: Uri, onOpen: () -> Unit, onRemove: () -> Unit) {
    val colors = KittenTheme.colors
    val sizePx = with(LocalDensity.current) { ThumbnailSize.roundToPx() }
    val thumbnail = rememberImageBitmap(uri, sizePx)
    val currentOnOpen by rememberUpdatedState(onOpen)
    val press = rememberGlassPressState()

    Box(modifier = Modifier.size(ThumbnailSize)) {
        // 还没解出来时先垫一块浅色，免得一片空白。
        Box(
            modifier = Modifier
                .matchParentSize()
                .semantics {
                    role = Role.Button
                    onClick(label = "查看大图") {
                        currentOnOpen()
                        true
                    }
                }
                .pointerInput(press) { detectGlassPress(press) { currentOnOpen() } }
                .graphicsLayer {
                    val s = lerp(1f, ThumbnailPressedScale, press.pressProgress)
                    scaleX = s
                    scaleY = s
                }
                .clip(ThumbnailShape)
                .background(colors.controlTint),
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail,
                    contentDescription = "已选的图片",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        // 触摸区比看得见的圆大一圈，好点。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(RemoveTouchSize)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onRemove,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(RemoveBadgeSize)
                    .clip(CircleShape)
                    .background(colors.toast),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "移除这张图片",
                    tint = colors.onToast,
                    modifier = Modifier.size(RemoveIconSize),
                )
            }
        }
    }
}

/**
 * 输入栏里字的大小和行高。两层的栏里文字单独占一层，字给大一点读起来舒服，和参考的
 * 那种输入框差不多。
 */
private val InputFontSize = 18.sp
private val InputLineHeight = 26.sp

/** 输入栏的圆角。栏有两层、比较高，圆角给大一些，看起来是一整块柔和的玻璃。 */
private val InputBarRadius = 26.dp

/** "+"和发送这两个圆有多大，和顶栏按钮一样。 */
private val ActionButtonSize = 40.dp

/**
 * 发送和停止互换时图标怎么动，方向跟着"消息往上发出去"走：换成停止时箭头往上飞走、方块从下面
 * 升上来；换回发送时方块往下沉、箭头从上面落回来。滑动的距离是整个圆的高度，图标裁在圆里，
 * 正好整个进出玻璃。顺带淡一点，图标碰到圆边被裁掉的那一下不那么硬。
 */
private fun AnimatedContentTransitionScope<ImageVector>.sendStopTransition(): ContentTransform {
    val toStop = targetState == KittenIcons.Stop
    val enter = slideInVertically(IconSlideSpec) { height -> if (toStop) height else -height } +
        fadeIn(IconFadeSpec)
    val exit = slideOutVertically(IconSlideSpec) { height -> if (toStop) -height else height } +
        fadeOut(IconFadeSpec)
    return enter togetherWith exit
}

/** 图标滑动的弹簧：略冲过头一点再落定，像被弹上去 / 落下来。dampingRatio 越小晃得越多。 */
private val IconSlideSpec = spring(
    dampingRatio = 0.75f,
    stiffness = 500f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)

/** 图标淡入淡出：比滑动快，滑到一半就已经看得清了。 */
private val IconFadeSpec = spring<Float>(stiffness = 900f)

/**
 * 切换模型的按钮和发送之间额外留的空。发送的触摸区每边比圆多出 (48 - 40) / 2 = 4dp，再加这 4dp，
 * 看起来隔着 8dp，不会贴在一起。
 */
private val ModelSwitcherGap = 4.dp

/** 附件区缩略图的大小和圆角。圆角和卡片里的小控件同一档。 */
private val ThumbnailSize = 72.dp
private val ThumbnailShape = RoundedCornerShape(GlassRadius.Medium)

/** 按下缩略图时缩到多小。 */
private const val ThumbnailPressedScale = 0.94f

/** 缩略图右上角"×"：看得见的小圆、里面的叉、触摸区。 */
private val RemoveBadgeSize = 22.dp
private val RemoveIconSize = 14.dp
private val RemoveTouchSize = 32.dp

/**
 * 一次最多带几张图片。两家接口都能一次收不少张，但图片要编码进请求里，张数多了请求会很大、
 * 很慢，先给一个够用的数。
 */
private const val MaxAttachments = 9

/**
 * 输入栏的模糊。比卡片（2dp）重一点、和底部栏一样：背后滚过的是一行行字，糊得太轻会和正在
 * 打的字抢着被读。
 */
private val InputBarBlur = 4.dp

/** 输入栏的折射量（折射带宽的倍数，卡片是 2）。背后的气泡要被掰弯得明显，玻璃才看得出来。 */
private const val InputBarRefraction = 2f
