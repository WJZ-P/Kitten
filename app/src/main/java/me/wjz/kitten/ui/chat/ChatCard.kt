package me.wjz.kitten.ui.chat

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.shadow.Shadow
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassRim
import me.wjz.kitten.ui.theme.KittenColors

/**
 * 对话里"卡片"（问答卡片、任务卡片）共用的那层玻璃。
 *
 * 要和模型的回复（直接铺在页面上的字）、用户的气泡一眼分开：底色深一档（chatCardTint），上半截一道
 * 渐隐的亮光，再垫一层软阴影，浮在页面上，像一张实在的卡片。不带尾巴：它不是谁说的一句话。
 *
 * 玻璃只模糊、不折射，和气泡一样（见 [ChatBubble]）：卡片在消息列表里跟着滚，折射每一帧都要重算。
 *
 * @param scale 卡片的缩放，按下时鼓起一点用。只在绘制阶段读，按的时候不重组。
 */
internal fun Modifier.chatCardGlass(
    backdrop: Backdrop,
    colors: KittenColors,
    scale: () -> Float = { 1f },
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { ChatCardShape },
    effects = { blur(BubbleBlur.toPx()) },
    highlight = { GlassRim },
    shadow = { ChatCardShadow },
    layerBlock = {
        val s = scale()
        scaleX = s
        scaleY = s
    },
    onDrawSurface = {
        drawRect(colors.chatCardTint)
        // 上半截一道渐隐的亮光：没有它，深一档的底色看起来只是一块色块。
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    colors.controlTint.copy(alpha = colors.controlTint.alpha * ChatCardSheenAlpha),
                    Color.Transparent,
                ),
                endY = size.height * 0.5f,
            ),
        )
    },
)

/** 卡片的圆角，和气泡、提问卡片同一档。 */
internal val ChatCardShape = RoundedCornerShape(GlassRadius.Large)

/** 卡片的内边距，和提问卡片一样，几张卡片看起来是一套。 */
internal val ChatCardPadding = 14.dp

/**
 * 卡片底下的软阴影，让它浮起来一点、和贴着页面的气泡分开。比按钮的阴影（8dp、0.12）散得开、
 * 淡一点：卡片面积大，阴影浓了像一块脏印子。
 */
private val ChatCardShadow = Shadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.08f))

/** 卡片上半截那道亮光最亮处有多亮（乘在 controlTint 那层白上）。 */
private const val ChatCardSheenAlpha = 0.6f
