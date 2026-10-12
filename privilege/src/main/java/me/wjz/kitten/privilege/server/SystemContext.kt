package me.wjz.kitten.privilege.server

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * 在特权进程里造一个系统 Context。
 *
 * 特权进程是 app_process 直接拉起来的，不走正常的应用启动流程，所以手上没有
 * 任何 Context——而 DisplayManager、PackageManager、startActivity 全都要它。
 *
 * 解法是反射调 `ActivityThread.systemMain().getSystemContext()`，和 Shizuku 的
 * UserService 同一套路。`ActivityThread` 是隐藏类，但特权进程不是应用进程，
 * 没有 non-SDK 接口限制，反射可以直接用。
 *
 * **必须在主线程、且 Looper 准备好之后调用**：`systemMain()` 内部会 new 出
 * ActivityThread，它的 H handler 用的是当前线程的 Looper，没有就直接抛异常。
 * 所以 [ServerMain] 里的顺序是 prepareMainLooper → init → loop。
 */
// lint 的 StaticFieldLeak 在这里是误报，压掉而不是改写。
// 那条规则防的是"把 Activity/Service 的 Context 放进静态字段，组件销毁后泄漏它
// 和它的视图树"。我们这个 Context 来自 ActivityThread.getSystemContext()，是进程级的，
// 背后没有任何 Activity 或 View；而且特权进程本身就是个单一用途的常驻进程，
// 它的生命周期等于这个 Context 的生命周期，退出时整个进程一起消失（见 destroy()）。
// 反过来说也不能不缓存：systemMain() 只能在主线程调用，binder 线程上重建会直接抛异常。
@SuppressLint("StaticFieldLeak")
internal object SystemContext {

    private const val TAG = "KittenPrivContext"

    @Volatile
    private var context: Context? = null

    /** 在主线程初始化。失败只记日志——拿不到 Context 只是副屏用不了，不该拖垮整个特权进程。 */
    fun init() {
        if (context != null) return
        context = runCatching { create() }
            .onFailure { Log.e(TAG, "创建系统 Context 失败，副屏相关功能将不可用", it) }
            .getOrNull()
        context?.let { Log.i(TAG, "系统 Context 已就绪，packageName=${it.packageName}") }
    }

    /** 取 Context，没初始化成功就返回 null，调用方自己决定怎么降级。 */
    fun get(): Context? = context

    /**
     * shell 身份对应的包名。
     *
     * `getSystemContext()` 给回来的 Context 包名是 "android"（uid 1000），但我们这个进程
     * 是 uid 2000。`DisplayManagerService.createVirtualDisplayInternal` 会做
     * `validatePackageName(callingUid, packageName)` —— 拿 `getPackagesForUid(2000)` 去比对，
     * 包名对不上就抛 `SecurityException: packageName must match the calling uid`（实测踩过）。
     *
     * uid 2000 名下只有这一个包。Android Studio 的屏幕共享建的虚拟屏，用的也是它。
     */
    private const val SHELL_PACKAGE = "com.android.shell"

    @SuppressLint("PrivateApi")
    private fun create(): Context {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val activityThread = activityThreadClass.getMethod("systemMain").invoke(null)
        val systemContext =
            activityThreadClass.getMethod("getSystemContext").invoke(activityThread) as Context

        // 换成包名为 com.android.shell 的 Context，这样 getPackageName() 和本进程的
        // uid 2000 才对得上。不带 CONTEXT_INCLUDE_CODE：我们只要一个身份正确的
        // Context，不需要真去加载 shell 那个 APK 的代码。
        return runCatching {
            systemContext.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse {
            Log.w(TAG, "切换到 $SHELL_PACKAGE 失败，退回系统 Context（副屏可能用不了）", it)
            systemContext
        }
    }
}
