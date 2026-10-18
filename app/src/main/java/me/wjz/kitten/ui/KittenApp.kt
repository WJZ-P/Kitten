package me.wjz.kitten.ui

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.chat.ChatScreen
import me.wjz.kitten.ui.chat.ChatViewModel
import me.wjz.kitten.ui.chat.SessionDrawer
import me.wjz.kitten.ui.component.GlassBackground
import me.wjz.kitten.ui.component.GlassOverlayHost
import me.wjz.kitten.ui.settings.SettingsScreen
import me.wjz.kitten.ui.setup.SetupActions
import me.wjz.kitten.ui.task.TaskScreen
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.abs

/**
 * App 的根容器：底部 tab + 左侧会话抽屉。
 *
 * ## 为什么不用 navigation-compose
 *
 * 三个平级 tab，没有返回栈、没有参数传递、没有深链需求。用 [rememberSaveable] 记住
 * 当前 tab 就够了，引一套导航框架只会多一层间接。等真要做"会话详情"这类深层页面时
 * 再引也不迟——那时候才用得上它的返回栈。
 *
 * ## 三个页面装在 HorizontalPager 里
 *
 * 以前是 `when (currentTab)` 直接换页面：切走的页面被整个丢掉，切回来再重新组合。
 * 设置页一组合就是十几块玻璃加上读配置、读权限状态，每次切过去都卡一下。
 *
 * 现在三页放进 [HorizontalPager]，`beyondViewportPageCount` 让三页始终组合着，切换只是
 * 把它们横向滑过来，自带左滑 / 右滑的过渡。代价是三页的副作用都会在启动时就跑起来——
 * 比如设置页回到前台时的自动配置和自动连接 ADB，以前要等用户点进设置页才会触发。
 *
 * 不接受手指左右滑（`userScrollEnabled = false`）：对话页的左缘右滑是拉出历史会话的
 * 抽屉，设置页里还有开关、滑条、横向滚动的 chip，都要抢横向手势。页面能被手指拖动的话，
 * 这几处都得逐个协调谁先谁后，眼下收益抵不上复杂度。真要开，改这一个参数即可。
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
    val tabs = KittenTab.entries
    val pagerState = rememberPagerState(initialPage = currentTab.ordinal) { tabs.size }
    val focusManager = LocalFocusManager.current

    // 选中项一变就滑过去。从对话直接切到设置会滑过中间的任务页，方向和距离都和底部
    // 滑块一致，用户能看出自己是往哪边走的。
    LaunchedEffect(currentTab) {
        // 三页常驻意味着离开的那页还活着：上面的输入框要是还聚焦着，键盘会一直开着。
        focusManager.clearFocus()
        pagerState.animateScrollToPage(currentTab.ordinal, animationSpec = PageSlideSpec)
    }

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
            ModalDrawerSheet(drawerContainerColor = KittenTheme.colors.drawer) {
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
        GlassBackground(
            backdrop = backdrop,
            // 设置页满屏都是玻璃卡片，背景几乎露不出来，动了也看不见；而玻璃越多，背景每推进
            // 一次要重算的就越多（实测设置页是对话页的一倍半）。所以只在对话页和任务页动。
            animate = currentTab != KittenTab.Settings,
        ) {
            // 下拉菜单这类浮层画在这一层上，盖住整个界面（包括顶栏和底部栏）。它必须在
            // GlassBackground 里面、和背景是兄弟，浮层的玻璃才折射得到背景。见 GlassOverlayHost。
            GlassOverlayHost(modifier = Modifier.fillMaxSize()) {
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
                        icon = currentTab.icon,
                        // 两个按钮只在对话页出现：任务页和设置页既没有历史会话也没有"新建"。
                        showSessionActions = currentTab == KittenTab.Chat,
                        onOpenDrawer = { scope.launch { drawerState.open() } },
                        onNewSession = chatViewModel::startNewSession,
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                            // 三页全部常驻：不预先组合的话，第一次切到设置页要现场组合十几块玻璃、
                            // 读配置和权限状态，会卡一下。
                            beyondViewportPageCount = tabs.size - 1,
                            // 只跟着底部 tab 走，不接手指左右滑。原因见类注释。
                            userScrollEnabled = false,
                        ) { page ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    // 预加载的页面只组合、不绘制。不画的话它们的 RenderNode 不在渲染树里，
                                    // 里面的玻璃就不会跟着背景一起重新模糊、折射——实测停在对话页时，背景每
                                    // 推进一次的 RenderThread 耗时从 20ms 降到 13ms。滑动切换的途中两页都露出来，
                                    // 所以按"离视口还差多少"判断，而不是只画当前页。
                                    .drawWithContent {
                                        val distance = (pagerState.currentPage - page) +
                                            pagerState.currentPageOffsetFraction
                                        if (abs(distance) < 1f) drawContent()
                                    },
                            ) {
                                when (tabs[page]) {
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
                            }
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
}

/** 页面滑动：临界阻尼，不回弹。和底部滑块的位置弹簧同一种性格，两者一起动时不会各走各的。 */
private val PageSlideSpec = spring<Float>(dampingRatio = 1f, stiffness = 400f)
