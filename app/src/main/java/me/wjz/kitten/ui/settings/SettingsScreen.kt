package me.wjz.kitten.ui.settings

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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Face
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.R
import me.wjz.kitten.agent.SystemPrompt
import me.wjz.kitten.llm.LlmConfigStore
import me.wjz.kitten.llm.LlmProfile
import me.wjz.kitten.llm.LlmProfiles
import me.wjz.kitten.permission.PermissionStatus
import me.wjz.kitten.permission.SelfConfigurator
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
import me.wjz.kitten.ui.setup.DebugCard
import me.wjz.kitten.ui.setup.PermissionItem
import me.wjz.kitten.ui.setup.PrivilegeSection
import me.wjz.kitten.ui.setup.SetupActions
import kotlin.math.roundToInt
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页：模型配置 + 系统提示词 + 权限管理。
 *
 * 原先这些内容独占一个启动页（SetupScreen），现在收进 tab。权限引导本来就是
 * 一次性的事，配好之后不该还占着首屏——首屏该是对话。
 *
 * 权限状态每次回到前台都要重读：用户是去系统设置里开关的，App 这边收不到回调。
 *
 * 页面本身不再放"设置"大标题：顶栏已经写着了，再写一遍只是占地方。
 *
 * @param contentPadding 滚动内容四周额外留的空白。底部栏浮在页面上面，靠它让最后一张卡片
 *   能滚到栏的上方。
 */
@Composable
fun SettingsScreen(
    backdrop: Backdrop,
    actions: SetupActions,
    onModelConfigChanged: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 留白放在滚动内容里面：内容能一直滚到底部栏后面，滚到底时最后一张卡片又能完整露出来。
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModelProfilesSection(
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

        SystemPromptSection(
            backdrop = backdrop,
            customPrompt = customPrompt,
            onEdit = {
                // 打开时拷一份当前在用的：没改过就是默认人设，用户看得到原本写了什么再改。
                promptDraft = customPrompt ?: SystemPrompt.DEFAULT_PERSONA
                editingPrompt = true
            },
        )

        GlassSection(backdrop = backdrop, title = "权限", icon = Icons.Filled.Lock) {
            PermissionItem(
                backdrop = backdrop,
                icon = Icons.Filled.Face,
                title = stringResource(R.string.accessibility_service_label),
                enabled = status.accessibilityEnabled,
                onOpenSettings = actions::openAccessibilitySettings,
            )
            GlassDivider()
            PermissionItem(
                backdrop = backdrop,
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.notification_listener_label),
                enabled = status.notificationListenerEnabled,
                onOpenSettings = actions::openNotificationListenerSettings,
            )
        }

        PrivilegeSection(backdrop = backdrop, onStartPairing = actions::startPairing)

        // 临时：看玻璃组件的手感，定下来之后换成真的设置项。
        ComponentPreviewSection(backdrop = backdrop)

        // 临时：验证副屏可用性，验完连同 DebugCard.kt 一起删。
        DebugCard()
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
