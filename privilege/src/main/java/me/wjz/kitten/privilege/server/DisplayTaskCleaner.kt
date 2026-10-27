package me.wjz.kitten.privilege.server

import android.annotation.SuppressLint
import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 收副屏之前，把副屏上的 task 从系统里移除，连同"最近任务"里的那一条。
 *
 * ## 为什么光靠 FLAG_DESTROY_CONTENT_ON_REMOVAL 不够
 *
 * 那个标志只管 Activity：副屏一收，上面的 Activity 都被 finish。但 task 本身还留在系统的
 * 最近任务列表里，成了一个没有 Activity 的空壳。用户从底部上滑看最近任务时，会多出一个
 * 和主屏同名的应用（实测淘宝：一个有画面、一个黑屏）。黑屏是因为这个 task 的缩略图是在
 * 副屏上截的，或者根本没截到。
 *
 * ## 做法
 *
 * 收副屏之前，把这块屏上的 task 逐个 `removeTask`。效果和用户在最近任务里把它划掉一样：
 * Activity 关掉，最近任务里的记录一起删。
 *
 * 系统划掉 task 时，会顺手杀掉这个应用的进程，但有两种情况不杀：
 * - 它在别的 task 里还有界面，比如用户主屏上开着的那个淘宝；
 * - 它有前台服务。
 * 这和用户自己划掉是一样的，不会误伤主屏上正在用的应用。
 *
 * ## 为什么还要一路记着（[remember]）
 *
 * 只在收屏那一刻问"屏上有哪些 task"会漏。实测：Agent 一轮里拉了三次淘宝，每次都新开一个
 * task；后两次的启动页（Welcome）发现淘宝主页已经在别的 task 里了，就把主页拉回那边、自己
 * finish 掉。它所在的 task 一下子空了，系统把它从屏上摘下来，却还留在最近任务里。收屏时
 * 问这块屏，它已经不在上面，于是漏掉，最近任务里照样多一个黑屏的淘宝。
 *
 * 所以每次拉起应用、每次截图之后，都把屏上的 task 记一笔（调用方是 [VirtualDisplayHost]）。
 * 刚拉起时启动页还在，空壳 task 这时一定还挂在屏上，记得到。收屏时把"现在屏上的"和
 * "之前记下的"一起移掉。
 *
 * 之前记下的 task 万一后来被挪到了别的屏上，就不归这块屏管了，不能动：
 * - 挪到了主屏：用户正看着；
 * - 挪到了 Kitten 的另一块副屏：同时跑着两轮任务、都去开同一个单实例的应用时，后开的那一轮
 *   会把 task 从前一块屏上抢过去（系统的行为，见 DeviceSession.NewInstance 的注释），这时它
 *   归那一轮用，前一轮收屏时清掉它，等于把人家正在操作的应用关了。
 * 所以移除前查一次所有屏上的 task（`getAllRootTaskInfos`），挂在别的屏上的一律排除。真正
 * 要补清的只是两种：还在这块屏上的，和已经从所有屏上摘下来、只剩最近任务里一条空壳的。
 * 查不到的话，记下的那些宁可一个都不动，只清现在还在这块屏上的。
 *
 * 只动 Kitten 自己建的副屏（调用方 [VirtualDisplayHost] 已经核对过 displayId），
 * 不对外暴露"移除任意 task"的接口：遵守 IPrivilegeService 里"接口越窄越好"的原则。
 *
 * ## 权限
 *
 * 两个接口分别要 `MANAGE_ACTIVITY_TASKS` 和 `REMOVE_TASKS`。com.android.shell 都持有，实测
 * `granted=true`，特权进程跑在它名下。
 *
 * ## 走反射
 *
 * `IActivityTaskManager` 是 @hide 的，只能反射。方法签名是在 HyperOS 3（Android 16）的
 * framework.jar 里核对过的：
 * - `getAllRootTaskInfos(): List<RootTaskInfo>`
 * - `getAllRootTaskInfosOnDisplay(int): List<RootTaskInfo>`
 * - `RootTaskInfo.childTaskIds: int[]`，以及继承自 TaskInfo 的 `taskId`、`displayId`
 * - `removeTask(int): boolean`
 *
 * 特权进程不受 non-SDK 接口限制，原因见 [InputInjector]。哪一步失败都只打日志、不抛异常：
 * 最差的结果是退回老样子，最近任务里留一条黑屏的记录，副屏照样收掉。
 */
internal object DisplayTaskCleaner {

    private const val TAG = "KittenPrivTasks"

    /** 每块副屏上出现过的 task，见类注释"为什么还要一路记着"。 */
    private val seen = ConcurrentHashMap<Int, MutableSet<Int>>()

    /** 反射拿到的接口；为 null 表示这条路不通，记和清都不做。 */
    private val api: Api? by lazy { Api.create() }

    /** 记下 [displayId] 上现在有哪些 task。 */
    fun remember(displayId: Int) {
        val ids = api?.taskIdsOn(displayId) ?: return
        if (ids.isEmpty()) return
        seen.getOrPut(displayId) { ConcurrentHashMap.newKeySet() }.addAll(ids)
    }

