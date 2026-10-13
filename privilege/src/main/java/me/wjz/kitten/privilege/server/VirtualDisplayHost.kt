package me.wjz.kitten.privilege.server

import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap

/**
 * 在特权进程里持有虚拟副屏。
 *
 * 为什么由特权进程持有：虚拟屏幕的生命周期绑在创建者身上，创建者进程一死屏幕立刻消失。
 * 特权进程常驻、比 App 进程稳定得多，放这里副屏才活得住。
 *
 * ## 为什么必须是 trusted display
 *
 * 一块屏是不是 trusted，决定了**应用自己**能不能在上面启动 Activity。
 * `ActivityTaskSupervisor.isCallerAllowedToLaunchOnDisplay` 里：
 *
 * ```java
 * if (!display.isTrusted()) {
 *     if ((aInfo.flags & FLAG_ALLOW_EMBEDDED) == 0) return false;  // 挡住绝大多数应用
 *     ...
 * }
 * ```
 *
 * 我们自己调 `am start` 不受影响——shell 持有 `INTERNAL_SYSTEM_WINDOW`，在上面那段之前
 * 就直接放行了。但应用的**第一个 Activity 跳到第二个**时，发起方变成了应用自己，
 * 它既没有 `INTERNAL_SYSTEM_WINDOW`，目标 Activity 也几乎不会声明
 * `android:allowEmbedded="true"`，于是被判 false，系统把它退回主屏。
 *
 * 实测现象（淘宝，非 trusted 屏）：`Welcome` 正确落在副屏，1 秒后跳 `TBMainActivity` 时
 * logcat 打出 `disallow launch on virtual display for not-embedded activity`，主页跑去了主屏。
 * 对只有一个 Activity 的应用（计算器）看不出问题，一旦有 splash 跳转就露馅。
 *
 * ## TRUSTED 能拿到
 *
 * `ADD_TRUSTED_DISPLAY` 的 protectionLevel 是 `signature|role`，Kitten 自己的 uid 确实拿不到，
 * 但特权进程跑在 uid 2000 / `com.android.shell` 下，而它**已经被授予了这个权限**
 * （`dumpsys package com.android.shell` 可见 `granted=true`）。
 * 服务端只校验这一个权限（`DisplayManagerService.createVirtualDisplayInternal`），所以通得过。
 *
 * 仍然做降级：定制 ROM 可能删掉 shell 的这条授权，拿不到就退回非 trusted——
 * 单 Activity 的应用照旧能用，总比整个功能挂掉好。
 */
internal object VirtualDisplayHost {

    private const val TAG = "KittenPrivDisplay"
    private const val DISPLAY_NAME = "KittenVirtualDisplay"

    /**
     * `DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED`。
     *
     * 它是 `@SystemApi @hide`，编译期取不到，只能写字面量。值从 Android 10 引入起没变过。
     */
    private const val FLAG_TRUSTED = 1 shl 10   //  所以这个其实是API里面没有暴露的，写的一个魔法数

    /**
     * 画面缓冲区的张数。
     *
     * 2 张够用：一张正在被系统写入、一张可以被读走。给多了只是白占内存。
     */
    private const val IMAGE_BUFFER_COUNT = 2

    private class Holder(val display: VirtualDisplay, val reader: ImageReader)

    private val holders = ConcurrentHashMap<Int, Holder>()

    /**
     * 创建一块副屏，返回 displayId；失败返回 -1。
     *
     * 用 ImageReader 的 Surface 当画面输出，而不是随便塞个 SurfaceTexture：
     * 虚拟屏幕必须有地方渲染，而 ImageReader 额外带来一个好处——以后要给视觉模型
     * 喂截图时，直接从这里读帧即可，不用再走 MediaProjection 那套要用户授权的流程。
     */
    fun create(width: Int, height: Int, densityDpi: Int): Int {
        val context = SystemContext.get() ?: run {
            Log.e(TAG, "没有系统 Context，无法创建副屏")
            return INVALID_DISPLAY_ID
        }
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            Log.e(TAG, "副屏参数不合法：${width}x$height @${densityDpi}dpi")
            return INVALID_DISPLAY_ID
        }
        // density 和主屏不一致是个很难自己查出来的坑：应用会在 onConfigurationChanged 里
        // 看到 densityDpi 变化，有的（实测 QQ）直接把自己的进程 SIGKILL 掉，连带主屏上
        // 正在用的 task 一起消失。不拦，只是把话说在日志里——宽高不一致倒是没关系。
        val mainDensity = runCatching { context.resources.configuration.densityDpi }.getOrNull()
        if (mainDensity != null && mainDensity != densityDpi) {
            Log.w(
                TAG,
                "副屏 density=$densityDpi 与主屏 $mainDensity 不一致。部分应用会把这当成" +
                    "系统显示设置被改动，进而杀掉自己的进程（连带主屏的 task 一起消失）。" +
                    "建议传 context.resources.displayMetrics.densityDpi",
            )
        }
        return runCatching {
            val displayManager = context.getSystemService(DisplayManager::class.java)
                ?: error("取不到 DisplayManager")
            val reader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                IMAGE_BUFFER_COUNT,
            )
            // PUBLIC：别的应用的 Activity 才能被放到这块屏上。
            // OWN_CONTENT_ONLY：只显示扔上来的内容，不镜像主屏。
            val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

