package me.wjz.kitten.ui.setup

import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wjz.kitten.a11y.CaptureScope
import me.wjz.kitten.a11y.ScreenReader
import me.wjz.kitten.privilege.PrivilegeManager
import me.wjz.kitten.privilege.PrivilegeState
import me.wjz.kitten.privilege.VirtualDisplayManager

/**
 * 临时调试卡片：验证副屏能不能用。
 *
 * **这是一次性的验证工具，验完就删**，所以文案直接硬编码、没走 strings 资源。
 *
 * 副屏现在是 trusted 的，和当初拿 `overlay_display_devices` 测的那块屏在
 * `isCallerAllowedToLaunchOnDisplay` 的判断上等价，所以那批结论这里也成立。
 * 重点看**应用内部的 Activity 跳转还会不会被退回主屏**——淘宝、QQ 这种带
 * splash 的最容易暴露问题，单 Activity 的计算器看不出来。
 *
 * 两个按钮的区别（真机实测过的行为差异）：
 * - 共用实例：目标应用已经在主屏开着时，系统会把它整个 task **搬**到副屏，
 *   于是它从主屏上消失——用户会看见应用凭空不见了。
 * - 新开实例：加 FLAG_ACTIVITY_MULTIPLE_TASK，两块屏各留一个 task。
 *   但进程和数据仍然只有一份，登录态共享。
 */
// ExposedDropdownMenuBox 在当前 Material3 版本里仍是实验性 API。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugCard(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val state by PrivilegeManager.state.collectAsState()
    // 副屏的 density 必须跟主屏一致，不能写死（见 VirtualDisplayManager 的类注释）。
    val density = LocalContext.current.resources.displayMetrics.densityDpi
    var displayId by remember { mutableIntStateOf(VirtualDisplayManager.INVALID_DISPLAY_ID) }
    var result by remember { mutableStateOf("尚未操作") }
    var previewing by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf(TARGET_APPS.first()) }
    var menuOpen by remember { mutableStateOf(false) }

    val ready = state is PrivilegeState.Ready

    fun launchQq(forceNewInstance: Boolean) {
        result = "执行中…"
        scope.launch {
            // binder 调用是阻塞的，别放主线程。
            val id = withContext(Dispatchers.IO) {
                VirtualDisplayManager.launchAppOnNewDisplay(
                    packageName = target.packageName,
                    width = WIDTH,
                    height = HEIGHT,
                    densityDpi = density,
                    forceNewInstance = forceNewInstance,
                )
            }
            displayId = id
            result = if (id == VirtualDisplayManager.INVALID_DISPLAY_ID) {
                "失败，看 KittenPrivDisplay / KittenPrivLauncher 日志"
            } else {
                "成功：displayId=$id（屏幕不可见，用 dumpsys 或无障碍日志确认）"
            }
        }
    }

    /**
     * 抓副屏的节点树，完整内容打到 logcat。
     *
     * 不往界面上显示：一个真实页面几百个节点，几万字符，塞进 Card 里既看不清也滚不动。
     * 界面上只留一行统计，细节去 `logcat -s KittenNodeDump` 看。
     */
    fun dumpNodes() {
        result = "抓取中…"
        scope.launch {
            // 三档各抓一次，直接对比裁剪效果。第一次之后 AccessibilityCache 会命中，
            // 后两次基本不花时间（实测同页面 336ms → 7ms），所以这么测不亏。
            val snapshots = withContext(Dispatchers.IO) {
                // 节点遍历是成百上千次阻塞的跨进程调用，绝不能放主线程。
                CaptureScope.entries.map { it to ScreenReader.capture(displayId, it) }
            }
            result = if (snapshots.all { it.second == null }) {
                "抓取失败：无障碍服务没就绪"
            } else {
                snapshots.forEach { (captureScope, snapshot) ->
                    snapshot ?: return@forEach
                    // Logcat 单条有长度上限，整棵树会被截断，所以按行分批打。
                    Log.i(DUMP_TAG, "==== display=$displayId scope=$captureScope 开始 ====")
                    snapshot.toDebugString().lineSequence().forEach { Log.i(DUMP_TAG, it) }
                    Log.i(DUMP_TAG, "==== display=$displayId scope=$captureScope 结束 ====")
                }
                snapshots.mapNotNull { (captureScope, snapshot) ->
                    snapshot?.let { "$captureScope=${it.nodeCount}" }
                }.joinToString(" / ") + " 节点，详见 logcat -s $DUMP_TAG"
            }
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("副屏调试（临时）", style = MaterialTheme.typography.titleMedium)
            Text(
                if (ready) {
                    "在不可见的副屏上拉起目标应用。画面渲染到离屏缓冲区，用户看不到。"
                } else {
                    "需要先完成 ADB 配对，特权进程就绪后才能用。"
                },
                style = MaterialTheme.typography.bodySmall,
            )
            ExposedDropdownMenuBox(
                expanded = menuOpen,
                onExpandedChange = { menuOpen = !menuOpen },
            ) {
                OutlinedTextField(
                    value = target.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("目标应用") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                ) {
                    TARGET_APPS.forEach { app ->
                        DropdownMenuItem(
                            text = { Text(app.label) },
                            onClick = {
                                target = app
                                menuOpen = false
                            },
                        )
                    }
                }
            }

            Text("结果：$result", style = MaterialTheme.typography.bodyMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { launchQq(forceNewInstance = false) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("共用实例")
                }
                Button(
                    onClick = { launchQq(forceNewInstance = true) },
                    enabled = ready,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("新开实例")
                }
            }
            val hasDisplay = displayId != VirtualDisplayManager.INVALID_DISPLAY_ID

            // 抓的是副屏，所以得先有副屏。主屏抓取走 ScreenReader.capture() 不传参即可。
            Button(
                onClick = { dumpNodes() },
                enabled = hasDisplay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (hasDisplay) "抓取副屏节点树（display=$displayId）" else "抓取副屏节点树（先拉起应用）")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("显示副屏画面", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = previewing,
                    onCheckedChange = { previewing = it },
                    enabled = hasDisplay,
                )
            }

            // 开关打开时才挂 SurfaceView。副屏画面是否可见，取决于它往哪个 Surface
            // 渲染——这里把 SurfaceView 的 Surface 交过去，画面就参与系统合成了；
            // 关掉时切回离屏缓冲，重新隐身。
            if (previewing && hasDisplay) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(WIDTH.toFloat() / HEIGHT),
                    factory = { context ->
                        SurfaceView(context).apply {
                            // 关键：把缓冲区固定成副屏的真实分辨率。
                            // 不设的话缓冲区会跟着 View 在屏幕上的像素大小走（比如 400x711），
                            // 而副屏仍按 1080x1920 渲染——生产者写得比消费者缓冲区大，
                            // 结果是**裁切**而不是缩放，只能看到左上角那一块。
                            // 设成固定尺寸后，由 SurfaceView 在合成阶段把整幅画面缩放到 View 大小。
                            holder.setFixedSize(WIDTH, HEIGHT)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    scope.launch(Dispatchers.IO) {
                                        VirtualDisplayManager.setSurface(displayId, h.surface)
                                    }
                                }

                                override fun surfaceChanged(
                                    h: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) = Unit

                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    // Surface 没了还往上面渲染会出错，立刻切回离屏。
                                    scope.launch(Dispatchers.IO) {
                                        VirtualDisplayManager.hide(displayId)
                                    }
                                }
                            })
                        }
                    },
                )
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        previewing = false
                        withContext(Dispatchers.IO) { VirtualDisplayManager.releaseAll() }
                        displayId = VirtualDisplayManager.INVALID_DISPLAY_ID
                        result = "已释放全部副屏"
                    }
                },
                enabled = ready && hasDisplay,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("释放副屏")
            }
        }
    }
}

