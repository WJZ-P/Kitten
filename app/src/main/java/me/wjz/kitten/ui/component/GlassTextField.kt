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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import me.wjz.kitten.ui.theme.KittenColors
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 玻璃输入框。
 *
 * 和卡片是同一种玻璃（[glassEffects]）：边缘一样会把背景掰弯、带一点色散。区别在于它是
 * **凹下去的**——上沿一道很淡的内阴影，不投外阴影，底色比卡片略白一点。浮起来的是可以
 * 按的，凹下去的是可以填的，一眼就能分清。
 *
 * 玻璃折射不到另一块玻璃（见 [GlassBackground]），所以它透过卡片看到的是背景本身，
 * 不是卡片——这正好：嵌在卡片里的输入框像是在卡片上开了一扇更清透的窗。
 *
 * 聚焦时边框和标签变成强调色，底部一道强调色的线从起始边延伸到整条。
 *
 * Material 的 `TextField` 自带不透明容器，会把玻璃效果整个盖住，所以基于
 * [BasicTextField] 自己画。
 */
@Composable
fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    backdrop: Backdrop,
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
                        .glassWell(backdrop = backdrop, colors = colors, active = focusProgress)
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

/**
 * 输入框那种凹下去的玻璃槽。[GlassTextField] 和 [GlassDropdown] 收起时共用这一个样子。
 *
 * @param active 0..1，聚焦（或者菜单展开）的程度：边框变成强调色，底部一道强调色的线
 *   从起始边延伸到整条。
 * @param onDrawSurface 画在底色上面、内容下面，比如按压时的光雾。
 */
internal fun Modifier.glassWell(
    backdrop: Backdrop,
    colors: KittenColors,
    active: Float,
    onDrawSurface: DrawScope.() -> Unit = {},
): Modifier = this
    .drawBackdrop(
        backdrop = backdrop,
        shape = { SmallShape },
        effects = { glassEffects(GlassRadius.Small) },
        highlight = { GlassRim },
        // 凹进卡片里的东西不投外阴影。
        shadow = null,
        innerShadow = { WellInnerShadow },
        onDrawSurface = {
            drawWell(active, colors)
            onDrawSurface()
        },
    )
    .border(
        width = 1.dp,
        color = lerp(colors.hairline, colors.accent.copy(alpha = 0.8f), active.coerceIn(0f, 1f)),
        shape = SmallShape,
    )

private fun DrawScope.drawWell(active: Float, colors: KittenColors) {
    drawRect(colors.controlTint)
    if (active > 0.001f) {
        val lineHeight = 2.dp.toPx()
        val width = size.width * active.coerceIn(0f, 1f)
        val left = if (layoutDirection == LayoutDirection.Ltr) 0f else size.width - width
        drawRect(
            brush = Brush.horizontalGradient(listOf(colors.accentLight, colors.accentDeep)),
            topLeft = Offset(left, size.height - lineHeight),
            size = Size(width, lineHeight),
        )
    }
}

/** 上沿一道很淡的内阴影：槽是凹进卡片里的。 */
private val WellInnerShadow = InnerShadow(radius = 5.dp, color = Color.Black.copy(alpha = 0.10f))
