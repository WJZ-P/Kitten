package me.wjz.kitten.a11y

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 从一帧画面上认字：ML Kit 的中文识别，模型打包在 APK 里，在本机跑，画面不出手机。
 *
 * 只是认字，认出来的哪些要交给模型、编号怎么排，在 [ScreenTexts] 里定。
 *
 * ## 只用中文识别器
 *
 * 中文识别器也认数字（价格、数量这些）。纯英文的字认得怎么样没验证过；要补的话再加一个拉丁文的识别器、
 * 同一帧一起认，体积和耗时都要翻一倍，所以先不加。
 *
 * ## 识别器只建一个
 *
 * 第一次用时把模型加载进来（几百毫秒），之后一直留着，整个进程共用：每次读屏都要用，建了又关划不来。
 */
internal object ScreenOcr {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** 认出来的一行：字、在位图上的位置（位图像素）、置信度（模型没给是 null）。 */
    class Line(val text: String, val bounds: Rect, val confidence: Float?)

    /**
     * 认 [bitmap] 上的字，认完才返回。识别出错抛异常，和"画面上没字"（返回空列表）分开。
     *
     * [bitmap] 交给这里，认完由这里回收：调用方等不及（超时、喊停）先走了的话，识别还在后台读着这块
     * 像素，调用方这时候回收会把它读坏。
     */
    suspend fun read(bitmap: Bitmap): List<Line> = suspendCancellableCoroutine { continuation ->
        val task = try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        task.addOnCompleteListener { done ->
            bitmap.recycle()
            if (!continuation.isActive) return@addOnCompleteListener
            if (done.isSuccessful) {
                continuation.resume(done.result.toLines())
            } else {
                continuation.resumeWithException(done.exception ?: IllegalStateException("文字识别失败"))
            }
        }
    }

    private fun Text.toLines(): List<Line> = textBlocks.flatMap { block ->
        block.lines.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            val value = line.text.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            // 有的识别模型不给置信度（为 0），当作没给，不当作很低。
            Line(value, box, line.confidence.takeIf { it > 0f })
        }
    }
}