/** 一个待验证的目标应用。 */
private data class TargetApp(val label: String, val packageName: String)

/**
 * 验证清单。按"能暴露不同问题"来挑，不是堆数量。
 *
 * 已知结论：计算器完全正常；QQ 的 SplashActivity 会自行退出（进程和 task 都建了，
 * 但窗口自己关掉）——那是应用行为，不是副屏机制的问题。
 */
private val TARGET_APPS = listOf(
    // 基准组：系统自带的简单应用，副屏机制本身有问题的话这几个会先挂
    TargetApp("计算器（基准）", "com.miui.calculator"),
    TargetApp("时钟", "com.android.deskclock"),
    TargetApp("便签", "com.miui.notes"),
    TargetApp("设置", "com.android.settings"),

    // 真实目标：Agent 最终要操作的就是这类社交应用
    TargetApp("微信", "com.tencent.mm"),
    TargetApp("QQ（已知会自退）", "com.tencent.mobileqq"),
    TargetApp("企业微信", "com.tencent.wework"),
    TargetApp("Telegram", "org.telegram.messenger"),

    // 对照组：风控严格 / 重渲染，容易暴露副屏的边界
    TargetApp("支付宝（强风控）", "com.eg.android.AlipayGphone"),
    TargetApp("淘宝", "com.taobao.taobao"),
    TargetApp("抖音（重渲染）", "com.ss.android.ugc.aweme"),
    TargetApp("哔哩哔哩", "tv.danmaku.bili"),
    TargetApp("小红书", "com.xingin.xhs"),
    TargetApp("浏览器", "com.android.browser"),
)
private const val WIDTH = 1080
private const val HEIGHT = 1920

/** 节点树单独一个 tag，免得被链路日志淹掉：`adb logcat -s KittenNodeDump`。 */
private const val DUMP_TAG = "KittenNodeDump"
