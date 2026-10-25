package me.wjz.kitten.privilege.ipc

/** App 侧与特权进程侧共享的常量。 */
object PrivilegeContract {

    /** ContentProvider authority 的后缀，完整值是 "${applicationId}.privilege"。 */
    const val AUTHORITY_SUFFIX = ".privilege"

    /** ContentProvider.call 的 method 名：特权进程用它把 binder 交回。 */
    const val METHOD_SEND_BINDER = "sendBinder"

    /** Bundle 里存放 BinderContainer 的 key。 */
    const val EXTRA_BINDER = "me.wjz.kitten.privilege.extra.BINDER"

    /** Bundle 里存放一次性 token 的 key，用于校验 binder 确实来自本次启动。 */
    const val EXTRA_TOKEN = "me.wjz.kitten.privilege.extra.TOKEN"

    /**
     * call 返回的 Bundle 里表示"App 是否接受了这个 binder"的 key。
     *
     * 必须有这个回执：call 本身不会因为 App 拒绝而抛异常，特权进程收不到否定答复
     * 就会一直活着，变成一个谁也不认识的孤儿特权进程。
     */
    const val KEY_ACCEPTED = "me.wjz.kitten.privilege.key.ACCEPTED"

    /** 特权进程的入口类，由 app_process 启动。 */
    const val SERVER_MAIN_CLASS = "me.wjz.kitten.privilege.server.ServerMain"

    /**
     * 特权侧代码的版本号。**改动任何跑在特权进程里的代码，就把它 +1。**
     *
     * 为什么需要它：特权进程是 `app_process` 拉起来的常驻进程，**不随 App 更新而重启**。
     * 装上新版 App 后，旧特权进程往往还活着，而且继续执行旧 dex——
     * 此时 [me.wjz.kitten.privilege.PrivilegeManager.state] 显示的是 Ready，
     * 看起来一切正常，实际调过去的全是老逻辑。
     *
     * 实际踩过：改完 `VirtualDisplayHost` 的 TRUSTED flag，装了新包、重测了好几遍，
     * 行为却一点没变，最后是靠"新加的日志字段没出现"才发现跑的是旧代码。
     *
     * 有了这个号，App 每次连上都会核对一次，不一致就把特权进程重新拉起
     * （见 [me.wjz.kitten.privilege.PrivilegeManager.isStale]）。
     */
    const val PROTOCOL_VERSION = 5
}
