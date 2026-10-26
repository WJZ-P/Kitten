package me.wjz.kitten

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Kitten 的界面现在在不在前台（用户看得见）。由 [MainActivity] 的 onStart / onStop 维护。
 *
 * 给不在界面里的地方用，比如常驻通知服务：Agent 要问用户问题时，用户正看着 Kitten 的话，对话里
 * 已经有提问卡片了，不用再弹一条横幅通知；切到别的应用了才弹。
 *
 * 只有一个 Activity，所以一个布尔值就够。没用 ProcessLifecycleOwner：为这一个值再引一个库不划算。
 */
object AppVisibility {

    private val _inForeground = MutableStateFlow(false)

    val inForeground: StateFlow<Boolean> = _inForeground.asStateFlow()

    internal fun update(visible: Boolean) {
        _inForeground.value = visible
    }
}
