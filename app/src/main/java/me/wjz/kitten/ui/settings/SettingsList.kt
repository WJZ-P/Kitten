package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.detectGlassPress
import me.wjz.kitten.ui.component.rememberGlassPressState
import me.wjz.kitten.ui.theme.KittenTheme

/*
 * 设置页的分组列表：卡片外面一行灰色小字是分组名，卡片里一行一个入口。
 *
 * 和以前"一张大卡片一个功能、东西全摊开"不同，首页只放入口，点进去才是具体内容（二级页）。
 * 一屏能看全所有设置，找东西靠扫一眼行首的彩色图标。
 */

/**
 * 分组名：卡片上方一行灰色小字（"模型""权限与能力"）。
 *
 * 首页的分组是一串入口，分组名只是给这一串起个名，放在卡片外面、压得很轻，卡片里就只剩整齐的
 * 一行行。二级页里那些装着具体内容的卡片照旧把标题写在卡片里（[GlassSection] 的表头）。
 */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = KittenTheme.colors.textTertiary,
        modifier = modifier.padding(start = GroupLabelInset, bottom = 6.dp),
    )
}

/**
 * 一组入口：一块玻璃，行与行之间由调用方放 GlassDivider。
 *
 * 卡片本身不做按压反馈：能按的是里面的每一行，整块一起鼓起来会分不清按的是哪行。
 */
@Composable
internal fun SettingsGroup(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSection(backdrop = backdrop, modifier = modifier, interactive = false, content = content)
}

/**
 * 列表里的一行：彩色图标、标题、一行灰色说明，右边是状态文字和（能点进去的话）一个"›"。
 *
 * 整行都能点：按下垫一层淡天依蓝，松手才算数，和工具清单的行一样。没有 [onClick] 的行只是展示，
 * 不响应。
 *
 * @param iconTint 图标的颜色，取 KittenColors 里 icon 开头的那一组：一类一个颜色。
 * @param subtitleColor 说明文字的颜色。没配好、要用户注意的时候给 warning。
 * @param trailingText 右边的状态文字（"已开启""去开启"）。
 * @param showChevron 右边画不画"›"。点了进二级页的行画，点了只是去系统设置开个开关的行也画——
 *   都表示"点这里会去别的地方"。
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleColor: Color = KittenTheme.colors.textSecondary,
    trailingText: String? = null,
    trailingColor: Color = KittenTheme.colors.textTertiary,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = KittenTheme.colors
    val press = rememberGlassPressState()
    val currentOnClick by rememberUpdatedState(onClick)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics(mergeDescendants = true) {
                            role = Role.Button
                            onClick(label = title) {
                                currentOnClick?.invoke()
                                true
                            }
                        }
                        .pointerInput(press) { detectGlassPress(press) { currentOnClick?.invoke() } }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .drawBehind {
                val pressed = press.pressProgress.coerceIn(0f, 1f)
                if (pressed > 0f) drawRect(colors.sliderIdle, alpha = pressed)
            }
            .heightIn(min = RowMinHeight)
            .padding(horizontal = GlassContentPadding, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RowIconGap),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(RowIconSize),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall,
                color = trailingColor,
                maxLines = 1,
            )
        }
        if (showChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(ChevronSize),
            )
        }
    }
}

/**
 * 页面顶上的标题。二级页左边多一个返回按钮（玻璃小圆，和对话页顶栏的按钮一样）；首页没有。
 *
 * 系统的返回手势同样能回到上一级，见 SettingsScreen 里的 BackHandler。
 */
@Composable
internal fun SettingsPageHeader(
    title: String,
    backdrop: Backdrop,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = HeaderHeight)
            .padding(start = if (onBack == null) GroupLabelInset else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onBack != null) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                backdrop = backdrop,
                size = BackButtonSize,
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = KittenTheme.colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** 一行至少多高：一行标题加一行说明放得下，也好按。 */
private val RowMinHeight = 56.dp

/** 行首图标多大、和文字隔多远。 */
private val RowIconSize = 22.dp
private val RowIconGap = 14.dp

/** 行尾"›"多大。 */
private val ChevronSize = 18.dp

/** 分组名、首页标题往里缩多少：和卡片里的文字大致对齐。 */
private val GroupLabelInset = 6.dp

/** 页面标题那一行多高、返回按钮多大（和对话页顶栏的按钮一样）。 */
private val HeaderHeight = 48.dp
private val BackButtonSize = 40.dp
