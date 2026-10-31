package me.wjz.kitten.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.R
import me.wjz.kitten.agent.SystemPrompt
import me.wjz.kitten.data.memory.MemoryStore
import me.wjz.kitten.llm.LlmConfigStore
import me.wjz.kitten.llm.LlmProfile
import me.wjz.kitten.llm.LlmProfiles
import me.wjz.kitten.permission.PermissionStatus
import me.wjz.kitten.permission.SelfConfigurator
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.client.WirelessDebugController
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassChip
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDivider
import me.wjz.kitten.ui.component.GlassIconTile
import me.wjz.kitten.ui.component.GlassListItem
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassSlider
import me.wjz.kitten.ui.component.GlassSwitch
import me.wjz.kitten.ui.component.LocalToast
import me.wjz.kitten.ui.component.ToastTone
import me.wjz.kitten.ui.setup.DebugCard
import me.wjz.kitten.ui.setup.PrivilegeSection
import me.wjz.kitten.ui.setup.SetupActions
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.roundToInt

/** 设置里的几页：首页，和从首页点进去的二级页。[title] 是页面顶上的标题。 */
private enum class SettingsPage(val title: String) {
    Root("设置"),
    Models("模型服务商"),
    Tokens("Token 看板"),
    Memory("记忆"),
    Privilege("增强模式"),
    Developer("开发者调试"),
}

