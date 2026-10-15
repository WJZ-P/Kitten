package me.wjz.kitten.a11y

/**
 * 执行一个动作的结果。
 *
 * 和 [CaptureResult] 一样，失败带着[原因][ScreenError]而不是一个 false——模型要据此
 * 决定重抓、换目标、还是换一条执行通道。
 */
sealed interface ActionResult {

    data object Ok : ActionResult

    data class Failed(val error: ScreenError) : ActionResult

    val isOk: Boolean
        get() = this is Ok

    /** 转成发给模型的文本：成功一行确认，失败是可据以决策的错误说明。 */
    fun toModelText(): String = when (this) {
        is Ok -> "OK"
        is Failed -> error.describe()
    }
}
