package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.chat.BubbleSide
import me.wjz.kitten.ui.chat.BubbleTextPadding
import me.wjz.kitten.ui.chat.ChatBubble
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.GlassSlider
import me.wjz.kitten.ui.theme.FontScale
import me.wjz.kitten.ui.theme.KittenTheme
import kotlin.math.roundToInt

/**
 * "字号"二级页：上面一段示例对话（用户的气泡 + Kitten 的回复），下面一根滑条，两头各一个小 A、大 A。
 *
 * ## 边拖边看，松手才生效
 *
 * 拖动时只有示例跟着变（[GlassSlider] 的 onPreview）：示例那一块单独套一层字号，按手指底下的那一档排。
 * 松手才存下来、整个 App 一起变（[onScaleChange]）。要是拖的时候整个 App 都跟着变，这一页自己的字也在变，
 * 滑条、标题被挤得上下跳，手指底下的东西一直在动，没法拖。
 *
 * @param scale 现在存着的倍数，见 [FontScale]。
 */
@Composable
fun FontSizePage(
    scale: Float,
    onScaleChange: (Float) -> Unit,
    backdrop: Backdrop,
) {
    val colors = KittenTheme.colors
    // 拖动中手指底下那一档；没在拖时就是存着的那个。
    var preview by remember(scale) { mutableFloatStateOf(scale) }

    GlassSection(backdrop = backdrop, interactive = false) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScaledPreview(applied = scale, preview = preview, backdrop = backdrop)

            Text(
                text = FontScale.describe(preview),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.accentStrong,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FixedSizeA(size = SmallASize)
                GlassSlider(
                    value = scale,
                    onValueChange = onScaleChange,
                    onPreview = { preview = it },
                    backdrop = backdrop,
                    valueRange = FontScale.MIN..FontScale.MAX,
                    steps = ((FontScale.MAX - FontScale.MIN) / FontScale.STEP).roundToInt() - 1,
                    modifier = Modifier.weight(1f),
                )
                FixedSizeA(size = LargeASize)
            }
            Text(
                text = "拖一拖，看看哪种大小读起来最舒服～",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textTertiary,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * 示例对话：按 [preview] 那一档排。外面整个 App 已经乘了 [applied]，这里先除掉它、再乘上 [preview]，
 * 系统自己的字号设置照样算在里面。
 */
@Composable
private fun ScaledPreview(applied: Float, preview: Float, backdrop: Backdrop) {
    val colors = KittenTheme.colors
    val density = LocalDensity.current
    val previewDensity = remember(density, applied, preview) {
        Density(density.density, density.fontScale / applied * preview)
    }
    CompositionLocalProvider(LocalDensity provides previewDensity) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                ChatBubble(
                    side = BubbleSide.End,
                    backdrop = backdrop,
                    tinted = true,
                    modifier = Modifier.widthIn(max = PreviewBubbleMaxWidth),
                ) {
                    Text(
                        text = "今晚想吃点清淡的，帮我看看附近有什么",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onAccent,
                        modifier = Modifier.padding(BubbleTextPadding),
                    )
                }
            }
            Text(
                text = "好呀～Kitten 去外卖软件里翻了翻，楼下那家粥铺评分 4.8，皮蛋瘦肉粥 18 块，要帮你点一份吗？(=^･ω･^=)",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = PreviewReplyInset),
            )
        }
    }
}

/**
 * 滑条两头的 A：一小一大，告诉用户往哪边是变小、往哪边是变大。它们自己不跟着字号变：字号调大了、
 * 两个 A 也跟着大，就看不出"这头小、那头大"了。
 */
@Composable
private fun FixedSizeA(size: Float) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
        Text(
            text = "A",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = size.sp),
            fontWeight = FontWeight.SemiBold,
            color = KittenTheme.colors.textSecondary,
        )
    }
}

/** 示例里用户气泡最宽多少，和对话页一样。 */
private val PreviewBubbleMaxWidth = 280.dp

/** 示例里回复的字往里缩多少，和对话页里回复的字对齐。 */
private val PreviewReplyInset = 8.dp

/** 滑条两头的小 A、大 A 多大（sp，不跟着字号变）。 */
private const val SmallASize = 13f
private const val LargeASize = 22f
