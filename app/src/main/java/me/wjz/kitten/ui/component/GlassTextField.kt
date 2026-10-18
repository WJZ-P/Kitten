package me.wjz.kitten.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 玻璃输入框。
 *
 * ## 为什么是"凹下去的槽"而不是又一块玻璃
 *
 * 输入框总是放在分组卡片里面。卡片已经是一块浮起来的玻璃了，里面再浮一块玻璃会让
 * 层级乱掉——而且玻璃折射不到另一块玻璃（见 [GlassBackground]），叠起来也没有效果。
 * 所以输入框反过来做成往下凹的槽：上沿一道内阴影、一圈细边。浮起来的是可以按的，
 * 凹下去的是可以填的，一眼就能分清。
 *
 * 聚焦时底部有一道强调色的线从起始边延伸到整条，标签同时变成强调色。
 *
 * Material 的 `TextField` 自带不透明容器，会把玻璃效果整个盖住，所以基于
 * [BasicTextField] 自己画。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    val colors = KittenTheme.colors
    var focused by remember { mutableStateOf(false) }
    val focusProgress by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
        label = "fieldFocus",
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = lerp(colors.textSecondary, colors.accentStrong, focusProgress),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accentStrong),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MediumShape)
                        .drawBehind { drawWell(focusProgress, colors) }
                        .border(
                            width = 1.dp,
                            color = lerp(colors.hairline, colors.accent.copy(alpha = 0.8f), focusProgress),
                            shape = MediumShape,
                        )
                        .heightIn(min = 44.dp)
                        .padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textTertiary,
                            )
                        }
                        innerTextField()
                    }
                    trailing?.invoke()
                }
            },
        )
    }
}

private fun DrawScope.drawWell(focus: Float, colors: KittenColors) {
    drawRect(colors.inputWell)
    // 内阴影：上沿一道暗色渐隐，看起来是往下凹的，而不是浮起来的。
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Black.copy(alpha = 0.05f), Color.Transparent),
            startY = 0f,
            endY = 6.dp.toPx(),
        ),
    )
    if (focus > 0.001f) {
        // 聚焦线从起始边延伸出来。
        val lineHeight = 2.dp.toPx()
        val width = size.width * focus.coerceIn(0f, 1f)
        val left = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width
        drawRect(
            brush = Brush.horizontalGradient(listOf(colors.accentLight, colors.accentDeep)),
            topLeft = Offset(left, size.height - lineHeight),
            size = Size(width, lineHeight),
        )
    }
}
