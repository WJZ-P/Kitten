package me.wjz.kitten.privilege

import me.wjz.kitten.privilege.ipc.PrivilegeContract

/** 构造在 shell 里执行的命令。 */
object Starter {

    /**
     * 一次性自助授权，是整套"免手动"方案的地基。
     *
     * WRITE_SECURE_SETTINGS 的 protectionLevel 带 `development` 标志，所以能用
     * `pm grant` 授给普通应用。关键在于**它授给的是 App 自己的 uid，永久有效**：
     * 扛重启、也不需要特权进程在场。拿到之后 App 自己就能开无障碍、开通知使用权、
     * 重新打开无线调试——于是开机后能自动把特权进程重建起来，用户再不用碰 ADB。
     *
     * 对比之下，INJECT_EVENTS / CAPTURE_VIDEO_OUTPUT / INTERNAL_SYSTEM_WINDOW
     * 这些都是纯 signature 级，没有 development 标志，`pm grant` 授不了，
     * 只能靠活着的特权进程去用。
     *
     * 电池白名单是附带的尽力而为项：失败也不影响主流程，所以用 `;` 串联而不是 `&&`。
     */
    fun buildSelfGrantCommand(packageName: String): String =
        "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS; " +
            "cmd deviceidle whitelist +$packageName"

    private const val NICE_NAME = "me.wjz.kitten:privilege"

    /** 特权进程的启动输出。App 自己读不到（shell 目录），但出问题时可以用 adb 查。 */
    const val LOG_PATH = "/data/local/tmp/kitten_privilege.log"

    /**
     * @param apkPath base.apk 的路径，可在 shell 里用 `pm path me.wjz.kitten` 得到。
     * @param token   本次启动的一次性校验码，特权进程交回 binder 时带上，供 App 核对。
     */
    fun buildCommand(apkPath: String, token: String): String =
        // 这条命令里每一段都是必须的，去掉任何一段都会出问题：
        //
        // pkill -x -f
        //          先清掉上一次残留的特权进程，否则每次重试都会多堆一个。
        //          -x（精确全匹配）不能少：app_process 把 cmdline 整个改写成了
        //          nice-name，正好能精确命中；而只用 -f 的话，任何 cmdline 里**含有**
        //          这个字符串的进程都会被杀——包括执行这条命令的 shell 自己。
        // setsid   脱离当前会话，PTY 挂断时的 SIGHUP 带不走它（Shizuku 的 native
        //          starter 里也是 setsid + dup2 到 /dev/null，这里是等价的 shell 写法）。
        // </dev/null >LOG 2>&1
        //          **最关键的一段**。不重定向的话，后台进程会继承 adb shell 的 PTY，
        //          fd 0/1/2 一直指向 /dev/pts/N，那条 shell 流就永远不会 EOF，
        //          AdbBackend 里等读完的那一步会卡死（实测过）。
        //          输出写进文件而不是 /dev/null：启动失败时（比如 ClassNotFound）
        //          这是唯一的线索，ServerMain 起来之后的日志才会进 logcat。
        // ( ... &) & 必须写在括号**内**。写成 ( ... )& 的话，这个子 shell 会变成主 shell
        //          的一个 running job，而交互式 mksh 发现有后台作业时会拒绝退出，
        //          只回一句 "You have running jobs" 继续等输入——shell 就此泄漏，
        //          那条 shell 流也永远不会关闭（实测过）。
        //          写在括号内则是子 shell 内部后台启动后立刻退出，主 shell 手上没有
        //          job，exit 畅通，特权进程 reparent 到 init 继续存活。
        "(pkill -x -f $NICE_NAME; " +
            "CLASSPATH=$apkPath setsid app_process /system/bin " +
            "--nice-name=$NICE_NAME " +
            "${PrivilegeContract.SERVER_MAIN_CLASS} --token=$token " +
            "</dev/null >$LOG_PATH 2>&1 &)"
}
