package me.wjz.kitten.privilege.server

import android.content.Intent
import android.util.Log

/**
 * 把应用拉到指定屏幕上。
 *
 * ## 为什么不用 Context.startActivity
 *
 * 试过，走不通。`ContextImpl.startActivity` 最终传给 ActivityTaskManager 的是
 * `getOpPackageName()`，而系统会校验"这个包名是否属于调用方的 uid"。
 *
 * 我们这个进程是 uid 2000，`createPackageContext("com.android.shell", ...)` 能把
 * `getPackageName()` 改成 com.android.shell（所以 DisplayManager 那边通过了），
 * 但 `getOpPackageName()` 改不了——`createPackageContextAsUser` 构造 ContextImpl 时
 * 给 overrideOpPackageName 传的是 null，于是沿用父 Context 的 "android"。
 * 结果就是 `SecurityException: package=android does not belong to uid=2000`（实测）。
 *
 * 要绕过它得反射 `ContextImpl.createAppContext(thread, loadedApk, opPackageName)` 这类
 * 私有方法，签名随版本变动，太脆。
 *
 * ## 所以直接 exec `am start`
 *
 * `am` 本来就是跑在 shell 身份下的命令，包名天然对得上——我们之前用 adb 手工验证过
 * `am start --display N -f 0x18000000 -n <component>` 是可行的，计算器确实落在了副屏上。
 *
 * 用参数数组而不是拼命令行字符串：**不经过 shell，所以不存在命令注入**，包名里就算
 * 混进分号反引号也只会被当成普通字符。再加一道包名格式校验作为纵深防御。
 */
internal object AppLauncher {

    private const val TAG = "KittenPrivLauncher"

    /** 合法包名：字母数字下划线，点分隔。用来挡住畸形输入。 */
    private val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*")

    /** NEW_TASK | MULTIPLE_TASK。实测加上它才会在两块屏各留一个 task，否则是把原 task 搬走。 */
    private const val FLAGS_NEW_INSTANCE = "0x18000000"

    /** am 执行完就该退出；卡住说明环境有问题，不能让 binder 线程一直等。 */
    private const val TIMEOUT_MS = 10_000L

    fun launchOnDisplay(packageName: String, displayId: Int, forceNewInstance: Boolean): Boolean {
        if (!PACKAGE_PATTERN.matches(packageName)) {
            Log.e(TAG, "包名格式不合法，拒绝执行：$packageName")
            return false
        }
        val component = resolveLaunchComponent(packageName) ?: return false

        val args = buildList {
            add("am")
            add("start")
            add("--display")
            add(displayId.toString())
            if (forceNewInstance) {
                add("-f")
                add(FLAGS_NEW_INSTANCE)
            }
            add("-n")
            add(component)
        }
        return exec(args, "在 displayId=$displayId 上启动 $packageName")
    }

    /** 查应用的启动 Activity。PackageManager 的查询不涉及身份校验，系统 Context 够用。 */
    private fun resolveLaunchComponent(packageName: String): String? {
        val context = SystemContext.get() ?: run {
            Log.e(TAG, "没有系统 Context，无法解析启动入口")
            return null
        }
        val intent: Intent? = runCatching {
            context.packageManager.getLaunchIntentForPackage(packageName)
        }.onFailure { Log.e(TAG, "解析 $packageName 启动入口出错", it) }.getOrNull()

        val component = intent?.component
        if (component == null) {
            Log.e(TAG, "找不到 $packageName 的启动入口，可能没装或没有 Launcher Activity")
            return null
        }
        return component.flattenToShortString()
    }

    private fun exec(args: List<String>, what: String): Boolean = runCatching {
        Log.i(TAG, "$what：${args.joinToString(" ")}")
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            Log.e(TAG, "$what 超时未返回")
            return false
        }
        val code = process.exitValue()
        // am 对"启动失败"也可能返回 0，所以输出里带 Error/Exception 同样算失败。
        val failed = code != 0 || output.contains("Error", ignoreCase = true) ||
            output.contains("Exception", ignoreCase = true)
        if (failed) Log.e(TAG, "$what 失败，exit=$code，输出：$output")
        else Log.i(TAG, "$what 成功：$output")
        !failed
    }.getOrElse {
        Log.e(TAG, "$what 执行出错", it)
        false
    }
}
