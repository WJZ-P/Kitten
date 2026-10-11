package me.wjz.kitten.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.wjz.kitten.ui.setup.SetupActions
import me.wjz.kitten.ui.setup.SetupScreen

/**
 * 应用的根 Composable：负责窗口级脚手架，以及决定当前显示哪个屏幕。
 *
 * 目前只有引导页。以后要加导航（助手主界面、设置等），入口就在这里，
 * MainActivity 不用再跟着改。
 */
@Composable
fun KittenApp(
    actions: SetupActions,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        SetupScreen(
            actions = actions,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
