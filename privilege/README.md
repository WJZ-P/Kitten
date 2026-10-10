# privilege 模块

让 Kitten 在**不依赖 Shizuku App**、**不接电脑**的前提下，拿到 adb shell 级别的权限。
思路和 Shizuku 一样，但特权服务由我们自己的代码实现。

## 原理

```
┌──────────── Kitten App 进程 (uid 10xxx) ────────────┐
│  PrivilegeManager ── 状态机、对外入口                 │
│       │                                              │
│  ① AdbClient (client/，2b 实现)                      │
│     mDNS 发现无线调试端口 → 配对 → TLS 连上本机 adbd   │
│       │ 执行 Starter 给出的命令                       │
│  ② app_process 以 shell 身份启动 ServerMain          │
│                                                      │
│  ④ PrivilegeReceiverProvider ◀── Binder ──┐          │
└────────────────────────────────────────────┼─────────┘
                                             │ ③ 交回 binder
┌──────── 特权进程 (uid 2000) ────────────────┴────────┐
│  ServerMain → PrivilegeService (IPrivilegeService)   │
└───────────────────────────────────────────────────────┘
```

③ 交回 binder：特权进程反射拿到 App 那个 ContentProvider 的 IContentProvider，
调 `call("sendBinder", ...)` 把 binder 放进去。特权进程不是正常 App 进程，
没有 non-SDK 接口限制，可以直接反射调用隐藏 API。

## 安全约束

- `IPrivilegeService` 只放精确、受控的方法，**绝不提供执行任意命令的通道**
  （Kitten 里有会读外部内容的 AI，接口越窄，被 prompt injection 利用的损失越小）。
- 接收 binder 的 ContentProvider 用 `INTERACT_ACROSS_USERS_FULL` 保护，普通 App 调不到。
- 每次启动带一个一次性 token，App 核对后才接受 binder。

## 进度

- [x] 2a：AIDL、特权端服务、Binder 交回、PrivilegeManager 骨架
- [x] 2b：client/ —— 内置 ADB 客户端（libadb-android 3.1.1），mDNS + 配对 + 连接 + 启动
- [x] 2c：配对/状态引导界面（app 模块的 PrivilegeCard）
- [ ] 3：一次性授权（WRITE_SECURE_SETTINGS 等）+ 一键配置

### client/ 说明

- `KittenAdbManager`：持有 ADB 密钥对和自签证书（存在 App 私有目录，复用）。
- `AdbMdnsHelper`：用 mDNS 发现配对/连接端口。
- `AdbBackend`：在已连接的 shell 里执行启动命令。
- `WirelessDebugController`：串起"发现 → 配对 → 连接 → 启动"全流程，暴露进度。

依赖：libadb-android、sun-security-android、conscrypt-android（均走 JitPack，settings 里已加仓库）。

## 已知待实测

- `BinderSender` 目前只实现 API 31+（AttributionSource）路径，API 30 待补。
- 特权进程的 binder 线程池是否自动启动、`ServerMain` 保活方式，需在 2b 能真正启动后验证。