    /** 移除 [displayId] 上现在的 task，和之前在这块屏上记下的 task，返回移掉了几个。 */
    fun removeTasksOn(displayId: Int): Int {
        val remembered = seen.remove(displayId).orEmpty()
        val api = api ?: run {
            Log.w(TAG, "反射不可用，清不了副屏 displayId=$displayId 上的 task，最近任务里可能留下黑屏的记录")
            return 0
        }
        val current = api.taskIdsOn(displayId).orEmpty()
        // 挂在别的屏上的不动，见类注释。
        val elsewhere = api.taskIdsOnOtherDisplays(displayId)
        val earlier = if (elsewhere == null) emptyList() else remembered.filterNot { it in elsewhere }
        val taskIds = (current + earlier).distinct()
        if (taskIds.isEmpty()) return 0
        return taskIds.count { taskId ->
            api.remove(taskId).also { if (!it) Log.w(TAG, "移除 task #$taskId 没成功") }
        }.also {
            Log.i(TAG, "副屏 displayId=$displayId：屏上的 task $current，之前记下的 $remembered，移掉 $it 个")
        }
    }

    /**
     * 反射出来的 IActivityTaskManager。一次性解析好，解析失败就整个为 null，和 [InputInjector]
     * 的做法一样。
     *
     * 方法从接口上取，不从 atm.javaClass（Stub.Proxy）上取：Proxy 是 private 的内部类，拿它的
     * 方法去 invoke 会被访问检查拦下。接口是 public 的。
     */
    private class Api(
        private val atm: Any,
        private val getAllRootTaskInfos: Method,
        private val getAllRootTaskInfosOnDisplay: Method,
        private val removeTask: Method,
    ) {
        /** 这块屏上现在有哪些 task；查不到返回 null。 */
        fun taskIdsOn(displayId: Int): List<Int>? = runCatching {
            (getAllRootTaskInfosOnDisplay.invoke(atm, displayId) as? List<*>).orEmpty()
                .filterNotNull()
                .flatMap(::taskIdsOf)
                .distinct()
        }.onFailure { Log.w(TAG, "查不到 displayId=$displayId 上的 task", it) }.getOrNull()

        /**
         * 除了 [displayId] 以外，所有屏上现在挂着的 task：主屏、Kitten 的其他副屏、投屏之类的
         * 别的屏都算。查不到返回 null。
         *
         * 根 task 和它底下的 task 在同一块屏上，按根 task 的 displayId（继承自 TaskInfo）分就行。
         */
        fun taskIdsOnOtherDisplays(displayId: Int): Set<Int>? = runCatching {
            (getAllRootTaskInfos.invoke(atm) as? List<*>).orEmpty()
                .filterNotNull()
                .filter { it.javaClass.getField("displayId").getInt(it) != displayId }
                .flatMap(::taskIdsOf)
                .toSet()
        }.onFailure { Log.w(TAG, "查不到其他屏上的 task", it) }.getOrNull()

        fun remove(taskId: Int): Boolean =
            runCatching { removeTask.invoke(atm, taskId) as? Boolean ?: false }
                .onFailure { Log.w(TAG, "移除 task #$taskId 出错", it) }
                .getOrDefault(false)

        /**
         * 一个根 task 底下真正装着界面的那些 task。
         *
         * 普通全屏的根 task 自己就是叶子，childTaskIds 里就是它自己。万一读不到 childTaskIds，
         * 退回根 task 自己的 taskId（RootTaskInfo 继承自 TaskInfo）。
         */
        private fun taskIdsOf(rootTask: Any): List<Int> {
            val children = runCatching { rootTask.javaClass.getField("childTaskIds").get(rootTask) as? IntArray }
                .getOrNull()
            if (children != null && children.isNotEmpty()) return children.toList()
            val self = runCatching { rootTask.javaClass.getField("taskId").getInt(rootTask) }.getOrNull()
            return listOfNotNull(self)
        }

        companion object {
            @SuppressLint("PrivateApi")
            fun create(): Api? = runCatching {
                val atm = Class.forName("android.app.ActivityTaskManager")
                    .getMethod("getService")
                    .invoke(null) ?: error("取不到 IActivityTaskManager")
                val atmInterface = Class.forName("android.app.IActivityTaskManager")
                Api(
                    atm = atm,
                    getAllRootTaskInfos = atmInterface.getMethod("getAllRootTaskInfos"),
                    getAllRootTaskInfosOnDisplay = atmInterface
                        .getMethod("getAllRootTaskInfosOnDisplay", Int::class.javaPrimitiveType),
                    removeTask = atmInterface.getMethod("removeTask", Int::class.javaPrimitiveType),
                )
            }.getOrElse {
                Log.w(TAG, "反射 IActivityTaskManager 失败，收副屏时清不了 task", it)
                null
            }
        }
    }
}
