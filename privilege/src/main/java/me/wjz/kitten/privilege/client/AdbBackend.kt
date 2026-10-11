package me.wjz.kitten.privilege.client

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.wjz.kitten.privilege.Starter

/**
 * 通过一个已连接的 ADB 管理器，在 shell 里执行启动命令。
 *
 * 命令用 app_process 以 shell 身份拉起特权进程，进程随后通过 ContentProvider 把 binder 交回。
 */
internal class AdbBackend(
    private val manager: AbsAdbConnectionManager,
) {

    /**
     * 执行 [command]，并给 shell 回显一点时间落进 Logcat。
     *
     * 这里**不以读到 EOF 作为完成条件**。实测即使 shell 已经正常退出（设备上查不到
     * 残留进程、adb 自带客户端跑同样的命令会立刻返回），libadb 的输入流也不会及时
     * EOF，死等就会白白拖住启动流程——之前踩过一次更严重的：PTY 被后台进程占着时
     * 这里会永久阻塞，现象是状态已经 Ready 了，进度却一直停在"启动特权进程中"。
     *
     * 回显只用于诊断，真正判断"特权进程是否起来了"看 PrivilegeManager.state。
     * 所以这里只等一小段时间，到点就收摊。
     */
    suspend fun launch(command: String): Unit = withContext(Dispatchers.IO) {
        val stream = manager.openStream(LocalServices.SHELL)
        val readerScope = CoroutineScope(Dispatchers.IO)
        val reader = readerScope.launch {
            runCatching {
                stream.openInputStream().bufferedReader().use { r ->
                    r.lineSequence().forEach { line -> Log.d(TAG, "shell: $line") }
                }
            }
        }
        try {
            stream.openOutputStream().use { os ->
                os.write((command + "\n").toByteArray())
                os.flush()
                // 命令已在子 shell 后台启动，关掉 stdin 让这条 shell 退出。
                os.write("exit\n".toByteArray())
                os.flush()
            }
            // 实测回显在 100ms 内就全到了，这里给足余量。
            if (withTimeoutOrNull(OUTPUT_GRACE_MS) { reader.join() } == null) {
                Log.d(TAG, "shell 回显未读到结尾，按预期收尾")
            }
        } finally {
            // 关掉 stream 既是释放资源，也能把可能还阻塞在 read 的线程踢醒。
            runCatching { stream.close() }
            readerScope.cancel()
        }
    }

    private companion object {
        const val TAG = "KittenAdbShell"
        const val OUTPUT_GRACE_MS = 1_500L
    }
}
