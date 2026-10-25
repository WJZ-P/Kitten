package me.wjz.kitten.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.wjz.kitten.service.KittenAccessibilityService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 用无障碍服务截屏（`AccessibilityService.takeScreenshot`，Android 11 起）。
 *
 * ## 为什么主屏截图走这里
 *
 * 副屏的画面是从特权进程的离屏缓冲里读的（见特权侧 VirtualDisplayHost.capture），主屏没有这条
 * 通道。无障碍服务声明了 `canTakeScreenshot`（见 accessibility_service_config.xml）之后能直接截
 * 主屏：不用 MediaProjection 那样每次弹窗让用户授权，也不用 ADB——没配特权进程的用户也能用。
 *
 * ## 限制
 *
 * - 两次截图至少隔 333ms（系统写死的 `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS`），
 *   太快会失败。Agent 几秒才截一张，碰不到。
 * - 带 `FLAG_SECURE` 的窗口（支付、银行、部分聊天的隐私模式）截出来是黑的。这是系统层面的
 *   限制，副屏截图也一样。
 *
 * 截出来的是一块显存里的硬件位图，这里拷回内存、缩小、压成 JPEG，和副屏截图出来的样子一致。
 */
object A11yScreenshot {

    private const val TAG = "KittenA11yShot"

    /** 截图的结果。失败时 [Failed.message] 是给模型读的一行，和 [ScreenError.describe] 一个路子。 */
    sealed interface Shot {
        class Ok(val jpeg: ByteArray) : Shot
        class Failed(val message: String) : Shot
    }

    /** 系统回调回来的原样结果，还没处理。 */
    private sealed interface Raw {
        class Success(val result: AccessibilityService.ScreenshotResult) : Raw
        class Failure(val errorCode: Int) : Raw
    }

    /**
     * 截 [displayId] 这块屏，长边缩到 [maxEdge]、按 [quality] 压成 JPEG。
     */
    suspend fun capture(displayId: Int, maxEdge: Int, quality: Int): Shot {
        val service = KittenAccessibilityService.peek()
            ?: return Shot.Failed(ScreenError.ServiceUnavailable.describe())

        val raw = try {
            // 类型参数不能省：两个回调分别 resume 了 Success 和 Failure，不写的话会被推成 Nothing。
            suspendCancellableCoroutine<Raw> { continuation ->
                // 回调在哪个线程跑都行，它只是把结果交给协程。
                service.takeScreenshot(
                    displayId,
                    Executor(Runnable::run),
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            // 协程已经被取消（用户喊停）就没人收了，显存里那块缓冲得自己还掉。
                            if (continuation.isActive) {
                                continuation.resume(Raw.Success(screenshot))
                            } else {
                                screenshot.hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(Raw.Failure(errorCode))
                        }
                    },
                )
            }
        } catch (e: SecurityException) {
            // 配置里声明了 canTakeScreenshot，但服务还没拿到这项能力。
            Log.w(TAG, "无障碍服务没有截屏能力", e)
            return Shot.Failed(
                "ERROR no_screenshot_capability: 无障碍服务没有截屏能力。刚更新过 Kitten 的话，" +
                    "可能要在系统设置里把 Kitten 的无障碍关掉再打开一次。这次先改用 read_screen。",
            )
        }

        return when (raw) {
            is Raw.Failure -> Shot.Failed(describeFailure(displayId, raw.errorCode))
            is Raw.Success -> withContext(Dispatchers.Default) { encode(raw.result, maxEdge, quality) }
        }
    }

    /** 硬件位图 → 内存里的位图 → 缩小 → JPEG。不管成没成功，显存缓冲都要还掉。 */
    private fun encode(shot: AccessibilityService.ScreenshotResult, maxEdge: Int, quality: Int): Shot {
        val buffer = shot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, shot.colorSpace)
                ?: return Shot.Failed("ERROR capture_failed: 截到的画面解不出来，改用 read_screen。")
            // 硬件位图的像素在显存里，不能直接缩放、压缩，先拷一份到内存。
            val full = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            if (full == null) {
                return Shot.Failed("ERROR capture_failed: 截到的画面拷不出来，改用 read_screen。")
            }
            val scaled = scaleToMaxEdge(full, maxEdge)
            if (scaled !== full) full.recycle()
            val jpeg = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            scaled.recycle()
            Shot.Ok(jpeg)
        } catch (e: Exception) {
            Log.w(TAG, "处理截图失败", e)
            Shot.Failed("ERROR capture_failed: 处理截图时出错了（${e.message}），改用 read_screen。")
        } finally {
            buffer.close()
        }
    }

    /** 长边超过 [maxEdge] 就等比缩下来，没超过原样返回。 */
    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun describeFailure(displayId: Int, errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "ERROR too_frequent: 截得太快了，系统要求两次截图至少隔半秒左右。先 wait 一下再截。"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "ERROR invalid_display: display=$displayId 截不了，这块屏不存在或者不允许截。"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "ERROR no_access: 系统不让无障碍服务截这块屏。改用 read_screen。"
        else ->
            "ERROR capture_failed: 系统截图失败（错误码 $errorCode）。可以过一两秒再试，或者改用 read_screen。"
    }
}
