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

    /** 特权进程的入口类，由 app_process 启动。 */
    const val SERVER_MAIN_CLASS = "me.wjz.kitten.privilege.server.ServerMain"

    /** 协议版本，App 更新后与特权进程核对是否一致。 */
    const val PROTOCOL_VERSION = 1
}
