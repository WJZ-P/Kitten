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
 * **拿不到 TRUSTED 标志**。`ADD_TRUSTED_DISPLAY` 的 protectionLevel 是 `signature|role`，
 * 三个持有它的 role 全是 systemOnly，shell 身份也没有。后果是系统会剥掉
 * `SHOULD_SHOW_SYSTEM_DECORATIONS`，这块屏上**没有桌面、没有导航栏、没有输入法**——
 * 只能用 [AppLauncher] 直接把目标应用扔上去，没法让用户在上面自己点。
 *
 * 好在往非 trusted 屏上启动任意应用是可以的：`isCallerAllowedToLaunchOnDisplay` 里
 * `INTERNAL_SYSTEM_WINDOW` 的判断排在 trusted 检查之前，而 shell 持有这个权限。
 */
internal object VirtualDisplayHost {

    private const val TAG = "KittenPrivDisplay"
    private const val DISPLAY_NAME = "KittenVirtualDisplay"

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
            // 不加 TRUSTED —— 加了会因为缺 ADD_TRUSTED_DISPLAY 直接抛 SecurityException。
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            val display = displayManager.createVirtualDisplay(
                DISPLAY_NAME,
                width,
                height,
                densityDpi,
                reader.surface,
                flags,
            ) ?: error("createVirtualDisplay 返回 null")

            val id = display.display.displayId
            holders[id] = Holder(display, reader)
            Log.i(TAG, "副屏已创建：displayId=$id ${width}x$height @${densityDpi}dpi")
            id
        }.getOrElse {
            Log.e(TAG, "创建副屏失败", it)
            INVALID_DISPLAY_ID
        }
    }

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
