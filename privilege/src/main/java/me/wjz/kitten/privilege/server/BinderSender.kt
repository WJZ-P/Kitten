package me.wjz.kitten.privilege.server

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log
import me.wjz.kitten.privilege.ipc.BinderContainer
import me.wjz.kitten.privilege.ipc.PrivilegeContract

/**
 * 在特权进程里，把服务的 binder 交回给 App。
 *
 * 做法照搬 Shizuku：拿到 App 那个 ContentProvider 的 IContentProvider，
 * 调它的 call("sendBinder", ...) 把 binder 放进去。
 *
 * 这里用到 getContentProviderExternal / IContentProvider.call 等隐藏 API。
 * 特权进程不是正常的 App 进程，没有 non-SDK 接口限制，可以直接反射调用。
 *
 * IContentProvider.call 的签名在不同 Android 版本上差异很大，这里只实现 API 31+
 * 带 AttributionSource 的那一版。这也是 minSdk 必须 ≥ 31 的直接原因——低于 31
 * 这条交回 binder 的路走不通，整个特权链路就是空的。minSdk 实际定在 33，
 * 理由见 app/build.gradle.kts。
 */
internal object BinderSender {

    private const val TAG = "KittenPrivSender"

    // 本模块随 App 一起打包，目标包名在编译期就是确定的。
    private const val TARGET_PACKAGE = "me.wjz.kitten"
    private val AUTHORITY = TARGET_PACKAGE + PrivilegeContract.AUTHORITY_SUFFIX

    // 目前只支持主用户。get / remove provider 必须用同一个 userId。
    private const val USER_ID = 0

    fun send(service: IBinder, token: String?): Boolean {
        return try {
            sendViaContentProvider(service, token)
            true
        } catch (t: Throwable) {
            Log.e(TAG, "交回 binder 失败", t)
            false
        }
    }

    private fun sendViaContentProvider(service: IBinder, token: String?) {
        val am = activityManager()
        val externalToken: IBinder = Binder()
        val holder = getContentProviderExternal(am, AUTHORITY, externalToken)
            ?: throw IllegalStateException("getContentProviderExternal 返回 null")
        try {
            val provider = holder.javaClass
                .getDeclaredField("provider")
                .apply { isAccessible = true }
                .get(holder)
                ?: throw IllegalStateException("ContentProviderHolder.provider 为空")

            val extras = Bundle().apply {
                putParcelable(PrivilegeContract.EXTRA_BINDER, BinderContainer(service))
                putString(PrivilegeContract.EXTRA_TOKEN, token)
            }
            // call 不会因为 App 拒绝而抛异常，必须看回执，否则被拒了还傻等着。
            val result = callProvider(provider, extras)
            if (result?.getBoolean(PrivilegeContract.KEY_ACCEPTED) != true) {
                throw IllegalStateException("App 没有接受这个 binder（token 不匹配或握手失败）")
            }
        } finally {
            // 走到这里时 binder 可能已经送达，归还失败只记日志，
            // 不能让它把成功变成失败，也不能盖掉 try 里抛出的原始异常。
            runCatching { removeContentProviderExternalAsUser(am, AUTHORITY, externalToken) }
                .onFailure { Log.w(TAG, "归还 provider 引用失败", it) }
        }
    }

    /** IActivityManager（android.app.ActivityManager#getService）。 */
    private fun activityManager(): Any =
        Class.forName("android.app.ActivityManager")
            .getMethod("getService")
            .invoke(null)
            ?: throw IllegalStateException("IActivityManager 为空")

    /** getContentProviderExternal(String name, int userId, IBinder token, String tag) -> ContentProviderHolder */
    private fun getContentProviderExternal(am: Any, authority: String, token: IBinder): Any? {
        val method = am.javaClass.getMethod(
            "getContentProviderExternal",
            String::class.java,
            Int::class.javaPrimitiveType,
            IBinder::class.java,
            String::class.java,
        )
        return method.invoke(am, authority, USER_ID, token, authority)
    }

    /**
     * removeContentProviderExternalAsUser(String name, IBinder token, int userId)
     *
     * 不带 userId 的 removeContentProviderExternal 已被标记 @deprecated，
     * AOSP 自己的 `content` 命令也用的是这个 AsUser 版本。
     */
    private fun removeContentProviderExternalAsUser(am: Any, authority: String, token: IBinder) {
        val method = am.javaClass.getMethod(
            "removeContentProviderExternalAsUser",
            String::class.java,
            IBinder::class.java,
            Int::class.javaPrimitiveType,
        )
        method.invoke(am, authority, token, USER_ID)
    }

    /** API 31+: IContentProvider.call(AttributionSource, String authority, String method, String arg, Bundle) */
    private fun callProvider(provider: Any, extras: Bundle): Bundle? {
        val attributionSource = AttributionSource.Builder(Process.myUid())
            .setPackageName(TARGET_PACKAGE)
            .build()
        val call = provider.javaClass.methods.firstOrNull { m ->
            m.name == "call" &&
                m.parameterTypes.size == 5 &&
                m.parameterTypes[0] == AttributionSource::class.java
        } ?: throw NoSuchMethodException("找不到 IContentProvider.call(AttributionSource, ...)")
        return call.invoke(
            provider,
            attributionSource,
            AUTHORITY,
            PrivilegeContract.METHOD_SEND_BINDER,
            null,
            extras,
        ) as? Bundle
    }
}
