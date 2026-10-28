package me.wjz.kitten.agent

/**
 * 哪个应用正被哪一轮任务操作着。
 *
 * ## 为什么要有它
 *
 * 以后做任务规划，会把一个大任务拆开，分给几块副屏并发去做（一块比价、一块查物流……）。
 * 副屏可以开很多块，但同一个应用不行：
 *
 * - 单实例的应用（不少聊天、购物软件的启动页是 singleTask），系统只给它一个 task。后开的那块屏
 *   会把它从前一块屏上抢走，前一轮接下来的点击全部落空，两边都做不完。
 * - 能多开的应用倒是不会被抢，但两轮任务同时改同一个账号里的东西（购物车、聊天草稿），一样会
 *   互相踩。
 *
 * 所以一律一个应用同一时间只给一轮任务用：拉起应用之前先占住它（[tryAcquire]），占不到就告诉
 * 模型"这个应用正被别的任务用着"，让它先做别的；这一轮换到别的应用、或者结束时放掉。
 *
 * ## 占的是"现在正在操作的那个应用"
 *
 * 一轮任务从一个应用切到另一个时，前一个就放掉了。它还留在这块副屏的后台，但没人操作了，
 * 别的任务要用就让它拿走：它的 task 会被挪到那块屏上，这块屏收掉时也不会误清它（见特权侧的
 * DisplayTaskCleaner）。
 *
 * ## 管不到的
 *
 * 只认 launch_app 拉起的应用。应用自己跳到另一个应用（比如购物软件拉起支付软件付款）不经过
 * 这里，占不住。真碰上了，表现是另一边的点击落空，模型重新抓屏会发现界面不对。
 *
 * 主屏也不归这里管：没有特权进程、退回主屏操作的任务，同一时间本来就只能有一个，主屏只有
 * 一块、前台只有一个应用。
 */
object AppLeases {

    /** 包名 → 占着它的那一轮（一般是那一轮的 [DeviceSession]）。 */
    private val holders = HashMap<String, Any>()

    /** 占住 [packageName]。已经被 [owner] 自己占着也算成功；被别人占着返回 false。 */
    @Synchronized
    fun tryAcquire(packageName: String, owner: Any): Boolean =
        holders.getOrPut(packageName) { owner } === owner

    /** 放掉 [owner] 占着的 [packageName]。不是它占的不动。 */
    @Synchronized
    fun release(packageName: String, owner: Any) {
        if (holders[packageName] === owner) holders.remove(packageName)
    }

    /** 放掉 [owner] 占着的全部应用。一轮任务结束时调。 */
    @Synchronized
    fun releaseAll(owner: Any) {
        holders.values.removeAll { it === owner }
    }
}
