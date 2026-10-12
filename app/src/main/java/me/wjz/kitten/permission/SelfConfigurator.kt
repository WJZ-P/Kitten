package me.wjz.kitten.permission

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import me.wjz.kitten.service.KittenAccessibilityService
import me.wjz.kitten.service.KittenNotificationListener

/**
 * 拿到 WRITE_SECURE_SETTINGS 之后，Kitten 自己把需要的开关全部打开。
 *
 * 这是"配一次，以后全自动"的后半段。前半段由 ADB 完成——`pm grant` 把
 * WRITE_SECURE_SETTINGS 永久授给 Kitten 自己的 uid（它的 protectionLevel 带
 * `development` 标志，所以普通应用可以被授予）。授权扛重启，也不需要特权进程在场，
 * 所以从此以后下面这些事 Kitten 自己就能做，用户不用再去系统设置里逐个开关。
 *
 * 全部方法幂等，没有权限时直接返回，可以放心反复调用。
 */
object SelfConfigurator {

    const val TAG = "KittenSelfConfig"

    /** 无线调试开关。写它才能在开机后把 ADB 链路重新拉起来。 */
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** ADB 授权超时，单位毫秒。0 表示永不过期。 */
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    /**
     * 已启用的通知监听服务。
     *
     * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS` 在 SDK 里是 @hide 的，
     * 公开 API 取不到，只能写字面量。这个值十几年没变过。
     */
    private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

    /** 单项配置的结果，只为把日志写清楚。 */
    enum class ItemState {
        /** 本来就是开的，这次没动。 */
        ALREADY_ON,

        /** 这次真的打开了。 */
        TURNED_ON,

        /** 写入失败。 */
        FAILED,
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 打开 Kitten 需要的全部开关。
     *
     * 这个方法每次回到前台都会被调用，所以"没有变化"时只打 debug 级日志，
     * 真的改了什么才打 info——这样 `logcat -s KittenSelfConfig:I` 看到的全是实事。
     *
     * @return 是否真的执行了（没有权限时返回 false）。
     */
    fun applyAll(context: Context): Boolean {
        if (!hasPermission(context)) {
            Log.d(TAG, "尚未取得 WRITE_SECURE_SETTINGS，跳过自助配置")
            return false
        }
        val accessibility = enableAccessibilityService(context)
        val listener = enableNotificationListener(context)
        val timeout = keepAdbPairingAlive(context)

        val summary = "无障碍=$accessibility 通知使用权=$listener ADB授权超时=$timeout"
        val changed = listOf(accessibility, listener, timeout).any { it != ItemState.ALREADY_ON }
        if (changed) Log.i(TAG, "自助配置：$summary") else Log.d(TAG, "自助配置无变化：$summary")
        return true
    }

