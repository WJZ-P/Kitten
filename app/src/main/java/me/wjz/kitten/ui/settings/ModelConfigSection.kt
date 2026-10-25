package me.wjz.kitten.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.launch
import me.wjz.kitten.llm.EndpointRepair
import me.wjz.kitten.llm.LlmFormat
import me.wjz.kitten.llm.LlmPreset
import me.wjz.kitten.llm.LlmProfile
import me.wjz.kitten.llm.LlmProfiles
import me.wjz.kitten.llm.ModelListResult
import me.wjz.kitten.llm.createProvider
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDropdown
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassModal
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassStatus
import me.wjz.kitten.ui.component.GlassStatusTone
import me.wjz.kitten.ui.component.GlassTextField
import me.wjz.kitten.ui.component.GlassToggleButton
import me.wjz.kitten.ui.component.LocalToast
import me.wjz.kitten.ui.component.ToastTone
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme
import java.net.URI

/**
 * 设置页上的"模型"：一张大卡片，里面每家服务一张小卡，最后是"添加服务商"。
 *
 * 小卡只放最常动的东西：服务商名、这一家用哪个模型（下拉条，选了就生效）、用不用它。地址、
 * Key 这些配一次就很少再动的，点小卡打开模态窗改（[ModelConfigDialog]），不占设置页的地方。
 */
