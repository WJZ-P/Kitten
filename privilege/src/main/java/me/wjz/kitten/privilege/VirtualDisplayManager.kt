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
 * ## 尺寸也要跟主屏一致，否则应用会当它是台小屏手机
 *
 * 应用排版依据的是 **dp**，不是像素：`dp = 像素 / (densityDpi / 160)`。
 * density 对齐之后，分辨率就直接决定了应用眼里这块屏有多大。
 *
 * 实测踩过：副屏写死 1080x1920、density 跟主屏取 480，算下来是 **360x640dp**，
 * 而主屏 1220x2712 @480dpi 是 **407x904dp**——副屏高度只有主屏的 71%、面积 63%，
 * 相当于一台 2012 年的手机。B 站在副屏上就少显示一行视频卡片，Agent 看到的内容
 * 比用户在主屏看到的少一截。
 *
 * 所以建副屏时宽高传 `context.resources.displayMetrics` 的真实值，和 densityDpi
 * 一样别写死。代价是像素多出 1.6 倍、ImageReader 的缓冲区更大，但副屏本来就不可见，
 * 这点渲染开销换"所见即主屏"是划算的。
 *
 * ## densityDpi 必须跟主屏一致
 *
 * 建副屏时传 `context.resources.displayMetrics.densityDpi`，**不要写死**。
 *
 * 原因是应用侧的：App 的 Application Configuration 跟着"当前主要的 display"走，
 * 在副屏上起一个 Activity 会触发 `Application.onConfigurationChanged`，
 * 而其中的 `densityDpi` 就是副屏的值。不少应用把这个变化当成"用户在系统设置里
 * 改了显示大小"，进而做激烈处理。
 *
 * 实测 QQ（主屏 480dpi，副屏给 320dpi）：
 *
 * ```
 * GlobalDisplayMetricsManager.checkChangeOfSysDensity(...)
 *   ← BaseApplicationImpl.onConfigurationChanged(...)
 *   ← ActivityThread.handleLaunchActivity(...)
 * → GlobalDisplayMetricsManager.killProcess(...) → SIG: 9
 * ```
 *
 * 它直接把自己整个进程 SIGKILL 了。进程一死，它名下**所有** Activity 都会被判
 * `proc died without state saved` 强制 finish——不只是副屏上新建的那个，连用户
 * 正在主屏用的那个 task 也一起没，副屏则因为没有壁纸兜底而变成纯黑。
 *
 * 同样条件下把副屏 density 改成 480，QQ 就安然无恙。宽高不一致没有这个问题，
 * 多屏本来尺寸就可以不同；density 才是会被误读成"系统设置变了"的那个。
 *
 * ## 这块副屏是"裸"的
 *
 * 屏上没有系统装饰：**没有桌面、没有导航栏、没有输入法**，只能用 [launchApp]
 * 直接把目标应用扔上去。这是我们**没加** `SHOULD_SHOW_SYSTEM_DECORATIONS` 的结果，
 * 不是能力不足——它的前提 `TRUSTED` 已经拿到了（见 `VirtualDisplayHost` 的类注释），
 * 以后要让副屏能弹输入法，把那个 flag 补上就行。
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

    /**
     * 截一张副屏的图，返回 JPEG 字节；拿不到返回 null。
     *
     * 只对自己创建的副屏有效，而且副屏得是隐藏状态（正显示在某个 Surface 上时画面不往
     * 截图通道写）。细节见 `VirtualDisplayHost.capture`。
     *
     * @param maxEdge 长边最多多少像素，超了等比缩小。
     * @param quality JPEG 质量 1..100。结果太大时特权侧会自己再降，见那边的 MAX_BYTES。
     */
    fun captureDisplay(displayId: Int, maxEdge: Int, quality: Int): ByteArray? {
        val service = PrivilegeManager.get() ?: run {
            Log.w(TAG, "特权进程未就绪，截不了图")
            return null
        }
        return runCatching { service.captureDisplay(displayId, maxEdge, quality) }
            .onFailure { Log.e(TAG, "截取副屏 $displayId 失败", it) }
            .getOrNull()
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
