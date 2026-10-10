package me.wjz.kitten.privilege.client

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** 用 mDNS 发现无线调试的端口。配对服务和连接服务的 serviceType 不同。 */
internal object AdbMdnsHelper {

    /**
     * 发现 [serviceType] 对应的端口，超时返回 null。
     *
     * 注意：配对服务 (SERVICE_TYPE_TLS_PAIRING) 只在系统"配对对话框"打开时才广播。
     */
    suspend fun discoverPort(
        context: Context,
        serviceType: String,
        timeoutMillis: Long,
    ): Int? {
        val deferred = CompletableDeferred<Int>()
        val mdns = AdbMdns(context, serviceType) { _, port ->
            if (port > 0 && !deferred.isCompleted) deferred.complete(port)
        }
        mdns.start()
        return try {
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            mdns.stop()
        }
    }
}
