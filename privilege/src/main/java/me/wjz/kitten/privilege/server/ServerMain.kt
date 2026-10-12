package me.wjz.kitten.privilege.server

import android.os.Looper
import android.util.Log
import kotlin.system.exitProcess

/**
 * 特权进程的入口，由 app_process 以 shell 身份启动：
 *
 *   CLASSPATH=<apk> app_process /system/bin \
 *       --nice-name=me.wjz.kitten:privilege \
 *       me.wjz.kitten.privilege.server.ServerMain --token=<token>
 *
 * 启动后创建服务、把 binder 交回 App，然后保持进程存活以响应后续调用。
 */
object ServerMain {

    private const val TAG = "KittenPrivServer"

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.firstOrNull { it.startsWith("--token=") }?.substringAfter('=')
        Log.i(TAG, "privilege server starting, uid=${android.os.Process.myUid()}")

        // 顺序有讲究：
        // 1. prepareMainLooper —— SystemContext 内部 new ActivityThread 时要用当前线程的
        //    Looper 建 Handler，没有就直接抛异常。
        // 2. SystemContext.init —— 必须在主线程做；binder 线程没有 Looper，到那时再初始化
        //    就晚了。拿不到 Context 只影响副屏，不阻断握手。
        // 3. 交回 binder，然后 loop 住。
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()
        SystemContext.init()

        val service = PrivilegeService()
        if (!BinderSender.send(service, token)) {
            Log.e(TAG, "交回 binder 失败，退出")
            exitProcess(1)
        }
        Log.i(TAG, "binder 已交回，进入等待")

        // 保持主线程存活；来自 App 的调用在 binder 线程池里处理（已实测会自动启动）。
        Looper.loop()
    }
}