/**
 * 设置页。
 *
 * ## 首页只放入口
 *
 * 首页是一串分组列表（模型 / 个性与记忆 / 权限与能力 / 其他），一行一个入口，右边写着现在的状态
 * （用的哪家模型、记了几件事、权限开没开）。具体内容点进去才看到：
 *
 * - 列表类的东西（服务商、Token 看板、记忆、增强模式、开发者调试）是二级页，从右边滑进来，左上角
 *   返回，系统返回手势也能回去。
 * - 只改一样东西的（改人设、编辑某一家服务商）还是模态窗。
 * - 权限没开时那一行写着"去开启"，点了直接跳系统设置；开了就只写"已开启"。
 *
 * 以前是一张大卡片一个功能、全摊在一页上，一屏看不全，也分不清哪些常用。
 *
 * ## 二级页怎么切
 *
 * 三个 tab 平级、没有返回栈（见 KittenApp），设置里多出来的这一层不值得为它引一套导航框架：
 * 当前在哪一页存在 [rememberSaveable] 里，用 AnimatedContent 换页。首页的滚动位置提到外面存着，
 * 去二级页再回来还停在原处。
 *
 * 权限状态每次回到前台都要重读：用户是去系统设置里开关的，App 这边收不到回调。
 *
 * @param contentPadding 滚动内容四周额外留的空白。底部栏浮在页面上面，靠它让最后一张卡片
 *   能滚到栏的上方。
 * @param isCurrentTab 设置是不是当前的 tab。三页常驻（见 KittenApp），不是当前 tab 时返回手势不能
 *   被设置的二级页截走。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    actions: SetupActions,
    onModelConfigChanged: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    isCurrentTab: Boolean = true,
) {
    val context = LocalContext.current
    val controller = remember(context) { WirelessDebugController.getInstance(context) }
    var status by remember { mutableStateOf(PermissionStatus.read(context)) }
    // 已保存的配置，和模态窗里正在改的草稿分开：不保存就关掉，草稿直接丢掉。
    // 别处改了配置（比如对话页自动补了 /v1）时重读：设置页一直活着，不重读的话手里那份是旧的，
    // 下次保存会把别处的改动盖回去。
    val revision by LlmConfigStore.revision.collectAsState()
    var profiles by remember(revision) { mutableStateOf(LlmConfigStore.loadProfiles(context)) }
    var draft by remember { mutableStateOf(LlmProfile.create()) }
    // 草稿是新加的一家还是在改已有的：决定保存时是追加还是替换，以及有没有删除按钮。
    var draftIsNew by remember { mutableStateOf(true) }
    var editingModel by remember { mutableStateOf(false) }
    // 系统提示词里用户能改的人设：存着的（没改过是 null）和模态窗里的草稿分开，同上。
    var customPrompt by remember { mutableStateOf(SystemPrompt.loadCustomPersona(context)) }
    var promptDraft by remember { mutableStateOf("") }
    var editingPrompt by remember { mutableStateOf(false) }
    // 记忆：和对话页共用一个 MemoryStore，模型刚记下一条这里就多一行。
    val memoryStore = remember(context) { MemoryStore.get(context) }
    val memories by remember(memoryStore) { memoryStore.observe() }.collectAsState(initial = emptyList())
    val privilegeState by PrivilegeManager.state.collectAsState()
    val allTimeTokens = rememberAllTimeTokens()
    val scope = rememberCoroutineScope()
    val toast = LocalToast.current

    var page by rememberSaveable { mutableStateOf(SettingsPage.Root) }
    val rootScroll = rememberScrollState()
    BackHandler(enabled = isCurrentTab && page != SettingsPage.Root) { page = SettingsPage.Root }

    // 存下来，并通知对话页：它的"模型是否已配置"是启动时读的，要主动让它重读。
    fun commit(next: LlmProfiles) {
        profiles = next
        LlmConfigStore.saveProfiles(context, next)
        onModelConfigChanged()
    }

    LifecycleResumeEffect(context) {
        // 拿到 WRITE_SECURE_SETTINGS 之后，无障碍和通知使用权由 Kitten 自己开。
        // 放在读取状态之前，这样下面读到的就是刚配好的结果。
        SelfConfigurator.applyAll(context)
        status = PermissionStatus.read(context)
        // 配对过之后就不该再让用户点按钮连接了。放在"回到前台"而不是"首次进入"，
        // 是因为重启后无线调试默认关着：用户去设置里打开再切回来，这里正好接上。
        controller.tryAutoConnect()
        onPauseOrDispose { }
    }

    AnimatedContent(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            // 往里走：新的一页从右边整页滑进来，盖在上面；首页往左挪一小截、淡下去。往回走反过来。
            val forward = targetState != SettingsPage.Root
            val enter = slideInHorizontally(PageSlideSpec) { if (forward) it else -it / 4 } + fadeIn(PageFadeSpec)
            val exit = slideOutHorizontally(PageSlideSpec) { if (forward) -it / 4 else it } + fadeOut(PageFadeSpec)
            (enter togetherWith exit).apply { targetContentZIndex = if (forward) 1f else -1f }
        },
        label = "settingsPage",
    ) { current ->
        val scroll = if (current == SettingsPage.Root) rootScroll else rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // 留白放在滚动内容里面：内容能一直滚到底部栏后面，滚到底时最后一张卡片又能完整露出来。
                .padding(contentPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsPageHeader(
                title = current.title,
                backdrop = backdrop,
                onBack = if (current == SettingsPage.Root) null else ({ page = SettingsPage.Root }),
            )
            when (current) {
                SettingsPage.Root -> SettingsRoot(
                    backdrop = backdrop,
                    profiles = profiles,
                    allTimeTokens = allTimeTokens,
                    customPrompt = customPrompt,
                    memoryCount = memories.size,
                    status = status,
                    privilegeState = privilegeState,
                    onOpen = { page = it },
                    onEditPersona = {
                        // 打开时拷一份当前在用的：没改过就是默认人设，用户看得到原本写了什么再改。
                        promptDraft = customPrompt ?: SystemPrompt.DEFAULT_PERSONA
                        editingPrompt = true
                    },
                    actions = actions,
                )
                SettingsPage.Models -> ModelProfilesSection(
                    backdrop = backdrop,
                    profiles = profiles,
                    onSelectModel = { profile, model ->
                        commit(
                            profiles.copy(
                                profiles = profiles.profiles.map { if (it.id == profile.id) it.copy(model = model) else it },
                            ),
                        )
                    },
                    onActivate = { commit(profiles.copy(activeId = it.id)) },
                    onEdit = {
                        draft = it
                        draftIsNew = false
                        editingModel = true
                    },
                    onAdd = {
                        draft = LlmProfile.create()
                        draftIsNew = true
                        editingModel = true
                    },
                )
                SettingsPage.Tokens -> TokenUsageSection(backdrop = backdrop)
                SettingsPage.Memory -> MemoryPage(
                    memories = memories,
                    onAdd = { content ->
                        scope.launch {
                            if (memoryStore.add(content) == null) {
                                toast.show("记满 ${MemoryStore.MAX_ENTRIES} 条啦，先删掉几条旧的吧～", tone = ToastTone.Error)
                            }
                        }
                    },
                    onUpdate = { id, content -> scope.launch { memoryStore.update(id, content) } },
                    onDelete = { id ->
                        scope.launch { memoryStore.delete(id) }
                        toast.show("忘掉啦～")
                    },
                    backdrop = backdrop,
                )
                SettingsPage.Privilege -> PrivilegeSection(backdrop = backdrop, onStartPairing = actions::startPairing)
                SettingsPage.Developer -> {
                    // 临时：看玻璃组件的手感，定下来之后换成真的设置项。
                    ComponentPreviewSection(backdrop = backdrop)
                    // 临时：验证副屏可用性，验完连同 DebugCard.kt 一起删。
                    DebugCard()
                    // 临时：调 Toast 的样子，调完连同 ToastDebugSection.kt 一起删。
                    ToastDebugSection(backdrop = backdrop)
                }
            }
        }
    }

    // 模态窗画在浮层上，放在哪里都一样，不占页面的位置。
    ModelConfigDialog(
        visible = editingModel,
        draft = draft,
        isNew = draftIsNew,
        onDraftChange = { draft = it },
        onSave = {
            val list = if (draftIsNew) {
                profiles.profiles + draft
            } else {
                profiles.profiles.map { if (it.id == draft.id) draft else it }
            }
            // 第一家配好的服务直接设为当前，不用用户再点一次"使用"。
            commit(LlmProfiles(list, profiles.activeId ?: draft.id))
            editingModel = false
        },
        onDelete = {
            val list = profiles.profiles.filterNot { it.id == draft.id }
            // 删的是正在用的那一家：换成剩下的第一家，一家都不剩就没有当前。
            val active = if (profiles.activeId == draft.id) list.firstOrNull()?.id else profiles.activeId
            commit(LlmProfiles(list, active))
            editingModel = false
        },
        onDismiss = { editingModel = false },
        backdrop = backdrop,
    )

    SystemPromptDialog(
        visible = editingPrompt,
        draft = promptDraft,
        onDraftChange = { promptDraft = it },
        onSave = {
            SystemPrompt.savePersona(context, promptDraft)
            // 存的时候会把"和默认的一样""清空了"都归成没改过，从存储里读回来才准。
            customPrompt = SystemPrompt.loadCustomPersona(context)
            editingPrompt = false
        },
        onDismiss = { editingPrompt = false },
        backdrop = backdrop,
    )
}

/**
 * 设置首页：四组入口。每一行右边的说明就是那一项现在的样子，不用点进去也知道配好没有。
 *
 * @param onOpen 进哪一个二级页。
 * @param onEditPersona 改人设：只有一样东西要改，直接弹模态窗，不另开一页。
 */
