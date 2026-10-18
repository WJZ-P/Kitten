package me.wjz.kitten.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 应用内的浮层：下拉菜单这类要盖在整个界面上面、又得是真玻璃的东西，画在这里。
 *
 * ## 为什么不用 Popup
 *
 * Compose 的 `Popup`（`DropdownMenu` 也是基于它）会另开一个窗口。玻璃要读的 backdrop
 * 录在主窗口的渲染树里，另一个窗口既拿不到它，也算不出两边的相对位置——弹出来只能是
 * 一块不透明的板子。
 *
 * 所以在主窗口里留一层：[GlassOverlayHost] 包住整个界面，挂上来的浮层画在界面的最上面。
 * 它和页面一样是背景的兄弟（见 [GlassBackground]），玻璃照样能折射背景。
 *
 * ## 浮层透得出底下的界面
 *
 * 光折射背景还不够：菜单底下压着卡片、按钮，它们不在背景层里，菜单看不见，透出来的只有
 * 背景本身，像是在界面上挖了个洞。所以浮层挂着的时候，host 把整个界面也录一份
 * （[GlassOverlayHostState.contentBackdrop]），浮层叠着"背景 + 界面"一起折射。
 *
 * 平时不录：录制是在 UI 线程上把界面的绘制指令再记一遍，没有浮层要看的时候是白花。
 * 浮层不在录制范围里（它和界面是兄弟），所以不会成环。
 */
@Composable
fun GlassOverlayHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val contentBackdrop = rememberLayerBackdrop()
    val state = remember(contentBackdrop) { GlassOverlayHostState(contentBackdrop) }
    Box(modifier = modifier.onGloballyPositioned { state.originInWindow = it.positionInWindow() }) {
        Box(
            modifier = if (state.entries.isNotEmpty()) Modifier.layerBackdrop(contentBackdrop) else Modifier,
        ) {
            CompositionLocalProvider(LocalGlassOverlay provides state) {
                content()
            }
        }
        // 后挂上来的画在上面。
        state.entries.forEach { entry ->
            key(entry) { entry.content(state) }
        }
    }
}

@Stable
internal class GlassOverlayHostState(
    /** 挂着浮层时录下来的整个界面。浮层把它和背景叠起来当自己的 backdrop。 */
    val contentBackdrop: LayerBackdrop,
) {
    val entries = mutableStateListOf<GlassOverlayEntry>()

    /** host 左上角在窗口里的位置。浮层拿它把窗口坐标换算成 host 里的坐标。 */
    var originInWindow by mutableStateOf(Offset.Zero)
}

internal class GlassOverlayEntry(val content: @Composable (GlassOverlayHostState) -> Unit)

private val LocalGlassOverlay = staticCompositionLocalOf<GlassOverlayHostState?> { null }

/**
 * 在 [visible] 期间把 [content] 挂到最近的 [GlassOverlayHost] 上。
 *
 * [content] 铺满整个 host，位置由它自己算：host 在窗口里的位置见
 * [GlassOverlayHostState.originInWindow]。
 */
@Composable
internal fun GlassOverlay(
    visible: Boolean,
    content: @Composable (GlassOverlayHostState) -> Unit,
) {
    val host = checkNotNull(LocalGlassOverlay.current) {
        "GlassOverlay 必须放在 GlassOverlayHost 里面"
    }
    // entry 只建一次；每次重组换上来的新 content 通过这个 State 传过去，host 那边跟着重组。
    val currentContent by rememberUpdatedState(content)
    val entry = remember { GlassOverlayEntry { currentContent(it) } }
    if (visible) {
        DisposableEffect(host, entry) {
            host.entries += entry
            onDispose { host.entries -= entry }
        }
    }
}
