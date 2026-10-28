package me.wjz.kitten.agent

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.view.Display
import me.wjz.kitten.a11y.UiSnapshot
import me.wjz.kitten.llm.ImageAttachment
import me.wjz.kitten.privilege.VirtualDisplayManager
import java.io.File
import java.util.UUID

/**
 * 一轮任务里 Agent 操作手机的"现场"：在哪块屏上干活、最近一次看到的界面是什么样。
 *
 * ## 为什么要有它
 *
 * 工具之间是有上下文的：`read_screen` 抓到的节点 id，`tap` 要拿来用；`launch_app` 开的副屏，
 * 后面每个动作都发往那里。把这些放在工具自己身上就散了，所以统一存在这里，所有工具共用
 * 同一个实例。
 *
 * ## 副屏
 *
 * 有特权进程时，[launchApp] 会建一块副屏把应用拉上去：用户的主屏不受打扰，Agent 在后台
 * 自己操作。副屏的尺寸和密度跟着主屏走——密度不一致时部分应用（实测 QQ）会把它当成系统
 * 显示设置变了，直接杀掉自己的进程，连带用户主屏上那个 task 一起消失。
 *
 * 没有特权进程时退回主屏：用普通 Intent 拉起应用，照样能读能点，只是会打扰到用户。这条
 * 退路是必要的——不是每个用户都会去配 ADB。
 *
 * ## 并发
 *
 * 每一轮任务各有一个 DeviceSession、各建自己的副屏，特权侧按 displayId 分开管，几轮可以同时跑。
 * 限制在应用上：同一个应用同一时间只给一轮用，见 [AppLeases]。
 *
 * ## 生命周期
 *
 * 一轮任务结束就 [release]：自己建的副屏要收掉，不然会一直挂在系统里。收的时候副屏上的应用
 * 跟着关掉，不会被系统挪到主屏、盖在用户眼前（见特权侧 VirtualDisplayHost 的
 * FLAG_DESTROY_CONTENT_ON_REMOVAL）；最近任务里也不会多出一个黑屏的它（见特权侧
 * DisplayTaskCleaner）。
 */
class DeviceSession(private val context: Context) {

    /**
     * 现在在操作哪块屏。默认主屏；[launchApp] 建了副屏之后指向副屏。
     *
     * 抓屏和所有动作都发往这块屏。
     */
    var displayId: Int = Display.DEFAULT_DISPLAY
        private set

    /**
     * 最近一次抓到的界面。节点编号只在这份快照里有意义，动作工具靠它把编号换回活节点。
     *
     * 页面一变它就过时了，所以动作失败时报的是"节点已失效，重新抓一次"，而不是悄悄点歪
     * （见 [me.wjz.kitten.a11y.ScreenError.NodeGone]）。
     */
    var lastSnapshot: UiSnapshot? = null

    /** 自己建的副屏，结束时要收掉。没建过是 [VirtualDisplayManager.INVALID_DISPLAY_ID]。 */
    private var ownedDisplayId: Int = VirtualDisplayManager.INVALID_DISPLAY_ID

    /** 这一轮拉起过的应用，[release] 时写进日志，方便排查。 */
    var currentPackage: String? = null
        private set

    /**
     * 把应用拉起来，之后的操作都对着它。
     *
     * 拉之前先占住这个应用（[AppLeases]）：别的任务正在用它的话不拉，告诉模型先做别的。拉起来了
     * 就换成占着它、放掉之前那个；没拉起来，刚占的放掉。
     *
     * @return 给模型看的一句话：拉到哪块屏上了，或者为什么没拉起来。
     */
    fun launchApp(packageName: String): String {
        val previous = currentPackage
        if (!AppLeases.tryAcquire(packageName, this)) {
            Log.i(TAG, "$packageName 正被另一个任务占着，这一轮先不拉")
            return "ERROR app_busy: $packageName 正在被另一个任务操作，同一个应用不能两个任务同时用。" +
                "先去做用不到它的部分，过一会儿再来拉它。"
        }
        val result = launchOnSomeDisplay(packageName)
        if (currentPackage == packageName) {
            if (previous != null && previous != packageName) AppLeases.release(previous, this)
        } else if (packageName != previous) {
            AppLeases.release(packageName, this)
        }
        return result
    }