@Composable
private fun SettingsRoot(
    backdrop: Backdrop,
    profiles: LlmProfiles,
    allTimeTokens: Long,
    customPrompt: String?,
    memoryCount: Int,
    status: PermissionStatus,
    privilegeState: PrivilegeState,
    onOpen: (SettingsPage) -> Unit,
    onEditPersona: () -> Unit,
    actions: SetupActions,
) {
    val colors = KittenTheme.colors
    val active = profiles.profiles.firstOrNull { it.id == profiles.activeId }
    val (modelText, modelNeedsAttention) = when {
        profiles.profiles.isEmpty() -> "还没配置，填好 API Key 才能聊天" to true
        active == null -> "还没选用哪一家" to true
        !active.isConfigured -> "${active.displayName} · 还没填好 Key 或模型" to true
        else -> "${active.displayName} · ${active.model}" to false
    }

    Column {
        SettingsGroupLabel("模型")
        SettingsGroup(backdrop = backdrop) {
            SettingsRow(
                icon = Icons.Filled.Star,
                iconTint = colors.iconBlue,
                title = "模型服务商",
                subtitle = modelText,
                subtitleColor = if (modelNeedsAttention) colors.warning else colors.textSecondary,
                onClick = { onOpen(SettingsPage.Models) },
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Filled.DateRange,
                iconTint = colors.iconGreen,
                title = "Token 看板",
                subtitle = "累计 ${formatTokens(allTimeTokens)} tokens",
                onClick = { onOpen(SettingsPage.Tokens) },
            )
        }
    }

    Column {
        SettingsGroupLabel("个性与记忆")
        SettingsGroup(backdrop = backdrop) {
            SettingsRow(
                icon = Icons.Filled.Face,
                iconTint = colors.iconPink,
                title = "人设",
                subtitle = if (customPrompt != null) "已自定义" else "默认的 Kitten",
                onClick = onEditPersona,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Filled.Favorite,
                iconTint = colors.iconCoral,
                title = "记忆",
                subtitle = if (memoryCount == 0) "还什么都没记住" else "记住了 $memoryCount 件事",
                onClick = { onOpen(SettingsPage.Memory) },
            )
        }
    }

    Column {
        SettingsGroupLabel("权限与能力")
        SettingsGroup(backdrop = backdrop) {
            PermissionRow(
                icon = Icons.Filled.AccountCircle,
                iconTint = colors.iconViolet,
                title = stringResource(R.string.accessibility_service_label),
                enabled = status.accessibilityEnabled,
                onOpenSettings = actions::openAccessibilitySettings,
            )
            SettingsDivider()
            PermissionRow(
                icon = Icons.Filled.Notifications,
                iconTint = colors.iconAmber,
                title = stringResource(R.string.notification_listener_label),
                enabled = status.notificationListenerEnabled,
                onOpenSettings = actions::openNotificationListenerSettings,
            )
            SettingsDivider()
            SettingsRow(
                icon = Icons.Filled.Lock,
                iconTint = colors.iconTeal,
                title = "增强模式",
                subtitle = privilegeState.summary(),
                subtitleColor = if (privilegeState is PrivilegeState.Error) colors.warning else colors.textSecondary,
                onClick = { onOpen(SettingsPage.Privilege) },
            )
        }
    }

    Column {
        SettingsGroupLabel("其他")
        SettingsGroup(backdrop = backdrop) {
            SettingsRow(
                icon = Icons.Filled.Build,
                iconTint = colors.iconGray,
                title = "开发者调试",
                subtitle = "组件预览、副屏、Toast",
                onClick = { onOpen(SettingsPage.Developer) },
            )
        }
    }
}