            // 先按 trusted 建，这是应用内部跳转能留在副屏的前提（见类注释）。
            var trusted = true
            val display = runCatching {
                newDisplay(displayManager, width, height, densityDpi, reader, baseFlags or FLAG_TRUSTED)
            }.getOrElse { error ->
                trusted = false
                Log.w(
                    TAG,
                    "建 trusted 副屏失败，退回普通副屏：这个 ROM 大概没给 com.android.shell " +
                        "授予 ADD_TRUSTED_DISPLAY。后果是应用内部跳转会被退回主屏，" +
                        "只有单 Activity 的应用能正常用",
                    error,
                )
                newDisplay(displayManager, width, height, densityDpi, reader, baseFlags)
            }

            val id = display.display.displayId
            holders[id] = Holder(display, reader)
            Log.i(
                TAG,
                "副屏已创建：displayId=$id ${width}x$height @${densityDpi}dpi " +
                    "trusted=$trusted",
            )
            id
        }.getOrElse {
            Log.e(TAG, "创建副屏失败", it)
            INVALID_DISPLAY_ID
        }
    }

    private fun newDisplay(
        displayManager: DisplayManager,
        width: Int,
        height: Int,
        densityDpi: Int,
        reader: ImageReader,
        flags: Int,
    ): VirtualDisplay = displayManager.createVirtualDisplay(
        DISPLAY_NAME,
        width,
        height,
        densityDpi,
        reader.surface,
        flags,
    ) ?: error("createVirtualDisplay 返回 null，flags=$flags")

    /**
     * 切换副屏的渲染目标，从而控制它是否可见。
     *
     * @param surface 传 SurfaceView 的 Surface 则画面参与系统合成，用户看得见；
     *                传 null 则切回 ImageReader 的离屏缓冲，重新隐身。
     *
     * 切回离屏时用的还是**同一个** ImageReader，所以来回切不会丢掉截图通道。
     */
    fun setSurface(displayId: Int, surface: Surface?): Boolean {
        val holder = holders[displayId] ?: run {
            Log.w(TAG, "displayId=$displayId 不是 Kitten 创建的，忽略")
            return false
        }
        return runCatching {
            // null 表示隐藏：回到创建时那块离屏缓冲。
            val target = surface ?: holder.reader.surface
            holder.display.surface = target
            Log.i(TAG, "副屏 $displayId 渲染目标已切换：${if (surface == null) "离屏（隐藏）" else "可见"}")
            true
        }.getOrElse {
            Log.e(TAG, "切换副屏 $displayId 的渲染目标失败", it)
            false
        }
    }

    /** 释放指定副屏；不是我们创建的就忽略。 */
    fun release(displayId: Int) {
        val holder = holders.remove(displayId)
        if (holder == null) {
            Log.w(TAG, "displayId=$displayId 不是 Kitten 创建的，忽略")
            return
        }
        runCatching { holder.display.release() }.onFailure { Log.w(TAG, "释放副屏失败", it) }
        runCatching { holder.reader.close() }.onFailure { Log.w(TAG, "关闭 ImageReader 失败", it) }
        Log.i(TAG, "副屏已释放：displayId=$displayId")
    }

    /** 释放全部副屏。特权进程退出前调用，免得留下孤儿屏幕。 */
    fun releaseAll() {
        holders.keys.toList().forEach(::release)
    }

    const val INVALID_DISPLAY_ID = -1
}
