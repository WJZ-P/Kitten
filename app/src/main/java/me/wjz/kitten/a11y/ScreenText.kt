package me.wjz.kitten.a11y

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 一帧画面：位图，和位图上 1 像素对应屏幕上几像素（[scale]）。认出来的字的位置乘上它，就是点击用的屏幕坐标。
 */
class ScreenFrame(val bitmap: Bitmap, val scale: Float)

/**
 * 从画面上认出来、节点树里没有的一行字。
 *
 * @param id 和节点同一套编号，接在这份快照最大的节点编号后面：模型照样拿它 tap，不用分两种编号。
 * @param bounds 屏幕坐标，点击时点它的中间。
 */
data class ScreenTextLine(val id: Int, val text: String, val bounds: Rect)

/**
 * 节点树读不全时补上的屏上文字，见 [ScreenTexts.supplement]。
 *
 * @param reason 树缺了什么，给模型看的半句话，见 [TreeGap]。
 * @param lines 补上的字，从上到下、从左到右。
 * @param failure 一行也没补上时为什么（截不到画面、没认出字、认字超时）。只在树里一个字都没有时会带着它，
 *   见 [ScreenTexts.supplement]。
 * @param omitted 认出来太多、没列出来的行数，见 ScreenTexts 的 MAX_LINES。
 */
data class ScreenText(
    val reason: String,
    val lines: List<ScreenTextLine>,
    val failure: String? = null,
    val omitted: Int = 0,
)

/**
 * 节点树读不全时，从画面上认字补上。
 *
 * ## 什么时候认
 *
 * 只在 [TreeQuality] 判出树不够用时才截图认字：树够用的页面（绝大多数）一点额外开销都没有。认一次要截一张
 * 图再跑一遍识别，几百毫秒到一秒多。
 *
 * ## 补哪些字
 *
 * 只补树里没有的。认出来的每一行，先去掉认错的（[legible]），再去掉树里已经有的：位置和某个节点叠在一起、
 * 字也对得上的，模型在节点树里已经看到了，再列一遍只会让它分不清该点哪个。密码框那块认出来的一律不要。
 *
 * 认完一行都不剩的话什么都不补，树其实是够用的，判得宽了而已。只有树里一个字都没有时，才告诉模型画面上
 * 也没认出字、为什么，它才知道该等一等、换个办法，而不是一遍遍重读。
 *
 * ## 隐私
 *
 * 认字在本机做（见 [ScreenOcr]），认出来的字和节点树里的字一样发给模型。日志里只记认了几行、花了多久，
 * 不记认出来的内容。
 */
internal object ScreenTexts {

    private const val TAG = "KittenOcr"

    /**
     * 判一下 [snapshot] 的节点树够不够用，不够就截一帧（[capture]）认字，把树里没有的补进快照（见
     * [UiSnapshot.screenText]）。够用、或者认完没有可补的，原样返回。
     *
     * @param capture 截现在这块屏的一帧。截不到返回 null。
     */
    suspend fun supplement(snapshot: UiSnapshot, capture: suspend () -> ScreenFrame?): UiSnapshot {
        val gap = TreeQuality.gap(snapshot) ?: return snapshot
        val startedAt = SystemClock.elapsedRealtime()
        val read = try {
            withTimeoutOrNull(TIMEOUT_MILLIS) { recognize(capture) } ?: Recognized.Failed("认字超时")
        } catch (e: CancellationException) {
            // 用户喊停了。超时不会走到这里（withTimeoutOrNull 自己接住了）。
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "认字出错", e)
            Recognized.Failed("认字出错")
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt

        return when (read) {
            is Recognized.Failed -> {
                Log.i(TAG, "节点树读不全（${gap.reason}），没补上：${read.reason}，${elapsed}ms")
                if (gap.empty) snapshot.copy(screenText = ScreenText(gap.reason, emptyList(), read.reason)) else snapshot
            }
            is Recognized.Ok -> {
                val missing = missingFromTree(snapshot, read.lines.filter(::legible))
                Log.i(TAG, "节点树读不全（${gap.reason}），认出 ${read.lines.size} 行、补上 ${missing.size} 行，${elapsed}ms")
                when {
                    missing.isNotEmpty() -> snapshot.copy(screenText = numbered(snapshot, gap, missing))
                    gap.empty -> snapshot.copy(screenText = ScreenText(gap.reason, emptyList(), "画面上没认出字"))
                    else -> snapshot
                }
            }
        }
    }

    /** 认字的结果：认出来的行（已经换成屏幕坐标），或者没认成的原因（给模型看的半句话）。 */
    private sealed interface Recognized {
        class Ok(val lines: List<ScreenOcr.Line>) : Recognized
        class Failed(val reason: String) : Recognized
    }

    private suspend fun recognize(capture: suspend () -> ScreenFrame?): Recognized {
        val frame = capture() ?: return Recognized.Failed("截不到画面")
        val lines = ScreenOcr.read(frame.bitmap)
        return Recognized.Ok(lines.map { line -> ScreenOcr.Line(line.text, line.bounds.scaled(frame.scale), line.confidence) })
    }

