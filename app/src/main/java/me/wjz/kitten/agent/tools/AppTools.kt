package me.wjz.kitten.agent.tools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.agent.stringParam
import org.json.JSONObject

/**
 * 列出手机上能打开的应用。
 *
 * 只列**有启动入口**的（`ACTION_MAIN` + `CATEGORY_LAUNCHER`），也就是桌面上能点开的那些。
 * 系统里装着几百个包，大多是服务、输入法插件、厂商组件，列给模型全是噪音。
 *
 * 能看到全部应用是因为 manifest 里声明了 `QUERY_ALL_PACKAGES`。没有它的话，Android 11 起
 * 的 package visibility 只让我们看到自己声明过的那几个。
 */
class ListAppsTool(private val context: Context) : AgentTool {

    override val name = "list_apps"

    override val description =
        "列出手机上已安装、可以打开的应用，给出每个应用的显示名和包名。" +
            "要操作某个应用之前先用它确认包名——包名不能猜，猜错了会拉起别的应用或者直接失败。" +
            "应用很多时用 keyword 过滤。"

    override val parameters = objectSchema(
        "keyword" to stringParam(
            "按关键词过滤，匹配显示名或包名，不区分大小写。" +
                "比如想知道用户装了哪些购物应用，可以把你知道的购物软件名字挨个试一遍，" +
                "也可以不传拿到完整列表再自己挑。",
        ),
    )

    override suspend fun execute(args: JSONObject): String = withContext(Dispatchers.IO) {
        val keyword = args.optString("keyword").trim().takeIf { it.isNotEmpty() }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val apps = runCatching {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        }.getOrElse {
            return@withContext "ERROR query_failed: 读取应用列表失败：${it.message}"
        }
            // 一个应用可能注册了好几个启动入口（多开、快捷方式），按包名去重。
            .distinctBy { it.activityInfo.packageName }
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { (label, pkg) ->
                keyword == null || label.contains(keyword, ignoreCase = true) ||
                    pkg.contains(keyword, ignoreCase = true)
            }
            .sortedBy { (label, _) -> label }

        if (apps.isEmpty()) {
            return@withContext if (keyword == null) {
                "ERROR no_apps: 没有读到任何可启动的应用，这不正常，可能是权限问题。"
            } else {
                "没有找到名字或包名包含\"$keyword\"的应用。换个关键词，或者不传 keyword 看完整列表。"
            }
        }

        buildString {
            appendLine("共 ${apps.size} 个应用（格式：显示名 | 包名）：")
            apps.take(MAX_APPS).forEach { (label, pkg) -> appendLine("$label | $pkg") }
            if (apps.size > MAX_APPS) {
                appendLine("…还有 ${apps.size - MAX_APPS} 个没列出来，用 keyword 缩小范围。")
            }
        }
    }

    private companion object {
        /** 一次最多列多少个。手机上一般一两百个，这个数够用，又不至于把上下文撑爆。 */
        const val MAX_APPS = 200
    }
}

/**
 * 把应用拉起来。有特权进程时拉到副屏（不打扰用户），否则拉到主屏。详见 [DeviceSession.launchApp]。
 */
class LaunchAppTool(private val session: DeviceSession) : AgentTool {

    override val name = "launch_app"

    override val description =
        "打开一个应用，之后的 read_screen 和所有操作都对着它。" +
            "有副屏能力时会在后台的副屏里打开，用户的屏幕不受影响；没有时在用户眼前打开。" +
            "包名要先用 list_apps 确认。注意有些应用（不少社交软件、短视频软件）同时只能开一个：" +
            "用户自己在主屏打开同一个应用时，副屏里的那个会被应用自己关掉。"

    override val parameters = objectSchema(
        "package_name" to stringParam("应用包名，比如 com.example.shop"),
        required = listOf("package_name"),
    )

    override suspend fun execute(args: JSONObject): String {
        val packageName = args.optString("package_name").trim()
        if (packageName.isEmpty()) {
            return "ERROR bad_args: 没有给 package_name。先用 list_apps 查到包名再调。"
        }
        return session.launchApp(packageName)
    }
}
