package me.wjz.kitten.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.chat.ChatScreen
import me.wjz.kitten.ui.chat.ChatViewModel
import me.wjz.kitten.ui.chat.SessionDrawer
import me.wjz.kitten.ui.glass.GlassBackground
import me.wjz.kitten.ui.settings.SettingsScreen
import me.wjz.kitten.ui.setup.SetupActions
import me.wjz.kitten.ui.task.TaskScreen

/**
 * App 的根容器：底部 tab + 左侧会话抽屉。
 *
 * ## 为什么不用 navigation-compose
 *
 * 三个平级 tab，没有返回栈、没有参数传递、没有深链需求。用 [rememberSaveable] 记住
 * 当前 tab 就够了，引一套导航框架只会多一层间接。等真要做"会话详情"这类深层页面时
 * 再引也不迟——那时候才用得上它的返回栈。
 *
 * ## backdrop 为什么建在这里
 *
 * 它是连接"背景"和"玻璃"的句柄，必须在两者**共同的父级**创建。所有页面的玻璃卡片
 * 都折射同一块背景，所以这里是唯一合适的位置。
 */
@Composable
fun KittenApp(actions: SetupActions) {
    val chatViewModel: ChatViewModel = viewModel()
    val chatState by chatViewModel.uiState.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableStateOf(KittenTab.Chat) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val backdrop = rememberLayerBackdrop()

    // 错误用 snackbar 提示完就清掉，否则切走再回来会重复弹。
    LaunchedEffect(chatState.error) {
        chatState.error?.let {
            snackbarHostState.showSnackbar(it)
            chatViewModel.consumeError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // 抽屉只在对话页有意义，别的 tab 下左滑不该拉出会话列表。
        gesturesEnabled = currentTab == KittenTab.Chat,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = DrawerBackground) {
                SessionDrawer(
                    sessions = chatState.sessions,
                    currentSessionId = chatState.sessionId,
                    onOpenSession = {
                        chatViewModel.openSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSession = chatViewModel::deleteSession,
                    onNewSession = {
                        chatViewModel.startNewSession()
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        GlassBackground(backdrop = backdrop) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // 内容避开状态栏和导航栏，但背景依然铺满整屏——这是沉浸式的做法。
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                KittenTopBar(
                    title = when (currentTab) {
                        KittenTab.Chat -> chatState.title.ifBlank { "新对话" }
                        KittenTab.Task -> "任务"
                        KittenTab.Settings -> "设置"
                    },
                    // 两个按钮只在对话页出现：任务页和设置页既没有历史会话也没有"新建"。
                    showSessionActions = currentTab == KittenTab.Chat,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewSession = chatViewModel::startNewSession,
                )

                Box(modifier = Modifier.weight(1f)) {
                    when (currentTab) {
                        KittenTab.Chat -> ChatScreen(
                            state = chatState,
                            backdrop = backdrop,
                            onSend = chatViewModel::send,
                            onOpenSettings = { currentTab = KittenTab.Settings },
                        )

                        KittenTab.Task -> TaskScreen()

                        KittenTab.Settings -> SettingsScreen(
                            backdrop = backdrop,
                            actions = actions,
                            onModelConfigChanged = chatViewModel::refreshModelConfig,
                        )
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp),
                    )
                }

                KittenBottomBar(
                    backdrop = backdrop,
                    current = currentTab,
                    onSelect = { currentTab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private val DrawerBackground = Color(0xFF0B1620)
