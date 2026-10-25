package me.wjz.kitten.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 读一张图片给界面用，解出来之前是 null。
 *
 * - 按 [maxSizePx]（长边）解码：几千万像素的原图不会整张读进内存；
 * - 在后台线程解码，解好的放进内存缓存（[ImageCache]），列表滚回来、同一张图再出现时直接拿，
 *   不闪一下空白；
 * - 解不出来（文件被删了、授权过期了）就一直是 null，调用方显示占位。
 *
 * [uri] 可以是相册给的 content:// 地址，也可以是自己存的文件（`Uri.fromFile`）。
 *
 * 没引 Coil 这类图片库：现在要的只是"按大小解码 + 内存缓存"，几十行就够，不值得多一个依赖。
 * 以后要网络图片、磁盘缓存、动图，再换。
 */
@Composable
fun rememberImageBitmap(uri: Uri, maxSizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val image by produceState(initialValue = ImageCache.get(uri, maxSizePx), uri, maxSizePx) {
        // 换了图片时旧值还留着，先换成新图的缓存（没有就是 null，显示占位），再去解。
        value = ImageCache.get(uri, maxSizePx)
        if (value == null) value = ImageCache.load(context, uri, maxSizePx)
    }
    return image
}

/** 解码好的图片的内存缓存，按占用的字节数算大小。 */
internal object ImageCache {

    private const val TAG = "KittenImage"

    /** 最多占可用内存的 1/8，和系统建议的图片缓存一个量级。 */
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun key(uri: Uri, maxSizePx: Int) = "$uri@$maxSizePx"

    fun get(uri: Uri, maxSizePx: Int): ImageBitmap? = cache.get(key(uri, maxSizePx))?.asImageBitmap()

    suspend fun load(context: Context, uri: Uri, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            // ImageDecoder 会按 EXIF 把照片转正；默认解成硬件位图，只用来画，省内存也画得快。
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = maxSizePx.toFloat() / max(width, height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (width * scale).roundToInt().coerceAtLeast(1),
                        (height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
        }.onFailure {
            Log.w(TAG, "图片解不出来：$uri", it)
        }.getOrNull()?.let { bitmap ->
            cache.put(key(uri, maxSizePx), bitmap)
            bitmap.asImageBitmap()
        }
    }
}
