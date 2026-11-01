package me.wjz.kitten.ui.theme

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

/**
 * 用户在 设置 → 字号 里调的倍数：整个 App 的字都乘它，乘在系统字号设置上面（系统调大了、这里再调大，
 * 两个都算）。只管字（sp），不管布局（dp）：卡片、按钮的大小不变，字大了就多折几行。
 *
 * 在 [KittenTheme] 里套进 LocalDensity 的 fontScale，所有 Text、Markdown、输入框一起变，不用每个
 * 组件各自去乘。
 *
 * 存在 SharedPreferences 里，第一次用到时读一次，之后改了就推一份新的。
 */
object FontScale {

    private const val PREFS = "kitten_ui"
    private const val KEY = "font_scale"

    /** 标准大小。 */
    const val DEFAULT = 1f

    /**
     * 能调到多小、多大。往下给得少：再小就看不清了。往上到 1.3：顶栏、底部栏、按钮的高度是定死的，
     * 字再大就会顶出去被裁掉；要更大的，系统的字号设置还能再往上加。
     */
    const val MIN = 0.85f
    const val MAX = 1.3f

    /** 一档多少：5% 一档，从小到大十档，拖起来一格一格的，看得出每一档的差别。 */
    const val STEP = 0.05f

    private val state = MutableStateFlow(DEFAULT)
    private var loaded = false

    /** 现在的倍数。第一次调用时从存着的读出来。 */
    fun state(context: Context): StateFlow<Float> {
        if (!loaded) {
            loaded = true
            state.value = prefs(context).getFloat(KEY, DEFAULT).coerceIn(MIN, MAX)
        }
        return state.asStateFlow()
    }

    fun set(context: Context, value: Float) {
        val snapped = ((value / STEP).roundToInt() * STEP).coerceIn(MIN, MAX)
        prefs(context).edit { putFloat(KEY, snapped) }
        loaded = true
        state.value = snapped
    }

    /** 给人看的说法："标准"，或者"110%"。 */
    fun describe(value: Float): String {
        val percent = (value * 100).roundToInt()
        return if (percent == 100) "标准" else "$percent%"
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
