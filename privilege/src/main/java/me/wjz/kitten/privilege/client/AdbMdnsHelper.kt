package me.wjz.kitten.privilege.client

import android.content.Context
import io.github.muntashirakon.adb.android.AdbMdns

/** 用 mDNS 发现无线调试的端口。配对服务和连接服务的 serviceType 不同。 */
internal object AdbMdnsHelper {

    /**
     * 持续监听 [serviceType] 的广播，每拿到一个有效端口就回调 [onPort]（可能回调多次）。
     *
     * 配对服务 (SERVICE_TYPE_TLS_PAIRING) 只在系统「配对弹窗」打开期间才广播，
     * 而且每次重新打开弹窗，端口都会换一个，所以这里不是一次性的发现而是持续监听。
     *
     * 回调发生在 NsdManager 的工作线程上。返回值是停止监听的函数，调用方用完要调一次。
     */
    fun observePort(context: Context, serviceType: String, onPort: (Int) -> Unit): () -> Unit {
        val mdns = AdbMdns(context, serviceType) { _, port ->
            if (port > 0) onPort(port)
        }
        mdns.start()
        return { mdns.stop() }
    }
}
