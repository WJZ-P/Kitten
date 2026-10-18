package me.wjz.kitten.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 小号的可选项，比如服务商预设。
 *
 * 选中时底色渐变成强调色，文字跟着在白和深色之间过渡；按下时和按钮一样亮起光雾。
 *
 * 不用 drawBackdrop：chip 通常一排好几个挤在分组卡片里，每个都开一层折射，GPU 开销
 * 不划算，而且这么小的面积上折射也看不出来。画一层淡白 + 细边就够了。
 */
@Composable
fun GlassChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val press = rememberGlassPressState()
    val glow = rememberGlassGlow { press.pressProgress.coerceIn(0f, 1f) }
    val colors = KittenTheme.colors
    val fill by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "chipFill",
    )

    Box(
        modifier = modifier
            .semantics {
                role = Role.Button
                this.selected = selected
                this.onClick(label = null) {
                    currentOnClick()
                    true
                }
            }
            .pointerInput(press) { detectGlassPress(press) { currentOnClick() } }
            .graphicsLayer {
                // chip 小，按下是往里收而不是鼓起：鼓起会顶到同一排的邻居。
                val s = lerp(1f, 0.96f, press.pressProgress)
                scaleX = s
                scaleY = s
            }
            .clip(SmallShape)
            .drawBehind {
                drawRect(colors.controlTint)
                drawRect(colors.accent, alpha = fill * 0.9f)
                drawGlassGlow(
                    glow = glow,
                    intensity = press.pressProgress.coerceIn(0f, 1f) * 0.7f,
                    center = press.position,
                    colors = if (selected) colors.glowOnAccent else colors.glowOnGlass,
                )
            }
            .border(width = 1.dp, color = colors.hairline, shape = SmallShape)
            .height(32.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = lerp(colors.textPrimary, colors.onAccent, fill),
            maxLines = 1,
        )
    }
}