/**
 * 一项系统权限：开了写"已开启"、点了没反应；没开写"去开启"、点了直接跳系统设置。
 *
 * 开了就不再引人去点：要关掉的话去系统设置里关，那本来就是系统的地盘。
 */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    enabled: Boolean,
    onOpenSettings: () -> Unit,
) {
    val colors = KittenTheme.colors
    SettingsRow(
        icon = icon,
        iconTint = iconTint,
        title = title,
        trailingText = stringResource(if (enabled) R.string.status_enabled else R.string.action_open_settings),
        trailingColor = if (enabled) colors.positive else colors.accentStrong,
        showChevron = !enabled,
        onClick = if (enabled) null else onOpenSettings,
    )
}

/** 增强模式那一行的说明：连没连上，连上了能干什么。 */
private fun PrivilegeState.summary(): String = when (this) {
    is PrivilegeState.Ready -> "已连接 · 能在后台副屏里操作"
    PrivilegeState.Connecting -> "连接中…"
    is PrivilegeState.Error -> "出错了，点进去看看"
    PrivilegeState.Idle -> "没连上 · 配好后能在后台操作，不打扰你"
}

/**
 * 组件预览。
 *
 * **这里的控件都不接任何逻辑**，只有本地状态，纯粹用来看 `ui/component` 里那套玻璃
 * 组件的手感：开关、滑条、chip、两种按钮。等真的有对应的设置项时，把这一块换掉即可，
 * 组件本身不用改。
 */
@Composable
private fun ComponentPreviewSection(backdrop: Backdrop, modifier: Modifier = Modifier) {
    var autoGrant by remember { mutableStateOf(true) }
    var verboseLog by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0.6f) }
    var tone by remember { mutableIntStateOf(1) }

    GlassSection(
        backdrop = backdrop,
        title = "组件预览",
        icon = Icons.Filled.Favorite,
        footer = "mock，不接任何逻辑。按下就有反馈，松手才算数：按住开关拖回原处、" +
            "或者从滑条上开始滚动页面，都会退回原样。",
        modifier = modifier,
    ) {
        GlassListItem(
            title = "自动授予权限",
            supporting = "拿到 ADB 后自动打开无障碍",
            leading = { GlassIconTile(Icons.Filled.Check) },
            trailing = {
                GlassSwitch(checked = autoGrant, onCheckedChange = { autoGrant = it }, backdrop = backdrop)
            },
        )
        GlassDivider()
        GlassListItem(
            title = "打印详细日志",
            leading = { GlassIconTile(Icons.Filled.Info) },
            trailing = {
                GlassSwitch(checked = verboseLog, onCheckedChange = { verboseLog = it }, backdrop = backdrop)
            },
        )
        GlassDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                // 不用 String.format：它跟随系统区域设置，有的语言里小数点是逗号。
                // 数值只在松手时更新——这正是"松手才算数"的样子。
                text = "示例滑条 · ${(level * 10).roundToInt() / 10f}",
                style = MaterialTheme.typography.bodyLarge,
                color = KittenTheme.colors.textPrimary,
            )
            GlassSlider(
                value = level,
                onValueChange = { level = it },
                backdrop = backdrop,
                valueRange = 0f..2f,
                steps = 19,
            )
        }
        GlassDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("简洁", "均衡", "详细").forEachIndexed { index, label ->
                GlassChip(text = label, selected = tone == index, onClick = { tone = index })
            }
        }
        GlassDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassButton(
                text = "主要",
                onClick = {},
                backdrop = backdrop,
                icon = Icons.Filled.Star,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "次要",
                onClick = {},
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "禁用",
                onClick = {},
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                enabled = false,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * 换页的滑动：偏硬、几乎不回弹，像系统设置那样干脆地推进推出。淡入淡出比位移快一点，滑到一半
 * 旧的那页就差不多看不见了，两页叠在一起的时间短。
 */
private val PageSlideSpec = spring(
    dampingRatio = 0.95f,
    stiffness = 450f,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)
private val PageFadeSpec = spring<Float>(stiffness = 700f)
