package me.wjz.kitten.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * 设置页式的分组：一整块玻璃，表头（图标 + 标题）、条目、脚注都装在里面。
 *
 * 标题不放在玻璃外面：卡片之间的空白里飘着两个小字，看着像是多出来的，上轻下重；
 * 收进卡片做成表头，一块卡片就是一个完整的单元。
 *
 * 条目之间的分隔线由调用方放 [GlassDivider]：有的分组里是一排列表项，有的是一段说明
 * 加一个按钮，自动插分隔线反而会插错地方。
 */
@Composable
fun GlassSection(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        backdrop = backdrop,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        ) {
            if (title != null) GlassHeader(title = title, icon = icon)
            content()
            if (footer != null) {
                Text(
                    text = footer,
                    style = MaterialTheme.typography.labelSmall,
                    color = KittenTheme.colors.textTertiary,
                    modifier = Modifier.padding(
                        start = GlassContentPadding,
                        end = GlassContentPadding,
                        top = 4.dp,
                        bottom = 10.dp,
                    ),
                )
            }
        }
    }
}

/**
 * 玻璃容器的表头：图标 + 加粗标题，可选的尾部控件（比如模态窗的关闭按钮）。
 *
 * 分组卡片和模态窗共用这一个，两种容器的标题才是同一个样子。
 */
@Composable
internal fun GlassHeader(
    title: String,
    icon: ImageVector?,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = KittenTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (trailing == null) {
                    Modifier.padding(start = GlassContentPadding, end = GlassContentPadding, top = 10.dp, bottom = 4.dp)
                } else {
                    // 尾部控件自带 48dp 的触摸区，右边留白收窄，它的图标才和正文的右边对齐。
                    Modifier.padding(start = GlassContentPadding, end = 4.dp, top = 4.dp)
                },
            )
            .semantics { heading() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.accentStrong,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/**
 * 分组里的一行：可选的图标块、标题和说明、可选的尾部控件（开关、按钮……）。
 *
 * 整行不响应点击——能点的只有尾部那个控件。设置页里"点整行等于点开关"的约定在
 * 玻璃上不好表达按压反馈（整行一起鼓起来会很怪），所以干脆不做。
 *
 * @param supporting 标题下面的一行说明文字。
 * @param supportingContent 说明文字之外还要放的东西（比如状态圆点），排在说明下面。
 */
@Composable
fun GlassListItem(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = GlassContentPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = KittenTheme.colors.textPrimary,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = KittenTheme.colors.textSecondary,
                )
            }
            supportingContent?.invoke()
        }
        trailing?.invoke()
    }
}

/** 分组内的分隔线。左边和条目文字对齐，不顶到卡片边——整条通栏会把卡片切成两块。 */
@Composable
fun GlassDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(start = GlassContentPadding)
            .fillMaxWidth()
            .height(1.dp)
            .background(KittenTheme.colors.hairline),
    )
}

/** 列表项前面的图标块：一小格淡淡的强调色渐变，图标浮在上面。 */
@Composable
fun GlassIconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = KittenTheme.colors
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(SmallShape)
            .background(
                Brush.linearGradient(
                    listOf(colors.accentLight.copy(alpha = 0.30f), colors.accentDeep.copy(alpha = 0.16f)),
                ),
            )
            .border(width = 1.dp, color = colors.hairline, shape = SmallShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.textPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** 状态的语气。决定小圆点和文字的颜色，具体颜色取自主题。 */
enum class GlassStatusTone { Positive, Neutral, Warning }

/**
 * 一个小圆点加一句状态。
 *
 * 颜色只是扫一眼时的辅助，状态本身写在文字里——色弱用户分不清绿和橙，也不影响理解。
 */
@Composable
fun GlassStatus(
    text: String,
    tone: GlassStatusTone,
    modifier: Modifier = Modifier,
) {
    val colors = KittenTheme.colors
    val color = when (tone) {
        GlassStatusTone.Positive -> colors.positive
        GlassStatusTone.Neutral -> colors.textSecondary
        GlassStatusTone.Warning -> colors.warning
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(color, CircleShape),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}