    /** 真正去拉：副屏优先，不行退回主屏。拉起来了会把 [currentPackage] 换成它。 */
    private fun launchOnSomeDisplay(packageName: String): String {
        // 副屏是首选：不打扰用户。拿不到特权进程就退回主屏。
        if (VirtualDisplayManager.isAvailable) {
            val metrics = context.resources.displayMetrics
            val target = if (ownedDisplayId != VirtualDisplayManager.INVALID_DISPLAY_ID) {
                // 已经有副屏了就复用，别一个任务开一堆屏。
                if (VirtualDisplayManager.launchApp(packageName, ownedDisplayId, NewInstance)) ownedDisplayId else null
            } else {
                VirtualDisplayManager.launchAppOnNewDisplay(
                    packageName = packageName,
                    width = metrics.widthPixels,
                    height = metrics.heightPixels,
                    // 必须和主屏一致，见类注释。
                    densityDpi = metrics.densityDpi,
                    forceNewInstance = NewInstance,
                ).takeIf { it != VirtualDisplayManager.INVALID_DISPLAY_ID }
                    ?.also { ownedDisplayId = it }
            }
            if (target != null) {
                displayId = target
                currentPackage = packageName
                lastSnapshot = null
                return "已在副屏（display=$target）拉起 $packageName，用户的主屏不受影响。" +
                    "接下来用 read_screen 看看它长什么样。"
            }
            Log.w(TAG, "副屏拉起 $packageName 失败，退回主屏")
        }

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "ERROR app_not_found: 没有找到 $packageName，或者它没有可以启动的界面。" +
                "先用 list_apps 确认包名。"
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            displayId = Display.DEFAULT_DISPLAY
            currentPackage = packageName
            lastSnapshot = null
            "已在主屏拉起 $packageName（没有副屏可用，所以会显示在用户眼前）。" +
                "接下来用 read_screen 看看它长什么样。"
        }.getOrElse {
            Log.w(TAG, "拉起 $packageName 失败", it)
            "ERROR launch_failed: 拉起 $packageName 失败：${it.message}"
        }
    }

    /**
     * 这一轮截图落脚的地方：缓存目录下自己的一个子目录。
     *
     * 放缓存目录而不是私有文件目录：截图只在这一轮里有用（发给模型看一眼），结束就删。
     * 每轮一个子目录，万一同时跑了两轮也不会互相删文件。
     */
    private var screenshotDir: File? = null

    /** 第一次截图时才建目录：绝大多数任务一张图都不截，没必要先建个空目录再删掉。 */
    private fun screenshotDir(): File =
        screenshotDir ?: File(context.cacheDir, "$SCREENSHOT_DIR/${UUID.randomUUID()}")
            .apply { mkdirs() }
            .also { screenshotDir = it }

    /**
     * 存一张截图，返回能发给模型的附件；存不下返回 null。
     *
     * 宽高是从 JPEG 头里读的（只解码尺寸、不解码像素，很便宜），给附件带上，后面要排版时不用
     * 再解一次。
     */
    fun saveScreenshot(bytes: ByteArray): ImageAttachment? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val file = File(screenshotDir(), "${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        ImageAttachment(path = file.absolutePath, width = bounds.outWidth, height = bounds.outHeight)
    }.onFailure { Log.w(TAG, "截图存不下", it) }.getOrNull()

    /** 一轮任务结束，收掉自己建的副屏和这一轮的截图，放掉占着的应用。 */
    fun release() {
        // 截图只在这一轮里有用，历史里不留（见 ChatViewModel 的 forHistory）。
        screenshotDir?.let { dir -> runCatching { dir.deleteRecursively() } }
        screenshotDir = null
        if (ownedDisplayId != VirtualDisplayManager.INVALID_DISPLAY_ID) {
            Log.i(TAG, "回收副屏 display=$ownedDisplayId（$currentPackage）")
            VirtualDisplayManager.releaseDisplay(ownedDisplayId)
            ownedDisplayId = VirtualDisplayManager.INVALID_DISPLAY_ID
        }
        displayId = Display.DEFAULT_DISPLAY
        lastSnapshot = null
        currentPackage = null
        AppLeases.releaseAll(this)
    }

    private companion object {
        const val TAG = "KittenAgent"
        const val SCREENSHOT_DIR = "agent-screenshots"

        /**
         * 在副屏上拉起应用时新开一个实例（NEW_TASK | MULTIPLE_TASK），而不是把用户已经开着的那个搬过来。
         *
         * 搬过来的话，用户主屏上的那个就凭空消失了，一轮结束副屏收掉时还会跟着被关掉。新开实例：
         * - 支持多开的应用：主屏、副屏各一个，互不影响。
         * - 应用自己限制单实例的（QQ、微信、抖音）：它会把旧的那个（用户主屏上的）关掉，只留新的。
         *   和搬过来的结果差不多，不会更糟。
         *
         * 例外：启动页是 singleTask / singleInstance 的应用，系统会无视 MULTIPLE_TASK，照样把已有的
         * task 搬过来（AOSP ActivityStarter 里 putIntoExistingTask 的判断），这个没法从外面改。
         *
         * 只管副屏。退回主屏时用的是普通 Intent，同一块屏上没必要开两个，复用已有的正合适。
         */
        const val NewInstance = true
    }
}
