package me.wjz.kitten.agent.tools

import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.wjz.kitten.a11y.A11yScreenshot
import me.wjz.kitten.a11y.CaptureResult
import me.wjz.kitten.a11y.CaptureScope
import me.wjz.kitten.a11y.ScreenReader
import me.wjz.kitten.agent.AgentTool
import me.wjz.kitten.agent.DeviceSession
import me.wjz.kitten.agent.ToolOutput
import me.wjz.kitten.agent.VisualTool
import me.wjz.kitten.agent.enumParam
import me.wjz.kitten.agent.noParams
import me.wjz.kitten.agent.objectSchema
import me.wjz.kitten.privilege.VirtualDisplayManager
import org.json.JSONObject

/**
 * 读当前这块屏上有什么。
 *
 * 抓回来的是无障碍节点树，每个节点带一个 `[id]`——**动作工具全靠这个 id 定位**，所以每次要
 * 操作之前都得先抓一次。页面一变旧的 id 就失效了，这是有意的：宁可明确报"节点已失效，重新
 * 抓一次"，也不要照着过时的坐标点歪（见 [me.wjz.kitten.a11y.ScreenError.NodeGone]）。
 *
 * 抓取是一串跨进程调用，几十到几百毫秒，必须在工作线程上跑（见 [ScreenReader]）。
 */
class ReadScreenTool(private val session: DeviceSession) : AgentTool {

    override val name = "read_screen"

    override val description =
        "读取当前屏幕上的界面元素，返回一棵带编号的节点树。每行形如 [12] \"确定\" clickable，" +
            "编号就是后续 tap / input_text / scroll 要用的 node_id。" +
            "每次操作之前都要先调它拿到最新编号——点过一下、滚过一屏之后页面就变了，旧编号会失效。" +
            "默认只返回能操作的元素（interactive），要读页面上的正文、价格、订单详情这类纯展示" +
            "文字时传 scope=visible。"

    override val parameters = objectSchema(
        "scope" to enumParam(
            "要哪一档内容。interactive：只要能点能填的元素，默认，决策用；" +
                "visible：用户此刻看得见的全部内容，读正文用，比 interactive 啰嗦但不会漏掉纯文字；" +
                "all：连看不见的也要，只在前两档明显漏东西时用来排查，很占篇幅。",
            listOf("interactive", "visible", "all"),
        ),
    )

    override suspend fun execute(args: JSONObject): String = withContext(Dispatchers.IO) {
        val scope = when (args.optString("scope").lowercase()) {
            "visible" -> CaptureScope.VISIBLE
            "all" -> CaptureScope.ALL
            // 模型没传或者传了没见过的值：按决策场景给 interactive，这是最常用的一档。
            else -> CaptureScope.INTERACTIVE
        }
        val result = ScreenReader.capture(
            displayId = session.displayId,
            scope = scope,
            // 拉起过应用就一直核对它还在不在这块屏上。单实例应用会被用户抢走，这是唯一能
            // 及时发现的办法，不然抓回来的是别的应用的界面，模型会照着它瞎推理。
            expectedPackage = session.currentPackage,
        )
        (result as? CaptureResult.Ok)?.let { session.lastSnapshot = it.snapshot }
        result.toModelText()
    }
}

/**
 * 截一张当前屏幕的图给模型看。
 *
 * ## 什么时候需要它
 *
 * 节点树读不出来的东西：图片、图表、验证码、自绘的界面（游戏、地图），还有"这两个按钮哪个
 * 在上面"这类纯视觉的判断。常规的找按钮、读文字用 [ReadScreenTool] 就够，又快又省。
 *
 * ## 只截副屏
 *
 * 画面是从副屏的离屏缓冲里读的（见 VirtualDisplayHost.capture），要特权进程。Agent 绝大多数
 * 时候都在副屏上办事，所以这是常用的那个。
 *
 * 主屏另有一个工具（[MainScreenshotTool]，走无障碍截图）。分成两个而不是让这一个自动判断，是
 * 因为截主屏是另一回事：截到的是用户眼前的画面，模型得清楚自己在看哪块屏、为什么要看。
 */
class ScreenshotTool(private val session: DeviceSession) : VisualTool {

    override val name = "screenshot"

