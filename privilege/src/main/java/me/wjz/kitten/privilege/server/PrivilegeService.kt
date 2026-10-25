package me.wjz.kitten.privilege.server

import android.os.Process
import android.view.Surface
import me.wjz.kitten.privilege.ipc.IPrivilegeService
import me.wjz.kitten.privilege.ipc.PrivilegeContract
import kotlin.system.exitProcess

/**
 * IPrivilegeService 的实现，运行在特权进程（uid=2000）里。
 *
 * 这里只做转发和参数校验，具体活交给各自的 host。
 */
class PrivilegeService : IPrivilegeService.Stub() {

    override fun getVersion(): Int = PrivilegeContract.PROTOCOL_VERSION

    override fun getUid(): Int = Process.myUid()

    override fun destroy() {
        // 副屏的生命周期绑在本进程上，退出前主动收掉，别留下孤儿屏幕。
        VirtualDisplayHost.releaseAll()
        exitProcess(0)
    }

    override fun createVirtualDisplay(width: Int, height: Int, densityDpi: Int): Int =
        VirtualDisplayHost.create(width, height, densityDpi)

    override fun releaseVirtualDisplay(displayId: Int) = VirtualDisplayHost.release(displayId)

    override fun releaseAllVirtualDisplays() = VirtualDisplayHost.releaseAll()

    override fun captureDisplay(displayId: Int, maxEdge: Int, quality: Int): ByteArray? =
        VirtualDisplayHost.capture(displayId, maxEdge, quality)

    override fun setDisplaySurface(displayId: Int, surface: Surface?): Boolean =
        VirtualDisplayHost.setSurface(displayId, surface)

    override fun launchAppOnDisplay(
        packageName: String?,
        displayId: Int,
        forceNewInstance: Boolean,
    ): Boolean {
        // AIDL 的 String 在跨进程时可能为 null，这里挡一道。
        if (packageName.isNullOrEmpty()) return false
        return AppLauncher.launchOnDisplay(packageName, displayId, forceNewInstance)
    }

    override fun injectTap(displayId: Int, x: Int, y: Int): Boolean =
        InputInjector.tap(displayId, x, y)

    override fun injectSwipe(
        displayId: Int,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
        durationMs: Int,
    ): Boolean = InputInjector.swipe(displayId, x1, y1, x2, y2, durationMs)

    override fun injectLongPress(displayId: Int, x: Int, y: Int, durationMs: Int): Boolean =
        InputInjector.longPress(displayId, x, y, durationMs)

    override fun injectKeyEvent(displayId: Int, keyCode: Int): Boolean =
        InputInjector.keyEvent(displayId, keyCode)
}
