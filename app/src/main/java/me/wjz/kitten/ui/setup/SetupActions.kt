package me.wjz.kitten.ui.setup

/**
 * 引导界面需要宿主 Activity 提供的能力。
 *
 * 这些事情 Composable 自己做不了——跳系统设置要 Activity Context，申请运行时权限要
 * ActivityResultLauncher，拉起前台服务要求调用方自己在前台。与其把四个 lambda 一路
 * 往下传，不如收成一个接口：参数列表干净，Preview 也只要给一个空实现。
 */
interface SetupActions {

    /** 打开系统的无障碍设置页。 */
    fun openAccessibilitySettings()

    /** 打开系统的通知使用权设置页。 */
    fun openNotificationListenerSettings()

    /**
     * 开始 ADB 配对：必要时先申请通知权限，拉起配对通知服务，再跳到开发者选项的
     * 「无线调试」那一行。这三步合成一个动作，用户只需要点一次。
     */
    fun startPairing()

    companion object {
        /** 给 @Preview 用的空实现。 */
        val Noop: SetupActions = object : SetupActions {
            override fun openAccessibilitySettings() = Unit
            override fun openNotificationListenerSettings() = Unit
            override fun startPairing() = Unit
        }
    }
}
