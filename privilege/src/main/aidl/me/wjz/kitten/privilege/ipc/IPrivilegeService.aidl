// 特权进程对外暴露的接口。App 和特权进程共用这一份定义。
//
// 安全原则：这里只放"精确、受控"的方法，绝不提供 exec(String) 这类
// 能执行任意命令的通道——因为 Kitten 里有会读取外部内容的 AI，
// 接口越窄，一旦被 prompt injection 利用，损失越小。
package me.wjz.kitten.privilege.ipc;

interface IPrivilegeService {
    // 协议版本。App 连上后先核对，不一致就重启特权进程。
    int getVersion();

    // 特权进程的身份 uid，正常应为 2000（adb shell）。App 用它确认链路已打通。
    int getUid();

    // 让特权进程自行退出（做完清理后 System.exit）。
    void destroy();
}
