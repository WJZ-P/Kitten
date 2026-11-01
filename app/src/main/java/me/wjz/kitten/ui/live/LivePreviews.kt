package me.wjz.kitten.ui.live

import android.view.Surface
import androidx.annotation.MainThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import me.wjz.kitten.privilege.VirtualDisplayManager
import java.util.concurrent.Executors

/**
 * 界面上谁在看哪块副屏，副屏的画面交给谁。
 *
 * 特权那边一块副屏同一时间只往一个地方画（见 VirtualDisplayManager.setPreview）。界面上却可能有两处同时
 * 想看：任务卡片旁的缩略图一直在，点开全屏之后，全屏那个也要。所以按先来后到排一摞：最后来的接手，它走了
 * 交还给前一个——全屏关掉，缩略图接着动。一个都没有了才告诉特权那边不看了，副屏回到直出，没有额外开销。
 *
 * 只在主线程上改这摞东西；真正跨进程的调用在一条专门的线程上按顺序发，先换上、后撤下的两个调用不会
 * 反过来到达特权进程。
 */
internal object LivePreviews {

    private class Viewer(val owner: Any, val surface: Surface)

    private val stacks = HashMap<Int, MutableList<Viewer>>()

    private val calls = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "KittenLivePreview") }
            .asCoroutineDispatcher(),
    )

    /** [owner] 要在 [surface] 上看 [displayId] 这块副屏。它接手，之前在看的先让一让。 */
    @MainThread
    fun attach(displayId: Int, owner: Any, surface: Surface) {
        val stack = stacks.getOrPut(displayId) { mutableListOf() }
        val top = stack.lastOrNull()
        if (top != null && top.owner === owner && top.surface === surface) return
        stack.removeAll { it.owner === owner }
        stack += Viewer(owner, surface)
        send(displayId, surface)
    }

    /**
     * [owner] 不看了。它正在看的话，交还给前一个；没有前一个就告诉特权那边不看了。
     *
     * @param afterDetached 特权那边确实不再往它的 Surface 上画了之后做的事：TextureView 销毁时，要等到这时候
     *   才放掉 Surface，不然那边还在往一个已经没了的 Surface 上画。
     */
    @MainThread
    fun detach(displayId: Int, owner: Any, afterDetached: () -> Unit = {}) {
        val stack = stacks[displayId]
        val wasShowing = stack?.lastOrNull()?.owner === owner
        stack?.removeAll { it.owner === owner }
        if (stack != null && stack.isEmpty()) stacks.remove(displayId)
        if (wasShowing) {
            send(displayId, stack?.lastOrNull()?.surface, afterDetached)
        } else {
            // 它本来就没在画面上：排在后面放掉就行，前面要是还有没发出去的调用，等它们发完。
            calls.launch { afterDetached() }
        }
    }

    private fun send(displayId: Int, surface: Surface?, then: () -> Unit = {}) {
        calls.launch {
            VirtualDisplayManager.setPreview(displayId, surface)
            then()
        }
    }
}
