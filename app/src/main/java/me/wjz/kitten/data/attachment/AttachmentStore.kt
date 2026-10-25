package me.wjz.kitten.data.attachment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.wjz.kitten.llm.ImageAttachment
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 消息里图片文件的存放。
 *
 * ## 为什么要自己存一份
 *
 * 系统照片选择器给的地址只是临时授权：App 进程一结束（或者过一阵子）就读不到了。消息要能
 * 一直看、下次对话还要带给模型，所以发送时把图片复制一份到应用私有目录（[DIR_NAME]），消息
 * 里记的是这份的路径。
 *
 * ## 顺手压缩
 *
 * 手机拍的照片动辄四五千像素、好几 MB，原样发给模型又慢又贵（图片按像素算 token），模型那边
 * 也会先缩小再看。所以存的时候就缩到长边 [MAX_EDGE_PX]、压成 JPEG：
 *
 * - 解码用 ImageDecoder，它会按 EXIF 把照片转正，存下来的就是看到的方向；
 * - 重新编码成 JPEG 时，原图里的 EXIF（拍摄地点之类）不会带过来，发给模型的图里没有位置信息；
 * - 带透明的图（PNG 截图）先垫上白底，JPEG 没有透明，不垫的话透明的地方会变成黑的。
 */
class AttachmentStore(context: Context) {

    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, DIR_NAME)

    /** 把一张图片复制、压缩进来。读不出来（格式不认识、授权过期）返回 null。 */
    suspend fun importImage(uri: Uri): ImageAttachment? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(appContext.contentResolver, uri)
            val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                // 要拿像素来重新编码，硬件位图读不了像素。
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val width = info.size.width
                val height = info.size.height
                val scale = MAX_EDGE_PX.toFloat() / max(width, height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
            val opaque = if (decoded.hasAlpha()) decoded.onWhite() else decoded
            dir.mkdirs()
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            ImageAttachment(path = file.absolutePath, width = opaque.width, height = opaque.height)
        }.onFailure {
            Log.w(TAG, "读不出这张图片：$uri", it)
        }.getOrNull()
    }

    /** 删掉这些图片的文件。只删自己目录里的，路径不在这里的一律不碰。 */
    fun delete(images: Collection<ImageAttachment>) {
        images.forEach { image ->
            val file = File(image.path)
            if (file.parentFile?.canonicalPath == dir.canonicalPath) file.delete()
        }
    }

    /** 整个目录清空。数据库重建时用：消息都没了，图片留着就是没人认领的垃圾。 */
    fun deleteAll() {
        dir.deleteRecursively()
    }

    private fun Bitmap.onWhite(): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(this@onWhite, 0f, 0f, null)
        }
        return result
    }

    private companion object {
        const val TAG = "KittenAttachment"
        const val DIR_NAME = "attachments"

        /**
         * 存下来的图片长边最多多少像素。1568 是 Claude 老一些的模型能看的上限，新模型能看到
         * 2576，但图片 token 也要多三倍左右；聊天里看图，1568 已经足够清楚，两家都通用。
         */
        const val MAX_EDGE_PX = 1568

        /** JPEG 质量。85 肉眼看不出损失，文件比 95 小一半左右。 */
        const val JPEG_QUALITY = 85
    }
}
