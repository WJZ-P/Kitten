package me.wjz.kitten.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassDivider
import me.wjz.kitten.ui.component.GlassIconButton
import me.wjz.kitten.ui.component.GlassRadius
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.theme.KittenTheme

/*
 * 设置页的分组列表：卡片外面一行灰色小字是分组名，卡片里一行一个入口。
 *
 * 和以前"一张大卡片一个功能、东西全摊开"不同，首页只放入口，点进去才是具体内容（二级页）。
 * 一屏能看全所有设置，找东西靠扫一眼行首的彩色图标。
 *
 * 字号都在文件底部：页面标题 [PageTitleFontSize]、分组名 [GroupLabelFontSize]、行标题
 * [RowTitleFontSize]、行说明 [RowSubtitleFontSize]。
 */

/**
 * 分组名：卡片上方一行灰色小字（"模型""权限与能力"）。
 *
 * 首页的分组是一串入口，分组名只是给这一串起个名，放在卡片外面，卡片里就只剩整齐的一行行。
 * 二级页里那些装着具体内容的卡片照旧把标题写在卡片里（[GlassSection] 的表头）。
 */
@Composable
internal fun SettingsGroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall.copy(fontSize = GroupLabelFontSize, lineHeight = GroupLabelLineHeight),
        fontWeight = FontWeight.Medium,
        color = KittenTheme.colors.textSecondary,
        modifier = modifier.padding(start = GroupLabelInset, bottom = 8.dp),
    )
}

/**
 * 一组入口：一块玻璃，行与行之间由调用方放 [SettingsDivider]。
 *
 * 里面的内容按卡片的圆角裁一刀：行按下时的底色和水波铺满整行，第一行的上两个角、最后一行的
 * 下两个角跟着卡片一起是圆的，中间几行是方的，和卡片严丝合缝，角上不会漏出一块。行不用知道自己
 * 是第几行。
 *
 * 卡片本身不做按压反馈：能按的是里面的每一行，整块一起鼓起来会分不清按的是哪行。
 */
@Composable
internal fun SettingsGroup(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSection(
        backdrop = backdrop,
        modifier = modifier,
        interactive = false,
        // 行自己带着上下留白，卡片不再额外垫一圈：首尾两行贴着卡片边，整张卡才紧凑。
        verticalPadding = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GroupShape),
            content = content,
        )
    }
}

/**
 * 分组里两行之间的分隔线：从文字开头画起，不从图标底下画。图标那一列是连着的，一眼看得出
 * 每行打头是什么；线只把文字一行行隔开，和系统设置一个样子。
 */
@Composable
internal fun SettingsDivider() {
    GlassDivider(modifier = Modifier.padding(start = DividerInset))
}

/**
 * 列表里的一行：彩色图标、标题、一行灰色说明，右边是状态文字和（能点进去的话）一个"›"。
 *
 * ## 按下：安卓的水波
 *
 * 按下时整行铺一层淡淡的天依蓝，同时从手指的位置往外荡开一圈水波（Material 的 ripple）；在这一行
 * 里抬手才算点了它，滑出去、或者开始滚页面就不算。整行铺满、按卡片的圆角裁好（见 [SettingsGroup]），
 * 和卡片严丝合缝。
 *
 * 之前试过按下时浮出一块玻璃透镜：透镜得往里缩一圈、四个角是圆的，和卡片的边、分隔线之间总漏着缝；
 * 改成铺满整行的话，中间几行是直角，透镜的高光会沿着分隔线画出一道白线，直角上的折射也会出尖角。
 *
 * 在能滚动的页面里，水波会晚一点点出现（系统的点按超时，约 0.1 秒）：手指一按下就开始滑的话不亮，
 * 免得滚页面时一路闪过去。这是安卓自己的规矩。
 *
 * 没有 [onClick] 的行只是展示，不响应。
 *
 * @param iconTint 图标的颜色，取 KittenColors 里 icon 开头的那一组：一类一个颜色。
 * @param subtitleColor 说明文字的颜色。没配好、要用户注意的时候给 warning。
 * @param trailingText 右边的状态文字（"已开启""去开启"）。
 * @param trailing 右边放的控件，比如一个开关。排在状态文字后面、"›"前面；它自己接手指，按在它上面
 *   不会算成点了这一行。
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
    trailing: (@Composable () -> Unit)? = null,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = KittenTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = null,
                        indication = ripple(color = colors.accentStrong),
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
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
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = RowTitleFontSize),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = RowSubtitleFontSize),
                    color = subtitleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailingText != null) {
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = RowSubtitleFontSize),
                color = trailingColor,
                maxLines = 1,
            )
        }
        trailing?.invoke()
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
 * 页面顶上的标题，居中。二级页左边多一个返回按钮（玻璃小圆，和对话页顶栏的按钮一样）；首页没有。
 *
 * 二级页的这一行钉在页面顶上，内容在它下面滚；首页的跟着内容一起滚。见 SettingsScreen。
 * 系统的返回手势同样能回到上一级，见 SettingsScreen 里的 BackHandler。
 */
@Composable
internal fun SettingsPageHeader(
    title: String,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = HeaderHeight),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            GlassIconButton(
                icon = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                onClick = onBack,
                backdrop = backdrop,
                size = BackButtonSize,
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = PageTitleFontSize),
            fontWeight = FontWeight.SemiBold,
            color = KittenTheme.colors.textPrimary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                // 左右都让出返回按钮的触摸区，标题再长也不会压到按钮上，而且左右对称、正好居中。
                .padding(horizontal = HeaderSideInset)
                .semantics { heading() },
        )
    }
}

// ---- 字号：想调大调小改这几个 ----

/** 页面标题（"设置""模型服务商"）。 */
private val PageTitleFontSize = 22.sp

/** 分组名（"模型""权限与能力"）。比行标题小一档、颜色浅一档，但不能小到看不清。 */
private val GroupLabelFontSize = 15.sp
private val GroupLabelLineHeight = 20.sp

/** 行标题（"模型服务商""记忆"）。 */
private val RowTitleFontSize = 17.sp

/** 行下面那句说明、右边的状态文字。 */
private val RowSubtitleFontSize = 13.sp

// ---- 尺寸 ----

/** 一行至少多高：一行标题加一行说明放得下，也好按。 */
private val RowMinHeight = 56.dp

/** 行首图标多大、和文字隔多远。 */
private val RowIconSize = 22.dp
private val RowIconGap = 14.dp

/**
 * 分隔线在 GlassDivider 自带的左留白（和行的左留白一样）之外再往右挪多少：正好挪过图标和间距，
 * 从文字开头画起。
 */
private val DividerInset = RowIconSize + RowIconGap

/** 行尾"›"多大。 */
private val ChevronSize = 18.dp

/** 分组名往里缩多少：和卡片里的文字大致对齐。 */
private val GroupLabelInset = 8.dp

/** 标题那一行多高、返回按钮多大（和对话页顶栏的按钮一样）。 */
private val HeaderHeight = 48.dp
private val BackButtonSize = 40.dp

/** 标题左右让出多少：返回按钮的触摸区是 48dp。 */
private val HeaderSideInset = 48.dp

/**
 * 分组卡片的形状，里面的内容按它裁（见 [SettingsGroup]）。必须和 GlassSection 画卡片用的圆角
 * 一样（[GlassRadius.XLarge]），首尾两行按下时的圆角才和卡片对得上。
 */
private val GroupShape = RoundedCornerShape(GlassRadius.XLarge)
