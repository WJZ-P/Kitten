package me.wjz.kitten.privilege.server

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

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
     * `DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL`：副屏移除时，上面的应用
     * 跟着关掉。
     *
     * 不带它的话，公开副屏（PUBLIC）移除时系统会把上面的 task 挪到主屏、而且放在最前面。实测：
     * Agent 在副屏里用完 QQ，一轮结束回收副屏，QQ 突然盖在了用户眼前（日志里是
     * `moveTaskToFront: Task{… com.tencent.mobileqq}`，preDisplayId 是副屏、displayId 变成 0）。
     * 副屏是 Agent 自己的工作台，收工时台面上的东西一起收掉，不该甩到用户面前。
     *
     * 代价：用户原本在主屏开着的那个应用，被 Agent 拉到副屏用过之后也会一起关掉（单实例的应用，
     * 拉起时系统会把已有的 task 挪过去）。应用进程不会被杀，下次打开从它的首页开始。
     *
     * 也是 `@hide`，只能写字面量。AOSP 里创建副屏时没有为它查权限，只是把它记进显示设备的标志位。
     */
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

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
            // DESTROY_CONTENT_ON_REMOVAL：收掉副屏时上面的应用一起关，不挪到主屏，见常量注释。
            val baseFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                FLAG_DESTROY_CONTENT_ON_REMOVAL

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

    /**
     * 取一帧副屏画面，压成 JPEG 返回；拿不到返回 null。
     *
     * ## 画面是从哪来的
     *
     * 副屏创建时就把 [ImageReader] 的 Surface 当成了渲染目标（见 [create]），画面一直在往
     * 那里写，取帧只是把最新的一张读出来，不需要 MediaProjection 那套要用户授权的流程。
     *
     * 副屏正显示在别的 Surface 上时（[setSurface] 传了非 null）画面不再往 ImageReader 写，
     * 这时取不到帧。Agent 用的副屏本来就是隐藏的，不受影响。
     *
     * ## 为什么要缩小、压缩
     *
     * 字节数要走 Binder 一次事务带回去，而一个进程的事务缓冲区总共才 1MB 左右，塞一张原始
     * 分辨率的位图（1080×2400×4 ≈ 10MB）必炸。好在给模型看的截图也不需要原始清晰度——缩到
     * 长边一千多像素、JPEG 压一下，界面上的字照样读得清。质量按 [MAX_BYTES] 兜底，太大就
     * 一档档降，降到 [MIN_QUALITY] 为止。
     */
    fun capture(displayId: Int, maxEdge: Int, quality: Int): ByteArray? {
        val holder = holders[displayId] ?: run {
            Log.w(TAG, "displayId=$displayId 不是 Kitten 创建的，截不了图")
            return null
        }
        return runCatching {
            // acquireLatestImage 会丢掉积压的旧帧只留最新一张，正是我们要的。
            // 返回 null 说明这块屏还没渲染过任何东西。
            val image = holder.reader.acquireLatestImage() ?: run {
                Log.w(TAG, "display=$displayId 还没有可用的画面")
                return null
            }
            // 必须 close，否则缓冲区占满之后副屏就不再出新帧了。
            val raw = image.use(::toBitmap)
            val scaled = raw.scaleToMaxEdge(maxEdge)
            compressToJpeg(scaled, quality).also {
                Log.i(TAG, "display=$displayId 截图 ${scaled.width}x${scaled.height}，${it.size / 1024}KB")
            }
        }.getOrElse {
            Log.e(TAG, "截取 display=$displayId 失败", it)
            null
        }
    }

    /**
     * 把 ImageReader 给的一帧转成位图。
     *
     * 每行末尾可能有对齐填充（rowStride 比 width×pixelStride 大），所以先按"带填充的宽度"
     * 建位图再裁掉多出来的那几列。不裁的话图会整体斜着错开。
     */
    private fun toBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)
        if (padded.width == image.width) return padded
        return Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }

    private fun Bitmap.scaleToMaxEdge(maxEdge: Int): Bitmap {
        val longest = maxOf(width, height)
        if (maxEdge <= 0 || longest <= maxEdge) return this
        val scale = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** 压成 JPEG，太大就一档档降质量，见 [capture] 里关于 Binder 事务大小的说明。 */
    private fun compressToJpeg(bitmap: Bitmap, quality: Int): ByteArray {
        var current = quality.coerceIn(MIN_QUALITY, 100)
        while (true) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, current, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_BYTES || current <= MIN_QUALITY) return bytes
            Log.i(TAG, "截图 ${bytes.size / 1024}KB 偏大，质量 $current 再降一档")
            current = (current - QUALITY_STEP).coerceAtLeast(MIN_QUALITY)
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

    /**
     * 一张截图最多多少字节。
     *
     * Binder 一个进程的事务缓冲区总共 1MB 左右，而且是所有并发调用共用的，所以单张留一半
     * 出头做上限，超了就降质量。实测界面截图缩到长边一千多像素之后，JPEG 普遍在 100～300KB，
     * 很少撞到这个线。
     */
    private const val MAX_BYTES = 512 * 1024

    /** 质量降到这里就不再降了，再低字会糊得认不出来。 */
    private const val MIN_QUALITY = 40

    private const val QUALITY_STEP = 15
}
