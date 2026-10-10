package me.wjz.kitten.privilege

/** 特权连接的状态。 */
sealed interface PrivilegeState {

    /** 还没开始。 */
    data object Idle : PrivilegeState

    /** 正在拉起特权进程、等待它交回 binder。 */
    data object Connecting : PrivilegeState

    /** 已连接。uid 是特权进程的身份，正常应为 2000（adb shell）。 */
    data class Ready(val uid: Int) : PrivilegeState

    /** 出错。 */
    data class Error(val message: String) : PrivilegeState
}
