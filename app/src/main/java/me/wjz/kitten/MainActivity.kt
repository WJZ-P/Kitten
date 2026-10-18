package me.wjz.kitten

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import me.wjz.kitten.privilege.client.AdbPairingService
import me.wjz.kitten.ui.KittenApp
import me.wjz.kitten.ui.setup.SetupActions
import me.wjz.kitten.ui.theme.KittenTheme

/**
 * App 入口。
 *
 * 这里只做两件事：把界面挂上去，以及实现 [SetupActions]——那些只有 Activity 才做得到的
 * 事情（跳系统设置、申请运行时权限、以前台身份拉起服务）。界面本身在 [KittenApp] 里。
 */
class MainActivity : ComponentActivity(), SetupActions {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            beginPairing()
        } else {
            // 没有通知就没地方填配对码，整条链路直接走不通。
            Toast.makeText(this, R.string.setup_privilege_needs_notification, Toast.LENGTH_LONG)
                .show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 界面是天依蓝 + 白的浅色系，而且只有这一套：状态栏和导航栏的图标固定用深色。
        // 默认的 auto 会跟着系统深色模式把图标变白，压在浅色背景上就看不见了。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            KittenTheme {
                KittenApp(actions = this)
            }
        }
    }

    override fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    override fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    override fun startPairing() {
        // POST_NOTIFICATIONS 是 API 33 引入的运行时权限，minSdk 就是 33，不用再判版本。
        val needsPermission = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            // 授权结果回来后再继续，见 notificationPermissionLauncher。
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        beginPairing()
    }

    /**
     * 挂上配对通知，然后把用户送到「无线调试」跟前。
     *
     * 两步的顺序不能反：API 31+ 只允许处于前台的 App 启动前台服务。先跳设置的话，
     * Kitten 当场进入后台，再 start 就会被 ForegroundServiceStartNotAllowedException 拒掉。
     */
    private fun beginPairing() {
        AdbPairingService.start(this)
        openWirelessDebuggingSettings()
    }

    private fun openWirelessDebuggingSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            // 开发者选项是个很长的列表，直接打开用户还得自己翻。这个 extra 能让设置页
            // 滚动到并高亮指定的 preference，省掉找的过程（Shizuku 也是这么干的）。
            //
            // 注意它不是公开 API——android-37 的 android.jar 里没有 EXTRA_FRAGMENT_ARG_KEY，
            // 只能写字面量。ROM 不认就当没传，退化成普通地打开开发者选项，不会报错。
            putExtra(SETTINGS_FRAGMENT_ARG_KEY, PREF_KEY_WIRELESS_DEBUGGING)
            // 清掉设置已有的任务栈，避免落在用户上次停留的页面上。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, R.string.setup_privilege_no_developer_options, Toast.LENGTH_LONG)
                .show()
        }
    }

    private companion object {
        /** AOSP 设置用来"滚动到并高亮某个 preference"的 extra，非公开 API。 */
        const val SETTINGS_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"

        /** 开发者选项里「无线调试」那一项的 preference key。 */
        const val PREF_KEY_WIRELESS_DEBUGGING = "toggle_adb_wireless"
    }
}
