// 特权进程对外暴露的接口。App 和特权进程共用这一份定义。
//
// 安全原则：这里只放"精确、受控"的方法，绝不提供 exec(String) 这类
// 能执行任意命令的通道——因为 Kitten 里有会读取外部内容的 AI，
// 接口越窄，一旦被 prompt injection 利用，损失越小。
//
// 副屏相关的几个方法按同样的原则设计：参数是尺寸和包名，不是命令行。
// 但要清楚它们依然是强能力——launchAppOnDisplay 能拉起任意应用，
// 上层在把它接给 AI 之前必须自己加一层约束（白名单或用户确认）。
package me.wjz.kitten.privilege.ipc;

// Surface 实现了 Parcelable，可以直接跨进程传。
import android.view.Surface;

interface IPrivilegeService {
    // 协议版本。App 连上后先核对，不一致就重启特权进程。
    int getVersion();

    // 特权进程的身份 uid，正常应为 2000（adb shell）。App 用它确认链路已打通。
    int getUid();

    // 让特权进程自行退出（做完清理后 System.exit）。
    void destroy();

    // ---- 副屏 ----
    //
    // 由特权进程持有副屏，而不是 App 进程：虚拟屏幕的生命周期绑在创建者身上，
    // 创建者一死屏幕就没了。特权进程比 App 进程稳定得多。

    // 创建一块虚拟副屏，返回 displayId；失败返回 -1。
    int createVirtualDisplay(int width, int height, int densityDpi);

    // 释放指定副屏。displayId 不是我们创建的则忽略。
    void releaseVirtualDisplay(int displayId);

    // 释放全部副屏。
    void releaseAllVirtualDisplays();

    // 在指定屏幕上拉起某个应用的启动 Activity。
    //
    // forceNewInstance=false 时，如果该应用已经在别的屏幕上开着，
    // 系统会把它整个 task 搬到目标屏——也就是会从原来那块屏上消失。
    // 传 true 则加 FLAG_ACTIVITY_MULTIPLE_TASK 另开一个 task，两块屏各留一份。
    // 注意即便如此也仍是同一个进程、同一份数据，登录态是共享的；
    // 而且 launchMode=singleTask/singleInstance 的 Activity 会无视这个标志。
    boolean launchAppOnDisplay(String packageName, int displayId, boolean forceNewInstance);

    // 把副屏的画面接到 surface 上，让用户看见；传 null 则切回离屏缓冲、重新隐身。
    //
    // 副屏可见与否，取决于它往哪个 Surface 渲染：
    //   离屏（ImageReader）→ 没有东西把它合成到物理屏 → 用户看不见
    //   SurfaceView 的 Surface → 正常参与合成 → 用户看得见
    // VirtualDisplay.setSurface 支持运行时切换，所以"显示/隐藏"是免费的。
    boolean setDisplaySurface(int displayId, in Surface surface);
}