    /**
     * 开启无障碍服务。
     *
     * 两个设置项缺一不可：服务要出现在列表里，总开关也要是开的。
     */
    fun enableAccessibilityService(context: Context): ItemState {
        val component = ComponentName(context, KittenAccessibilityService::class.java)
        val state = addToColonList(
            context,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToString(),
        )
        if (state == ItemState.TURNED_ON &&
            !putSecure(context, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        ) {
            // 服务进了列表但总开关没打开，等于没开，这种半成品状态必须叫出来。
            Log.e(TAG, "无障碍服务已入列表，但总开关 ACCESSIBILITY_ENABLED 写入失败")
            return ItemState.FAILED
        }
        return state
    }

    /** 开启通知使用权。 */
    fun enableNotificationListener(context: Context): ItemState =
        addToColonList(
            context,
            ENABLED_NOTIFICATION_LISTENERS,
            ComponentName(context, KittenNotificationListener::class.java).flattenToString(),
        )

    /**
     * 让配对状态不会过期。
     *
     * 系统默认 7 天没连接就把 ADB 授权作废，那样用户又得重新配对一次。置 0 表示永不过期。
     */
    fun keepAdbPairingAlive(context: Context): ItemState {
        if (readGlobal(context, ADB_ALLOWED_CONNECTION_TIME) == "0") return ItemState.ALREADY_ON
        return if (putGlobal(context, ADB_ALLOWED_CONNECTION_TIME, "0")) {
            ItemState.TURNED_ON
        } else {
            ItemState.FAILED
        }
    }

    /**
     * 打开无线调试。
     *
     * **只在开机恢复流程里调用**，不要放进 [applyAll]。两个原因：
     *
     * 1. 无线调试常开会多出一个对外端口，对要分发的 App 是实打实的攻击面。
     * 2. 更要命的是，写这类设置会触发 `AdbService.setAdbEnabled()`，**adbd 会整个重启**，
     *    所有 ADB 连接当场被掐断——包括开发者正在用的那条。实测踩过：Android Studio
     *    的安装在传输途中被打断，装不上去却又没有明显报错，排查了很久。
     *
     * 所以这里只写 `adb_wifi_enabled`，**绝不碰 `ADB_ENABLED`**——USB 调试开不开是
     * 用户自己的事，和我们无关，动它纯属越界。
     *
     * 回读是为了确认：部分 ROM（尤其 MIUI / HyperOS）改过开发者选项，可能只是收下了
     * 这个值却没真的启动 adbd 的监听。
     */
    fun enableWirelessDebugging(context: Context): Boolean {
        if (!hasPermission(context)) {
            Log.w(TAG, "没有 WRITE_SECURE_SETTINGS，打不开无线调试")
            return false
        }
        val before = readGlobal(context, ADB_WIFI_ENABLED)
        putGlobal(context, ADB_WIFI_ENABLED, "1")
        val after = readGlobal(context, ADB_WIFI_ENABLED)
        val ok = after == "1"
        Log.i(
            TAG,
            "打开无线调试：adb_wifi_enabled $before -> $after，" +
                if (ok) "回读正常" else "回读异常，这个 ROM 可能不认这个设置项",
        )
        return ok
    }

    /** 把 [value] 加进一个冒号分隔的设置项里；已经在里面就不动。 */
    private fun addToColonList(context: Context, key: String, value: String): ItemState {
        val entries = readSecure(context, key).orEmpty().split(':').filter { it.isNotEmpty() }
        if (entries.any { it.equals(value, ignoreCase = true) }) return ItemState.ALREADY_ON
        return if (putSecure(context, key, (entries + value).joinToString(":"))) {
            ItemState.TURNED_ON
        } else {
            ItemState.FAILED
        }
    }

    private fun readSecure(context: Context, key: String): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrNull()

    private fun readGlobal(context: Context, key: String): String? =
        runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrNull()

    private fun putSecure(context: Context, key: String, value: String): Boolean =
        runCatching { Settings.Secure.putString(context.contentResolver, key, value) }
            .onFailure { Log.e(TAG, "写入 Secure.$key 失败", it) }
            .getOrDefault(false)

    /**
     * 写 Global 设置，**值没变就不写**。
     *
     * 这不是省事，是必需：SettingsProvider 的 observer 认的是"写入"这个动作，
     * 不是"值发生了变化"。拿 adb 相关的设置来说，哪怕写进去的值和原来一模一样，
     * 也会触发 `AdbService.setAdbEnabled()` 把 adbd 重启一遍，顺带掐断所有
     * 现存的 ADB 连接。而 [applyAll] 每次回到前台都会跑。
     */
    private fun putGlobal(context: Context, key: String, value: String): Boolean {
        if (readGlobal(context, key) == value) {
            Log.d(TAG, "Global.$key 已经是 $value，跳过写入")
            return true
        }
        return runCatching { Settings.Global.putString(context.contentResolver, key, value) }
            .onFailure { Log.e(TAG, "写入 Global.$key 失败", it) }
            .getOrDefault(false)
    }
}
