package me.wjz.kitten.ui.chat

import me.wjz.kitten.agent.AgentQuestion
import me.wjz.kitten.data.session.SessionSummary
import me.wjz.kitten.llm.ChatMessage
import me.wjz.kitten.llm.LlmPreset

/**
 * 对话页要渲染的全部状态。
 *
 * 做成一个不可变的数据类，而不是在 ViewModel 里摆一堆独立的 StateFlow：
 * 界面任一时刻看到的都是一份自洽的快照，不会出现"正在加载"和"已有消息"互相矛盾
 * 的中间态。改多个字段也只触发一次重组。
 */
data class ChatUiState(
    /** 当前会话 id；为 null 表示还没有会话（刚进来或刚清空）。 */
    val sessionId: String? = null,

    val title: String = "",

    val messages: List<ChatMessage> = emptyList(),

    /** 侧边栏的历史会话列表。 */
    val sessions: List<SessionSummary> = emptyList(),

    /** 模型正在生成。此时输入框禁用，并显示等待指示。 */
    val isResponding: Boolean = false,

    /**
     * 正在流式生成的那条回复，已经冒出来的字。还没冒出第一个字、或者不在生成时是 null。
     *
     * 它不在 [messages] 里：回复完之前不入库，也不该被当成上下文发给模型。界面把它画在列表
     * 最后，当成一条临时的回复。
     */
    val streamingReply: String? = null,

    /**
     * Agent 正在做的那一步，一句人话（"打开 com.taobao.taobao""读取屏幕内容"）。不在执行
     * 工具时为 null。
     *
     * 界面上那一步显示在工具清单里（那一行的圆点在闪），这里只用来判断要不要显示"正在思考"：
     * 正在执行工具时不显示。模型看不到它。
     */
    val agentProgress: String? = null,

    /**
     * Agent 正在等用户回答的问题。对话底部会出现一张选项卡片；用户不在 Kitten 界面里时，
     * 常驻通知上也会显示同一个问题（见 [me.wjz.kitten.agent.AgentTaskHandle]）。
     */
    val pendingQuestion: AgentQuestion? = null,

    /**
     * 模型是否已配置。
     *
     * 没配就别让用户对着输入框打半天字再被告知发不出去——直接在界面上提示去设置页。
     */
    val isModelConfigured: Boolean = false,

    /**
     * 输入栏上能切换的模型，按服务商分组（见 [ModelSwitcher]）。只列填好了 Key、有可选模型的
     * 服务商：没填 Key 的那几家切过去也发不出去。
     */
    val modelGroups: List<ModelGroup> = emptyList(),

    /** 正在用的那一家（[ModelGroup.profileId]）。当前那一家还没配好（没 Key 或没选模型）时为 null。 */
    val activeProfileId: String? = null,

    /** 正在用的模型，同上，没配好时为 null。 */
    val activeModel: String? = null,

    /** 一次性的错误提示，展示过就该被 [ChatViewModel.consumeError] 清掉。 */
    val error: String? = null,

    /** 一次性的普通提示（比如"已自动补上 /v1"），展示过就该被 [ChatViewModel.consumeNotice] 清掉。 */
    val notice: String? = null,
) {
    val isEmpty: Boolean get() = messages.isEmpty()

    /**
     * 能不能发送：模型配好了，而且不在生成中。
     *
     * 例外是 Agent 正等着回答的时候——这时输入框要能用，用户打的字就是答案
     * （见 [ChatViewModel.send]），尤其是问题没给选项、只能自己打字的场合。
     */
    val canSend: Boolean get() = isModelConfigured && (!isResponding || pendingQuestion != null)
}

/**
 * 输入栏模型菜单里的一组：一家服务商和它可选的模型。
 *
 * @param profileId 对应的 [me.wjz.kitten.llm.LlmProfile.id]，切换时按它找回是哪一家。
 * @param name 组名，和设置页卡片上的名字一样。
 * @param preset 这一家对上的预设，自定义的服务是 null。决定输入栏上那个圆按钮里画谁家的图标，见 [modelBrandOf]。
 */
data class ModelGroup(
    val profileId: String,
    val name: String,
    val models: List<String>,
    val preset: LlmPreset? = null,
)
