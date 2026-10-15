package me.wjz.kitten.a11y

/**
 * 抓取屏幕的结果：要么拿到快照，要么拿到一个说得清原因的失败。
 *
 * 之所以不用 `UiSnapshot?`：null 把所有失败揉成了一种，而模型需要据此分流——
 * 应用被抢走了该重新拉起，无障碍没开该提示用户，副屏没了该重建。详见 [ScreenError]。
 */
sealed interface CaptureResult {

    data class Ok(val snapshot: UiSnapshot) : CaptureResult

    data class Failed(val error: ScreenError) : CaptureResult

    /** 成功就返回快照，失败返回 null。只在调用方确实不关心原因时用。 */
    fun snapshotOrNull(): UiSnapshot? = (this as? Ok)?.snapshot

    /**
     * 转成直接发给模型的文本：成功是节点树，失败是一行错误说明。
     *
     * 两种情况都是模型能继续推理的输入，这正是分开定义错误类型的意义。
     */
    fun toModelText(): String = when (this) {
        is Ok -> snapshot.toCompactString()
        is Failed -> error.describe()
    }
}
