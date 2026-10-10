package me.wjz.kitten.privilege

import me.wjz.kitten.privilege.ipc.PrivilegeContract

/** 构造在 shell 里启动特权进程的命令。 */
object Starter {

    private const val NICE_NAME = "me.wjz.kitten:privilege"

    /**
     * @param apkPath base.apk 的路径，可在 shell 里用 `pm path me.wjz.kitten` 得到。
     * @param token   本次启动的一次性校验码，特权进程交回 binder 时带上，供 App 核对。
     */
    fun buildCommand(apkPath: String, token: String): String =
        // 放进子 shell 后台运行：启动它的那条 shell 退出后，特权进程仍存活。
        "(CLASSPATH=$apkPath app_process /system/bin " +
            "--nice-name=$NICE_NAME " +
            "${PrivilegeContract.SERVER_MAIN_CLASS} --token=$token)&"
}
