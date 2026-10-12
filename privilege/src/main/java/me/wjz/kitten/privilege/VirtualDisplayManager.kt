package me.wjz.kitten.privilege

import android.util.Log
import android.view.Surface

/**
 * 副屏能力的对外入口。上层（Agent、UI）只跟它打交道，不用碰 AIDL，也不用关心
 * 特权进程在不在、怎么连上的。
 *
 * ## 先搞清楚一件事：副屏上的应用和主屏是什么关系
 *
 * 真机实测（HyperOS / Android 16）结论：
 *
 * - **同一个进程**。把应用拉到副屏不会产生第二个实例，pid 不变。
 * - **同一份数据**。`/data/user/0/<pkg>` 只有一份，所以**登录态是共享的**——
 *   主屏登录了，副屏打开就是已登录，不会是全新的未登录态。
 * - **默认会"搬家"而不是"复制"**。目标应用已经在别的屏开着时，系统会把它整个 task
 *   搬到目标屏，于是它从原来那块屏上消失。想两边各留一份要传 `forceNewInstance=true`。
 *
 * 真正独立的实例（独立数据、独立登录）只能靠多用户或应用分身，那是另一个 uid，
 * 不在这套机制的能力范围内。
 *
 * ## 这块副屏是"裸"的
 *
 * 我们拿不到 `ADD_TRUSTED_DISPLAY`（protectionLevel 为 `signature|role`，三个持有它的
 * role 全是 systemOnly），所以创建出来的屏没有系统装饰：**没有桌面、没有导航栏、
 * 没有输入法**。只能用 [launchApp] 直接把目标应用扔上去。
 */
object VirtualDisplayManager {

    private const val TAG = "KittenVirtualDisplay"

    const val INVALID_DISPLAY_ID = -1

    /** 特权进程是否就绪。没就绪时下面所有方法都会直接失败。 */
    val isAvailable: Boolean
        get() = PrivilegeManager.get() != null

    /**
     * 创建一块副屏。
     *
     * @return displayId，失败返回 [INVALID_DISPLAY_ID]。
     */
    fun createDisplay(width: Int, height: Int, densityDpi: Int): Int {
        val service = PrivilegeManager.get() ?: run {
            Log.w(TAG, "特权进程未就绪，无法创建副屏")
            return INVALID_DISPLAY_ID
        }
        return runCatching { service.createVirtualDisplay(width, height, densityDpi) }
            .onFailure { Log.e(TAG, "创建副屏失败", it) }
            .getOrDefault(INVALID_DISPLAY_ID)
    }

    /**
     * 在副屏上拉起某个应用。
     *
     * @param forceNewInstance 应用已在别处开着时，false 会把它搬过来（原处消失），
     *                         true 则两边各留一个 task。注意 launchMode 为
     *                         singleTask / singleInstance 的 Activity 会无视它。
     */
    fun launchApp(
        packageName: String,
        displayId: Int,
        forceNewInstance: Boolean = true,
    ): Boolean {
        val service = PrivilegeManager.get() ?: run {
            Log.w(TAG, "特权进程未就绪，无法启动 $packageName")
            return false
        }
        if (displayId == INVALID_DISPLAY_ID) {
            Log.w(TAG, "displayId 无效，拒绝启动 $packageName")
            return false
        }
        return runCatching {
            service.launchAppOnDisplay(packageName, displayId, forceNewInstance)
        }.onFailure { Log.e(TAG, "启动 $packageName 失败", it) }.getOrDefault(false)
    }

    /** 创建副屏并立刻把应用拉上去。失败返回 [INVALID_DISPLAY_ID] 并自动回收。 */
    fun launchAppOnNewDisplay(
        packageName: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        forceNewInstance: Boolean = true,
    ): Int {
        val displayId = createDisplay(width, height, densityDpi)
        if (displayId == INVALID_DISPLAY_ID) return INVALID_DISPLAY_ID
        if (!launchApp(packageName, displayId, forceNewInstance)) {
            // 应用没起来，这块屏留着也没用，顺手收掉免得泄漏。
            releaseDisplay(displayId)
            return INVALID_DISPLAY_ID
        }
        return displayId
    }

    /**
     * 让副屏显示到给定的 [surface] 上。传 null 则重新隐身。
     *
     * 典型用法：界面上放一个 SurfaceView，在它的 surfaceCreated 回调里把
     * `holder.surface` 传进来，副屏画面就实时投上去了；surfaceDestroyed 时传 null。
     *
     * 隐藏时回到的是创建副屏时那块离屏缓冲，所以来回切不会影响以后的截图通道。
     */
    fun setSurface(displayId: Int, surface: Surface?): Boolean {
        val service = PrivilegeManager.get() ?: run {
            Log.w(TAG, "特权进程未就绪，无法切换副屏显示")
            return false
        }
        return runCatching { service.setDisplaySurface(displayId, surface) }
            .onFailure { Log.e(TAG, "切换副屏 $displayId 显示失败", it) }
            .getOrDefault(false)
    }

    /** 隐藏副屏：画面切回离屏缓冲。 */
    fun hide(displayId: Int): Boolean = setSurface(displayId, null)

    fun releaseDisplay(displayId: Int) {
        val service = PrivilegeManager.get() ?: return
        runCatching { service.releaseVirtualDisplay(displayId) }
            .onFailure { Log.w(TAG, "释放副屏 $displayId 失败", it) }
    }

    fun releaseAll() {
        val service = PrivilegeManager.get() ?: return
        runCatching { service.releaseAllVirtualDisplays() }
            .onFailure { Log.w(TAG, "释放全部副屏失败", it) }
    }
}
