package me.wjz.kitten.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.wjz.kitten.ui.component.GlassButton
import me.wjz.kitten.ui.component.GlassButtonStyle
import me.wjz.kitten.ui.component.GlassContentPadding
import me.wjz.kitten.ui.component.GlassSection
import me.wjz.kitten.ui.component.LocalToast
import me.wjz.kitten.ui.component.ToastPosition
import me.wjz.kitten.ui.component.ToastTone

/**
 * 临时：把各种 Toast 都弹一遍，调样子用。调好之后连同这个文件一起删。
 *
 * 覆盖 Toast 的每一个变化：短的、长的（换行、停留更久）、三种语气（普通 / 成功 / 出错）、两种位置
 * （底部 / 居中），还有一条被下一条顶掉的过程。文案尽量用 App 里真会出现的，调出来的样子才作数。
 */
@Composable
fun ToastDebugSection(backdrop: Backdrop) {
    val toast = LocalToast.current
    val scope = rememberCoroutineScope()

    GlassSection(
        backdrop = backdrop,
        title = "Toast 调试",
        icon = Icons.Filled.Info,
        footer = "临时：调好 Toast 的样子就删掉。",
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GlassContentPadding)
                .padding(top = 4.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DebugRow(
                backdrop = backdrop,
                leftText = "短提示",
                onLeft = { toast.show("已复制") },
                rightText = "长提示",
                onRight = {
                    toast.show("API 地址少了 /v1，已经自动补上：https://api.example.com/v1。下次就不用再猜了。")
                },
            )
            DebugRow(
                backdrop = backdrop,
                leftText = "成功",
                onLeft = { toast.show("获取到 12 个模型", tone = ToastTone.Success) },
                rightText = "出错",
                onRight = { toast.show("认证失败，请检查 API Key：invalid x-api-key", tone = ToastTone.Error) },
            )
            DebugRow(
                backdrop = backdrop,
                leftText = "居中",
                onLeft = { toast.show("保存好啦", position = ToastPosition.Center, tone = ToastTone.Success) },
                rightText = "居中出错",
                onRight = {
                    toast.show("这一步要输支付密码，只能你自己来哦", position = ToastPosition.Center, tone = ToastTone.Error)
                },
            )
            DebugRow(
                backdrop = backdrop,
                leftText = "用量格子",
                onLeft = { toast.show("10月26日用了 12.3 万 token，缓存命中 78%～") },
                rightText = "连弹两条",
                // 第一条还没收起，第二条就来了：看"旧的先收起、新的再弹出"那一下顺不顺。
                onRight = {
                    scope.launch {
                        toast.show("第一条")
                        delay(ReplaceDelayMillis)
                        toast.show("第二条把第一条顶掉了", tone = ToastTone.Success)
                    }
                },
            )
        }
    }
}

/** 一排两个按钮，各占一半。 */
@Composable
private fun DebugRow(
    backdrop: Backdrop,
    leftText: String,
    onLeft: () -> Unit,
    rightText: String,
    onRight: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GlassButton(
            text = leftText,
            onClick = onLeft,
            backdrop = backdrop,
            style = GlassButtonStyle.Secondary,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        GlassButton(
            text = rightText,
            onClick = onRight,
            backdrop = backdrop,
            style = GlassButtonStyle.Secondary,
            compact = true,
            modifier = Modifier.weight(1f),
        )
    }
}

/** "连弹两条"里第二条隔多久弹（毫秒）：第一条刚弹稳、还没到收起的时候。 */
private const val ReplaceDelayMillis = 700L
