package me.wjz.kitten.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.data.session.SessionSummary
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContextMenu
import me.wjz.kitten.ui.component.GlassMenuItem
import me.wjz.kitten.ui.component.GlassOverlayHost
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassRim
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.glassEffects
import me.wjz.kitten.ui.component.rememberGlassContextMenuState
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.icon.Delete
import me.wjz.kitten.ui.icon.KittenIcons
import me.wjz.kitten.ui.theme.KittenTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 左侧抽屉：一整块液态玻璃，里面是历史会话列表。
 *
 * 只渲染 [SessionSummary]（标题 + 预览 + 时间），不碰消息本身——会话多起来以后，
 * 把每个会话的全部消息都读出来只为显示一行预览，纯属浪费。真正打开时才加载完整内容。
 *
 * ## 为什么不用 ModalDrawerSheet
 *
 * Material 的抽屉面板是一块不透明的 Surface，还会把里面的东西裁成它的形状——在上面画玻璃，
 * 背后什么都透不出来，阴影也被裁掉。所以面板自己画：折射 [backdrop]（整个界面，见
 * KittenApp），糊得很重，压一层实一点的底色，里面的字才不会和背后页面上的字打架。
 *
 * 面板的玻璃单独画在底下一层、录下来（surfaceBackdrop），给里面的玻璃按钮折射，和模态窗
 * 一样。
 *
 * ## 收起时不画
 *
 * 抽屉收起时停在屏幕外面，但还在渲染树里：玻璃会跟着背后的界面每一帧重新模糊、折射，
 * 白白耗电。所以按位置判断，滑出屏幕就不画（同光雾的判断，见 GlassMistNode）。
 *
 * ## 长按会话弹出菜单
 *
 * 删除这类不常用的操作收在长按菜单（[GlassContextMenu]）里，不在每一条上摆按钮。菜单要画在
 * 浮层上，可抽屉不在主界面的 GlassOverlayHost 里（ModalNavigationDrawer 把它画在整个界面
 * 上面），所以抽屉自己带一个 host，菜单就在抽屉里开、跟着抽屉走。整个列表共用一个菜单，记着
 * 长按的是哪一条。
 *
 * @param backdrop 抽屉背后的整个界面。
 */
@Composable
fun SessionDrawer(
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    onOpenSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onNewSession: () -> Unit,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    var onScreen by remember { mutableStateOf(false) }
    val menu = rememberGlassContextMenuState()
    // 长按的是哪一条。菜单收起后也留着：收起的动画还在放，菜单里的东西不能跟着变。
    var menuTarget by remember { mutableStateOf<String?>(null) }

    // 抽屉收走了（比如点了外面），菜单也收起，不然下次拉开抽屉它还开着。
    LaunchedEffect(onScreen) {
        if (!onScreen) menu.dismiss()
    }

    GlassOverlayHost(
        modifier = modifier
            .fillMaxHeight()
            .width(DrawerWidth)
            .semantics { paneTitle = "历史会话" }
            .onGloballyPositioned { onScreen = !it.boundsInWindow().isEmpty }
            .drawWithContent { if (onScreen) drawContent() },
    ) {
        DrawerPanel(
            sessions = sessions,
            currentSessionId = currentSessionId,
            menuTargetId = menuTarget.takeIf { menu.expanded },
            onOpenSession = onOpenSession,
            onLongPressSession = { id, positionInWindow ->
                menuTarget = id
                menu.show(positionInWindow)
            },
            onNewSession = onNewSession,
            backdrop = backdrop,
        )
        GlassContextMenu(
            state = menu,
            items = listOf(
                GlassMenuItem(text = "删除会话", icon = KittenIcons.Delete, destructive = true) {
                    menuTarget?.let(onDeleteSession)
                },
            ),
            backdrop = backdrop,
        )
    }
}

/**
 * 抽屉本体：玻璃面板 + 表头 + 会话列表。
 *
 * @param menuTargetId 长按菜单正开着的那一条，它一直亮着，看得出菜单是对哪一条的。
 */