    /**
     * 认错的不要：置信度太低的；一个字母、数字都没有的；符号比字还多的（分隔线、箭头）；单独一个不是汉字的
     * 字母（放大镜常被认成 Q，叉号认成 X）。单独一个数字留着：角标上的未读数就是这样。
     */
    private fun legible(line: ScreenOcr.Line): Boolean {
        if (line.confidence != null && line.confidence < MIN_CONFIDENCE) return false
        val text = line.text.filterNot(Char::isWhitespace)
        val word = text.count(Char::isLetterOrDigit)
        if (word == 0 || text.length - word > word) return false
        val single = text.singleOrNull()
        return single == null || !single.isLetter() || single.isHan()
    }

    /** 去掉节点树里已经有的、落在密码框上的，按从上到下、从左到右排好。 */
    private fun missingFromTree(snapshot: UiSnapshot, lines: List<ScreenOcr.Line>): List<ScreenOcr.Line> {
        val nodes = snapshot.rawRoots.flatMap { root -> root.findAll { it.visibleToUser && !it.bounds.isEmpty } }
        val secrets = nodes.filter { it.password }.map { it.bounds }
        val known = nodes.filter { it.hasLabel }.map { node ->
            node.bounds to listOfNotNull(node.text, node.contentDescription, node.hintText).map(::key).filter { it.isNotEmpty() }
        }
        return lines
            .filter { line ->
                val value = key(line.text)
                value.isNotEmpty() &&
                    secrets.none { overlap(line.bounds, it) >= SECRET_OVERLAP } &&
                    known.none { (bounds, keys) -> overlap(line.bounds, bounds) >= KNOWN_OVERLAP && keys.any { same(value, it) } }
            }
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    /** 编上号：接在这份快照最大的节点编号后面。太多的只留前 [MAX_LINES] 行。 */
    private fun numbered(snapshot: UiSnapshot, gap: TreeGap, lines: List<ScreenOcr.Line>): ScreenText {
        var maxId = -1
        snapshot.rawRoots.forEach { root -> root.walk { maxId = max(maxId, it.id) } }
        val kept = lines.take(MAX_LINES).mapIndexed { index, line ->
            ScreenTextLine(id = maxId + 1 + index, text = line.text, bounds = line.bounds)
        }
        return ScreenText(gap.reason, kept, omitted = lines.size - kept.size)
    }

    /** 比字用的样子：只留字母和数字（汉字也算字母），英文不分大小写。 */
    private fun key(text: String): String = text.lowercase().filter(Char::isLetterOrDigit)

    /**
     * 两段字算不算同一处：一样，或者一段包含另一段（至少两个字）。节点上常是一整段话，认出来的是其中一行；
     * 也常反过来，认字把挨着的两个控件连成了一行。
     */
    private fun same(a: String, b: String): Boolean =
        a == b || (a.length >= 2 && b.contains(a)) || (b.length >= 2 && a.contains(b))

    /** [a] 有多大比例落在 [b] 里（按 [a] 自己的面积算：大容器不会因为面积大就把小行字算成重合）。 */
    private fun overlap(a: Rect, b: Rect): Double {
        val area = a.width().toLong() * a.height()
        if (area <= 0L) return 0.0
        val intersection = Rect()
        if (!intersection.setIntersect(a, b)) return 0.0
        return intersection.width().toLong() * intersection.height() / area.toDouble()
    }

    private fun Rect.scaled(scale: Float): Rect = Rect(
        (left * scale).roundToInt(),
        (top * scale).roundToInt(),
        (right * scale).roundToInt(),
        (bottom * scale).roundToInt(),
    )

    private fun Char.isHan(): Boolean = Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

    /**
     * 截图加认字最多等多久（毫秒）。超了就不补，照常把节点树交出去：认字是补充，不能挡着模型的下一步。第一次
     * 用要把识别模型加载进来，会比平常慢几百毫秒，所以给得比一般一次认字（几百毫秒到一秒）宽。
     */
    private const val TIMEOUT_MILLIS = 4_000L

    /** 置信度低于这个的行不要（模型给了置信度时）。认错的字多在 0.3 以下，正常的字基本在 0.6 以上。 */
    private const val MIN_CONFIDENCE = 0.4f

    /** 一行字有这么多落在某个有字的节点上、字也对得上，就算节点树里已经有了。 */
    private const val KNOWN_OVERLAP = 0.5

    /** 一行字有这么多落在密码框上就不要：宁可多扔，密码不能从画面上漏出去。 */
    private const val SECRET_OVERLAP = 0.3

    /**
     * 最多补多少行。一屏字多的页面（长文章的截图）能认出上百行，全列出来很费 token；补的字是让模型找得到
     * 东西点，不是让它读全文，读全文它可以滚着看。
     */
    private const val MAX_LINES = 60
}

/** 长边超过 [maxEdge] 就等比缩下来，没超过原样返回。 */
internal fun Bitmap.scaledToMaxEdge(maxEdge: Int): Bitmap {
    val longest = max(width, height)
    if (longest <= maxEdge) return this
    val ratio = maxEdge.toFloat() / longest
    return Bitmap.createScaledBitmap(
        this,
        (width * ratio).roundToInt().coerceAtLeast(1),
        (height * ratio).roundToInt().coerceAtLeast(1),
        true,
    )
}