    override val description =
        "截取你正在操作的副屏的画面给你看。用在节点树读不出来的地方：图片内容、图表、验证码、" +
            "自绘的界面，或者要判断元素的实际排布时。" +
            "常规的找按钮、读文字用 read_screen 就够了，又快又省，不要每一步都截图。" +
            "注意：截图里看到的元素没有编号，要操作还是得靠 read_screen。" +
            "应用开在主屏上时用 screenshot_main_screen。"

    override val parameters = noParams()

    override suspend fun executeVisual(args: JSONObject): ToolOutput {
        if (!VirtualDisplayManager.isAvailable || session.displayId == Display.DEFAULT_DISPLAY) {
            return ToolOutput(
                "ERROR main_display: 现在没有副屏，应用开在用户的主屏上。要看画面用 screenshot_main_screen。",
            )
        }
        val bytes = VirtualDisplayManager.captureDisplay(session.displayId, MaxEdgePx, JpegQuality)
            ?: return ToolOutput(
                "ERROR capture_failed: 没取到画面。可能这块屏还没渲染出东西，" +
                    "可以先 wait 一两秒再试。",
            )
        val image = session.saveScreenshot(bytes)
            ?: return ToolOutput("ERROR save_failed: 截图存不下来，改用 read_screen。")
        return ToolOutput(
            text = "这是当前屏幕的画面（${image.width}×${image.height}，为了省流量缩过）。" +
                "要操作上面的元素，还得用 read_screen 拿编号。",
            images = listOf(image),
        )
    }
}

/**
 * 截一张用户主屏的图给模型看，走无障碍截图（见 [A11yScreenshot]），不要特权进程。
 *
 * ## 什么时候用
 *
 * 少数情况：没有特权进程、应用只能开在主屏上；或者用户明确让 Agent 看看他屏幕上的东西。
 * 平常 Agent 在副屏办事，截图用 [ScreenshotTool]。
 *
 * 主屏上是用户眼前的画面，截到什么全看用户此刻开着什么——用户正在 Kitten 里跟它说话的话，
 * 截到的多半就是 Kitten 自己的聊天界面。这点写进描述里，免得模型截完一脸困惑。
 *
 * 截图会发给模型服务商，和 read_screen 读到的文字是同一类数据，只是更全。所以描述里要求只在
 * 需要时截，不要"顺手看一眼"。
 */
class MainScreenshotTool(private val session: DeviceSession) : VisualTool {

    override val name = "screenshot_main_screen"

    override val description =
        "截取用户主屏（用户眼前正在看的那块屏）的画面给你看。平常你在副屏上办事，截图用 screenshot；" +
            "只在这两种情况用它：应用开在了主屏上（launch_app 说在主屏拉起的时候），" +
            "或者用户明确让你看他屏幕上的东西。不需要的时候不要截，截到的是用户的隐私画面。" +
            "注意：用户正在 Kitten 里跟你说话时，主屏上多半就是 Kitten 自己的聊天界面。" +
            "截图里的元素没有编号，要操作还是得靠 read_screen。"

    override val parameters = noParams()

    override suspend fun executeVisual(args: JSONObject): ToolOutput =
        when (val shot = A11yScreenshot.capture(Display.DEFAULT_DISPLAY, MaxEdgePx, JpegQuality)) {
            is A11yScreenshot.Shot.Failed -> ToolOutput(shot.message)
            is A11yScreenshot.Shot.Ok -> {
                val image = session.saveScreenshot(shot.jpeg)
                if (image == null) {
                    ToolOutput("ERROR save_failed: 截图存不下来，改用 read_screen。")
                } else {
                    ToolOutput(
                        text = "这是用户主屏现在的画面（${image.width}×${image.height}，为了省流量缩过）。" +
                            "要操作上面的元素，还得用 read_screen 拿编号。",
                        images = listOf(image),
                    )
                }
            }
        }
}

/**
 * 截图长边缩到多少像素，副屏和主屏一样。
 *
 * 给模型看不需要原始清晰度：1280 这个量级上界面文字照样认得出来，而像素少一半，
 * 图片 token 和传输都省一大截。再小的话小字号的说明文字会开始糊。
 */
private const val MaxEdgePx = 1280

/** JPEG 质量。界面是大色块加文字，这个档位几乎看不出压缩痕迹。 */
private const val JpegQuality = 75