@Composable
fun ModelProfilesSection(
    backdrop: Backdrop,
    profiles: LlmProfiles,
    onSelectModel: (profile: LlmProfile, model: String) -> Unit,
    onActivate: (LlmProfile) -> Unit,
    onEdit: (LlmProfile) -> Unit,
    onAdd: () -> Unit,
) {
    GlassSection(backdrop = backdrop, title = "模型", icon = Icons.Filled.Star) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (profiles.profiles.isEmpty()) {
                GlassStatus(
                    text = "还没有配置模型服务，添加一家、填好 API Key 才能对话",
                    tone = GlassStatusTone.Warning,
                )
            }
            profiles.profiles.forEach { profile ->
                key(profile.id) {
                    ProviderTile(
                        backdrop = backdrop,
                        profile = profile,
                        isActive = profile.id == profiles.activeId,
                        onSelectModel = { onSelectModel(profile, it) },
                        onActivate = { onActivate(profile) },
                        onEdit = { onEdit(profile) },
                    )
                }
            }
            GlassButton(
                text = "添加服务商",
                onClick = onAdd,
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                icon = Icons.Filled.Add,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 一家服务的小卡：第一行服务商名，第二行左边选模型、右边"使用 / 使用中"。
 *
 * 点小卡的空白处（不是下拉条和按钮——它们先消费了按下）打开编辑窗，按下时垫一层淡天依蓝，
 * 和下拉菜单的选项一样；名字后面一个"›"提示能点。
 *
 * "使用"要等这一家配好了（有 Key、有模型）才能点，不然切过去对话也发不出去。正在用的那一家
 * 显示"使用中"，是染色的主按钮，一眼看得出对话用的是哪家。
 */
@Composable
private fun ProviderTile(
    backdrop: Backdrop,
    profile: LlmProfile,
    isActive: Boolean,
    onSelectModel: (String) -> Unit,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = KittenTheme.colors
    val currentOnEdit by rememberUpdatedState(onEdit)
    val press = rememberGlassPressState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                onClick(label = "编辑") {
                    currentOnEdit()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnEdit() } }
            .clip(TileShape)
            .drawBehind {
                drawRect(colors.controlTint)
                drawRect(colors.sliderIdle, alpha = press.pressProgress.coerceIn(0f, 1f))
            }
            .border(width = 1.dp, color = colors.hairline, shape = TileShape)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.displayName(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val choices = profile.modelChoices
            if (choices.isEmpty()) {
                // 没有可选的模型时不放下拉条：点开是一个空菜单，不如直接说清楚去哪里弄。
                Text(
                    text = "还没有可选的模型，点这里获取或填写",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textTertiary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                GlassDropdown(
                    items = choices,
                    selected = profile.model.takeIf { it.isNotBlank() },
                    onSelect = onSelectModel,
                    itemLabel = { it },
                    label = null,
                    backdrop = backdrop,
                    modifier = Modifier.weight(1f),
                    placeholder = "选一个模型",
                )
            }
            if (isActive) {
                GlassButton(
                    text = "使用中",
                    onClick = {},
                    backdrop = backdrop,
                    style = GlassButtonStyle.Primary,
                    compact = true,
                )
            } else {
                GlassButton(
                    text = "使用",
                    onClick = onActivate,
                    backdrop = backdrop,
                    style = GlassButtonStyle.Secondary,
                    compact = true,
                    enabled = profile.isConfigured,
                )
            }
        }
        if (!profile.isConfigured) {
            Text(
                text = "还没填好 API Key 或模型",
                style = MaterialTheme.typography.labelSmall,
                color = colors.warning,
            )
        }
    }
}

/** 小卡的形状：比大卡小一档的圆角，和按钮、输入框一样。 */
private val TileShape = RoundedCornerShape(GlassRadius.Small)

/**
 * 一家服务的编辑窗。
 *
 * 编辑的是一份草稿（[draft]），由调用方在打开时拷一份：点保存才写回，不保存直接关掉就丢弃，
 * 不会出现"改了一半关掉，下次打开还是改了一半的样子"。
 *
 * 预设只是帮用户省掉查文档，不是白名单——任何兼容 OpenAI 或 Anthropic 协议的服务（包括
 * 自建的 Ollama、各种中转）填上地址就能用，所以每个字段都保持可编辑。地址对不上任何预设时，
 * 表单里会多出"接口格式"一项（[FormatPicker]）让用户选协议。
 *
 * 预设用玻璃下拉菜单（[GlassDropdown]）选。Material 的下拉菜单是另开窗口的不透明弹层，
 * 和玻璃格格不入，所以没用它。
 *
 * @param isNew 新加的一家：标题是"添加服务商"，没有删除按钮。
 */
@Composable
fun ModelConfigDialog(
    visible: Boolean,
    draft: LlmProfile,
    isNew: Boolean,
    onDraftChange: (LlmProfile) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    backdrop: Backdrop,
) {
    GlassModal(
        visible = visible,
        onDismissRequest = onDismiss,
        backdrop = backdrop,
        title = if (isNew) "添加服务商" else "编辑服务商",
        icon = Icons.Filled.Star,
    ) { modalBackdrop ->
        ModelConfigForm(
            backdrop = modalBackdrop,
            config = draft,
            isNew = isNew,
            onConfigChange = onDraftChange,
            onSave = onSave,
            onDelete = onDelete,
        )
    }
}

@Composable
private fun ModelConfigForm(
    backdrop: Backdrop,
    config: LlmProfile,
    isNew: Boolean,
    onConfigChange: (LlmProfile) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    // API Key 默认遮起来。设置页可能在别人旁边打开，密钥不该直接亮在屏幕上。换一家编辑时重新遮上。
    var keyVisible by remember(config.id) { mutableStateOf(false) }

    // "获取可用模型"：用表单里现在填的地址和 Key 去拉这家的模型列表，和已有的合在一起存进草稿
    // （手动加的不会被冲掉），模型还没选时顺手选上第一个，结果用底部 Toast 说。按钮是 API Key
    // 那一栏里的一个图标：Key 一般是粘贴进来的，粘完顺手就点。地址像是少拼了 /v1 时，先自己补上
    // 试一下，通了就把表单里的地址改掉（见 EndpointRepair）。改的都是草稿，点保存才生效。
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()
    // 请求要等一会儿，回来时用的是那时候最新的草稿，不是点按钮那一刻的。
    val currentConfig by rememberUpdatedState(config)
    val currentOnConfigChange by rememberUpdatedState(onConfigChange)
    var fetching by remember { mutableStateOf(false) }

    fun fetchModels() {
        if (fetching) return
        scope.launch {
            fetching = true
            // 只是列模型，maxTokens 和 temperature 用不上。
            val probeConfig = currentConfig.toConfig(maxTokens = 1, temperature = 0.0)
            var result = probeConfig.createProvider().listModels()
            val failed = result as? ModelListResult.Failed
            val repairedUrl = if (failed != null && EndpointRepair.isWrongPath(failed.error)) {
                EndpointRepair.tryAppendV1(probeConfig)
            } else {
                null
            }
            if (repairedUrl != null) {
                result = probeConfig.copy(baseUrl = repairedUrl).createProvider().listModels()
            }
            fetching = false

            val latest = currentConfig
            val baseUrl = repairedUrl ?: latest.baseUrl
            val repairedNote = if (repairedUrl != null) "地址少了 /v1，已自动补上。" else ""
            when (result) {
                is ModelListResult.Ok -> {
                    currentOnConfigChange(
                        latest.copy(
                            baseUrl = baseUrl,
                            models = (latest.models + result.models).distinct().sorted(),
                            model = latest.model.ifBlank { result.models.firstOrNull().orEmpty() },
                        ),
                    )
                    if (result.models.isEmpty()) {
                        toast.show(repairedNote + "服务商没有返回任何模型，可以在模型下拉菜单底部手动添加")
                    } else {
                        toast.show(repairedNote + "获取到 ${result.models.size} 个模型", tone = ToastTone.Success)
                    }
                }
                is ModelListResult.Failed -> {
                    // 列表没拉到，但地址补对了（比如 Key 的问题）：地址照样改掉。
                    if (repairedUrl != null) currentOnConfigChange(latest.copy(baseUrl = baseUrl))
                    toast.show(repairedNote + "获取模型失败：${result.error.describe()}", tone = ToastTone.Error)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GlassContentPadding)
            .padding(top = 6.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GlassDropdown(
            items = LlmPreset.ALL,
            selected = config.matchingPreset(),
            onSelect = { preset ->
                // 只填地址、格式和一个常用模型，不碰 API Key——换服务商时密钥本来就得重填，
                // 但如果用户只是想换个模型名，清掉 key 会很烦。上一家拉到的模型列表对新的一家
                // 没意义，清掉。
                onConfigChange(
                    config.copy(
                        format = preset.format,
                        baseUrl = preset.baseUrl,
                        model = preset.sampleModel,
                        models = emptyList(),
                    ),
                )
            },
            itemLabel = { it.label },
            label = "服务商预设",
            backdrop = backdrop,
            placeholder = "自定义",
        )
        GlassTextField(
            value = config.baseUrl,
            onValueChange = { onConfigChange(config.copy(baseUrl = it)) },
            label = "API 地址",
            backdrop = backdrop,
            // 自填的地址最容易漏掉 /v1：漏了请求会打到网站页面上，拿回一张网页。占位里就写出来。
            placeholder = "https://…/v1，留空用官方默认",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        GlassTextField(
            value = config.apiKey,
            onValueChange = { onConfigChange(config.copy(apiKey = it)) },
            label = "API Key",
            backdrop = backdrop,
            placeholder = "sk-…",
            visualTransformation = if (keyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailing = { wellBackdrop ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(FetchButtonGap),
                ) {
                    FetchModelsButton(
                        fetching = fetching,
                        onClick = ::fetchModels,
                        backdrop = wellBackdrop,
                    )
                    GlassToggleButton(
                        text = if (keyVisible) "隐藏" else "显示",
                        checked = keyVisible,
                        onCheckedChange = { keyVisible = it },
                        backdrop = wellBackdrop,
                    )
                }
            },
        )

        // 选了预设时格式是那一家定死的，不用问；地址对不上任何预设（自定义）时，不知道它说
        // 哪种协议，让用户选。不显示的话，先选 Anthropic 预设再改地址，格式会悄悄停在 Anthropic。
        AnimatedVisibility(visible = config.matchingPreset() == null) {
            FormatPicker(
                format = config.format,
                onFormatChange = { onConfigChange(config.copy(format = it)) },
                backdrop = backdrop,
            )
        }

        ModelPicker(
            config = config,
            onConfigChange = onConfigChange,
            backdrop = backdrop,
        )

        GlassButton(
            text = "保 存",
            onClick = onSave,
            backdrop = backdrop,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.titleLarge,
        )

        if (!isNew) {
            // 删掉就连 Key 一起没了，点错了很麻烦：第一下只是变成确认，再点一次才删。
            var confirming by remember(config.id) { mutableStateOf(false) }
            GlassButton(
                text = if (confirming) "再点一次，确认删除" else "删除这个服务商",
                onClick = {
                    if (confirming) {
                        onDelete()
                    } else {
                        confirming = true
                    }
                },
                backdrop = backdrop,
                style = GlassButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 选这一家用哪个模型：一整行的下拉条。"获取可用模型"在 API Key 那一栏里（[FetchModelsButton]），
 * 不和下拉条挤一行——模型名常常很长，宽度要留给它。
 *
 * 下拉条里是这一家所有可选的模型，每一项右边一个"×"，点了从列表里删掉（删的正好是选中的
 * 那个，就换成剩下的第一个）。菜单底部固定一个输入框，手动输入模型名、点"添加"（或者键盘上的
 * 完成）就加进列表并选中——拉不到列表的服务、列表里没有的型号，都靠它。
 *
 * 改的都是草稿，点保存才生效，和表单里别的字段一样。
 */
@Composable
private fun ModelPicker(
    config: LlmProfile,
    onConfigChange: (LlmProfile) -> Unit,
    backdrop: Backdrop,
) {
    // 菜单是另一层，回调触发时用最新的草稿，不是菜单打开那一刻的。
    val currentConfig by rememberUpdatedState(config)
    val currentOnConfigChange by rememberUpdatedState(onConfigChange)
    // 菜单底部输入框里正在打的模型名。换一家编辑时清空。
    var newModel by remember(config.id) { mutableStateOf("") }

    fun addModel(close: () -> Unit) {
        val name = newModel.trim()
        if (name.isEmpty()) return
        val latest = currentConfig
        currentOnConfigChange(
            latest.copy(
                models = if (name in latest.models) latest.models else latest.models + name,
                model = name,
            ),
        )
        newModel = ""
        close()
    }

    GlassDropdown(
        items = config.modelChoices,
        selected = config.model.takeIf { it.isNotBlank() },
        onSelect = { onConfigChange(config.copy(model = it)) },
        itemLabel = { it },
        label = "模型",
        backdrop = backdrop,
        placeholder = "选一个，或在菜单底部添加",
        onRemove = { removed ->
            val latest = currentConfig
            val rest = latest.models - removed
            currentOnConfigChange(
                latest.copy(
                    models = rest,
                    model = if (latest.model == removed) rest.firstOrNull().orEmpty() else latest.model,
                ),
            )
        },
        footer = { menuBackdrop, close ->
            GlassTextField(
                value = newModel,
                onValueChange = { newModel = it },
                label = null,
                backdrop = menuBackdrop,
                placeholder = "手动输入模型名",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addModel(close) }),
                trailing = { wellBackdrop ->
                    GlassButton(
                        text = "添加",
                        onClick = { addModel(close) },
                        backdrop = wellBackdrop,
                        style = GlassButtonStyle.Secondary,
                        compact = true,
                        enabled = newModel.isNotBlank(),
                    )
                },
            )
        },
    )
}

/**
 * API Key 那一栏里的"获取可用模型"：一个刷新图标的玻璃小圆，和旁边的"显示"一样高。
 * 拉列表的时候图标一直转；这时再点不会重复发请求（[onClick] 那边会挡掉）。
 */
@Composable
private fun FetchModelsButton(
    fetching: Boolean,
    onClick: () -> Unit,
    backdrop: Backdrop,
) {
    // 只在拉列表时才起无限动画，平时不占一个一直在跑的动画。停下时图标直接回正。
    val rotation = if (fetching) {
        rememberInfiniteTransition(label = "fetchModels").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(FetchSpinMillis, easing = LinearEasing)),
            label = "fetchSpin",
        )
    } else {
        null
    }
    GlassIconButton(
        icon = Icons.Filled.Refresh,
        contentDescription = if (fetching) "正在获取可用模型" else "获取可用模型",
        onClick = onClick,
        backdrop = backdrop,
        size = FetchButtonSize,
        touchSize = FetchButtonTouchSize,
        iconModifier = if (rotation != null) {
            Modifier.graphicsLayer { rotationZ = rotation.value }
        } else {
            Modifier
        },
    )
}

/** 获取模型那个圆看得见的大小：和旁边"显示"按钮一样高。 */
private val FetchButtonSize = 32.dp

/**
 * 它的触摸区。槽只有 44dp 高，给不到 48dp；比圆大一圈好按一点。改了它记得同步
 * [FetchButtonGap]，不然圆和"显示"之间看起来的间距会变。
 */
private val FetchButtonTouchSize = 40.dp

/**
 * 圆的触摸区和"显示"按钮之间的间距。触摸区每边比圆多出 (40 - 32) / 2 = 4dp，再加这 2dp，
 * 看起来就是 6dp——和"显示"到槽右边的距离一样。
 */
private val FetchButtonGap = 2.dp

/** 拉列表时图标转一圈多久（毫秒）。 */
private const val FetchSpinMillis = 800

/**
 * 自定义服务的接口格式：OpenAI 还是 Anthropic。
 *
 * [LlmFormat.OPENAI] 和 [LlmFormat.OPENAI_COMPATIBLE] 发出去的请求一模一样（只差显示名），
 * 这里合成一个选项，选它时存成 OPENAI_COMPATIBLE。
 *
 * 两个选项是两块玻璃切换按钮（[GlassToggleButton]），各占一半铺满整行；选中的那块染上强调色，
 * 点的时候鼓起、变成透镜，和别的按钮一样。
 */
@Composable
private fun FormatPicker(
    format: LlmFormat,
    onFormatChange: (LlmFormat) -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 和输入框的标签同一个样子，看起来是表单里的一项。
        Text(
            text = "接口格式",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 单选：点已经选中的那块不会取消，只有点另一块才切换。
            GlassToggleButton(
                text = "OpenAI 格式",
                checked = !format.isAnthropic,
                onCheckedChange = { if (it) onFormatChange(LlmFormat.OPENAI_COMPATIBLE) },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                pressedScale = FormatPressedScale,
            )
            GlassToggleButton(
                text = "Anthropic 格式",
                checked = format.isAnthropic,
                onCheckedChange = { if (it) onFormatChange(LlmFormat.ANTHROPIC) },
                backdrop = backdrop,
                modifier = Modifier.weight(1f),
                pressedScale = FormatPressedScale,
            )
        }
        Text(
            text = "不确定就选 OpenAI 格式，大多数第三方服务和中转都兼容它。",
            style = MaterialTheme.typography.labelSmall,
            color = colors.textTertiary,
        )
    }
}

/** 接口格式的两个按钮按下时鼓到多大。它们各占半行，比输入框里的小按钮宽得多，给小一点。 */
private const val FormatPressedScale = 1.05f

private val LlmFormat.isAnthropic: Boolean get() = this == LlmFormat.ANTHROPIC

/**
 * 预设没有单独存，按"地址 + 协议都对得上"反推当前用的是哪一家。对不上就是用户自己填的。
 *
 * 协议只分 OpenAI 和 Anthropic 两种：OPENAI 和 OPENAI_COMPATIBLE 是同一种协议，地址填的是
 * OpenAI 官方、格式存的是 OPENAI_COMPATIBLE 时，也该认成 OpenAI 预设。
 */
private fun LlmProfile.matchingPreset(): LlmPreset? =
    LlmPreset.ALL.firstOrNull { it.baseUrl == baseUrl && it.format.isAnthropic == format.isAnthropic }

/** 卡片上的名字：对得上预设就用预设名，否则用地址里的主机名。 */
private fun LlmProfile.displayName(): String =
    matchingPreset()?.label
        ?: runCatching { URI(baseUrl.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: "自定义服务"
