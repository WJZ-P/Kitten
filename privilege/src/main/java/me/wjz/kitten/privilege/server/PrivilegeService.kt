package me.wjz.kitten.privilege.server

import android.os.Process
import me.wjz.kitten.privilege.ipc.IPrivilegeService
import me.wjz.kitten.privilege.ipc.PrivilegeContract
import kotlin.system.exitProcess

/**
 * IPrivilegeService 的实现，运行在特权进程（uid=2000 或 0）里。
 *
 * 2a 阶段只实现握手必需的三个方法。授权相关的方法会在第 3 步加进来。
 */
class PrivilegeService : IPrivilegeService.Stub() {

    override fun getVersion(): Int = PrivilegeContract.PROTOCOL_VERSION

    override fun getUid(): Int = Process.myUid()

    override fun destroy() {
        // 以后可以在这里做清理，然后退出整个特权进程。
        exitProcess(0)
    }
}
