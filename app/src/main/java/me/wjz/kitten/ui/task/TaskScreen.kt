package me.wjz.kitten.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 任务页：Agent 的执行记录。
 *
 * **目前是占位。** 它要展示的是每次 Agent 跑任务的回溯——指令是什么、依次调了哪些
 * 工具、每步的参数和结果、成功还是失败、耗时多久。这些数据得等 Agent 循环做出来
 * 才有东西可记，所以先放个空页面占住 tab 的位置，而不是编造假数据。
 *
 * 等 Agent 循环落地后，这里需要：
 * - 一张 `agent_runs` 表（指令、开始/结束时间、结果）和 `agent_steps` 表（工具调用明细）
 * - 对应的 Repository，仍然走接口隔离，和 `SessionRepository` 一个套路
 * - 正在执行的任务要能看到实时进度并中止
 */
@Composable
fun TaskScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "任务",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
        )
        Text(
            text = "Agent 执行记录会出现在这里。\n等接上工具调用之后就有内容了。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
