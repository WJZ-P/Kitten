package me.wjz.kitten.agent

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Agent 在屏上刚做的一个动作，直播画面上要标出来的那种。坐标是那块屏的像素。
 *
 * 光看直播的话，页面只是一下一下地跳，看不出 Kitten 点了哪、往哪滑。把动作标在画面上，用户才跟得上
 * 它在干什么。
 */
sealed interface ScreenMark {
    val displayId: Int

    /** 点、长按、往输入框里填字：在那个位置泛一圈。 */
    data class Touch(override val displayId: Int, val x: Int, val y: Int, val long: Boolean = false) : ScreenMark

    /** 滑动、滚动：从起点划到终点。 */
    data class Slide(
        override val displayId: Int,
        val fromX: Int,
        val fromY: Int,
        val toX: Int,
        val toY: Int,
    ) : ScreenMark
}

/**
 * 动作一做成就发一个 [ScreenMark]，直播画面收着画（见 ui/live 的 LiveScreen）。
 *
 * 只是一阵一阵的事件，不是状态：没人在看的时候发出去就丢了，用不着攒着。缓冲给几个，连着点得很快时
 * 新的挤掉旧的，画面上本来也只看得清最近的几下。
 */
object ScreenMarks {

    private val _marks = MutableSharedFlow<ScreenMark>(
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val marks: SharedFlow<ScreenMark> = _marks.asSharedFlow()

    fun emit(mark: ScreenMark) {
        _marks.tryEmit(mark)
    }

    private const val BUFFER = 8
}
