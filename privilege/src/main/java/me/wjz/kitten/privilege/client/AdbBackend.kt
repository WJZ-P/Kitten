package me.wjz.kitten.privilege.client

import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.LocalServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通过一个已连接的 ADB 管理器，在 shell 里执行启动命令。
 *
 * 命令用 app_process 以 shell 身份拉起特权进程，进程随后通过 ContentProvider 把 binder 交回。
 */
internal class AdbBackend(
    private val manager: AbsAdbConnectionManager,
) {

    suspend fun launch(command: String): Unit = withContext(Dispatchers.IO) {
        val stream = manager.openStream(LocalServices.SHELL)
        coroutineScope {
            // 读写分离：在不同协程里进行，避免同一线程读写互相阻塞。
            val reader = launch {
                runCatching {
                    stream.openInputStream().bufferedReader().use { r ->
                        r.lineSequence().forEach { line -> Log.d(TAG, "shell: $line") }
                    }
                }
            }
            stream.openOutputStream().use { os ->
                os.write((command + "\n").toByteArray())
                os.flush()
                // 命令已在子 shell 后台启动，关掉 stdin 让这条 shell 退出。
                os.write("exit\n".toByteArray())
                os.flush()
            }
            reader.join()
        }
    }

    private companion object {
        const val TAG = "KittenAdbShell"
    }
}