@Composable
private fun DrawerPanel(
    sessions: List<SessionSummary>,
    currentSessionId: String?,
    menuTargetId: String?,
    onOpenSession: (String) -> Unit,
    onLongPressSession: (id: String, positionInWindow: Offset) -> Unit,
    onNewSession: () -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    val surfaceBackdrop = rememberLayerBackdrop()
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .layerBackdrop(surfaceBackdrop)
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { DrawerShape },
                    effects = { glassEffects(GlassRadius.Large, DrawerBlur) },
                    highlight = { GlassRim },
                    shadow = { DrawerShadow },
                    onDrawSurface = { drawRect(colors.glassTintStrong) },
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start))
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "历史会话",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                )
                GlassButton(
                    text = "新对话",
                    onClick = onNewSession,
                    backdrop = surfaceBackdrop,
                    style = GlassButtonStyle.Secondary,
                    compact = true,
                )
            }
            // 标题和列表之间一道细线，一眼分得开哪里是表头、哪里是会话。
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 8.dp)
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.hairline),
            )

            if (sessions.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "还没有历史会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textTertiary,
                    )
                }
                return@Column
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        isCurrent = session.id == currentSessionId,
                        isMenuTarget = session.id == menuTargetId,
                        onClick = { onOpenSession(session.id) },
                        onLongPress = { onLongPressSession(session.id, it) },
                    )
                }
            }
        }
    }
}

/**
 * 一条会话。当前打开的那条垫一层淡天依蓝；按下时同一层再叠一遍，手指压着哪一条一眼就能
 * 看出来（和下拉菜单的选项一样）。长按弹出菜单，菜单开着时这一条一直亮着。
 *
 * @param onLongPress 参数是手指的位置，窗口坐标：菜单从那里弹出来。
 */
@Composable
private fun SessionRow(
    session: SessionSummary,
    isCurrent: Boolean,
    isMenuTarget: Boolean,
    onClick: () -> Unit,
    onLongPress: (positionInWindow: Offset) -> Unit,
) {
    val colors = KittenTheme.colors
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)
    val press = rememberGlassPressState()
    // 这一条在窗口里的位置，把长按时手指的位置（这一条自己的坐标）换成窗口坐标。只在长按时
    // 读，滚动时更新它不会引起重组。
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = Rect(it.positionInWindow(), it.size.toSize()) }
            .semantics {
                role = Role.Button
                selected = isCurrent
                onClick {
                    currentOnClick()
                    true
                }
                // 读屏用户没法长按到某个点，菜单从这一条的正中间弹出来。
                onLongClick(label = "更多操作") {
                    currentOnLongPress(bounds.center)
                    true
                }
            }
            .pointerInput(press) {
                detectGlassPress(
                    state = press,
                    onLongPress = { currentOnLongPress(bounds.topLeft + it) },
                ) { currentOnClick() }
            }
            .drawBehind {
                val radius = CornerRadius(RowRadius.toPx())
                if (isCurrent) drawRoundRect(colors.sliderIdle, cornerRadius = radius)
                if (isMenuTarget) drawRoundRect(colors.sliderIdle, cornerRadius = radius)
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) drawRoundRect(colors.sliderIdle, cornerRadius = radius, alpha = pressed)
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.title.ifBlank { "未命名会话" },
                style = MaterialTheme.typography.bodyMedium,
                // 当前打开的那条高亮，否则一眼看不出自己在哪。
                color = if (isCurrent) colors.accentStrong else colors.textPrimary,
                fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            session.preview?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = "${formatTime(session.updatedAtMillis)} · ${session.messageCount} 条",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
            )
        }
    }
}

/**
 * 时间显示。
 *
 * 今天的只显示时分，更早的显示日期——会话列表里一串完整日期既占地方又难扫读。
 */
private fun formatTime(millis: Long): String {
    val now = System.currentTimeMillis()
    val pattern = if (now - millis < DAY_MILLIS) "HH:mm" else "MM-dd"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(millis))
}

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L

/** 抽屉宽度。比 Material 的上限（360dp）窄一些，右边多露出一截界面，看得出底下是什么。 */
private val DrawerWidth = 300.dp

/** 抽屉的形状：贴着屏幕左边，右边两个角圆，和底部栏同一档。 */
private val DrawerShape = RoundedCornerShape(topEnd = GlassRadius.Large, bottomEnd = GlassRadius.Large)

/**
 * 抽屉的模糊。比卡片重得多：背后压着一整页对话，糊轻了气泡里的字会透进来、和会话标题
 * 叠在一起抢着被读（同模态窗当初的理由）。
 */
private val DrawerBlur = 12.dp

/** 会话那一条高亮的圆角。 */
private val RowRadius = GlassRadius.Small

/** 抽屉浮在整页上面，阴影比卡片重。 */
private val DrawerShadow = Shadow(radius = 24.dp, color = Color.Black.copy(alpha = 0.16f))
