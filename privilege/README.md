# privilege 模块

让 Kitten 在**不依赖 Shizuku App**、**不接电脑**的前提下，拿到 adb shell 级别的权限。
思路和 Shizuku 一样，但特权服务由我们自己的代码实现。

## 原理

```
┌──────────── Kitten App 进程 (uid 10xxx) ────────────┐
│  PrivilegeManager ── 状态机、对外入口                 │
│       │                                              │
│  ① AdbClient (client/，2b 实现)                      │
│     配对码在通知里收 → 配对 → TLS 连上本机 adbd        │
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
- [x] 2c：配对/状态引导界面（app 模块的 PrivilegeCard）+ 通知栏配对（AdbPairingService）
- [ ] 3：一次性授权（WRITE_SECURE_SETTINGS 等）+ 一键配置

### client/ 说明

- `KittenAdbManager`：持有 ADB 密钥对和自签证书（存在 App 私有目录，复用）。
- `AdbMdnsHelper`：用 mDNS 持续监听配对/连接端口。
- `AdbBackend`：在已连接的 shell 里执行启动命令。
- `AdbPairingService`：在通知里收配对码（见下）。
- `WirelessDebugController`：串起"配对 → 连接 → 启动"全流程，暴露进度。全进程单例，
  界面和配对服务共用同一份状态。

### 为什么配对码要填在通知里

系统「使用配对码配对设备」的配对码，**只在那个弹窗打开期间有效**。用户切回 Kitten
输入的瞬间弹窗就关了，配对码同时作废，所以 App 里放输入框是走不通的。

通知栏是少数能盖在弹窗上、又不会把弹窗挤掉的交互入口。`AdbPairingService` 是一个前台
服务，mDNS 发现配对端口后，把通知换成带内联回复（`RemoteInput`）的版本，用户下拉通知栏
直接把配对码发回来。Shizuku 也是这个做法。

几个容易踩的点：

- **通知必须极简**：操作按钮只存在于通知的「展开布局」里。带长文本、尤其是
  `BigTextStyle` 的通知，在 MIUI / HyperOS 下默认折叠显示，「输入配对码」按钮就彻底
  看不见。所以那条通知只放标题 + 一个按钮，不设 `setContentText`/`setStyle`。
- 回复用的 PendingIntent 在 API 31+ 必须带 `FLAG_MUTABLE`，否则系统填不进用户输入。
- 还要带 `FLAG_UPDATE_CURRENT`：PendingIntent 判等不看 extras，不加就会一直用第一次
  发现的那个旧端口（重开弹窗端口会变）。
- 前台服务类型用 `specialUse` 而不是 Shizuku 的 `shortService`：后者在 API 34+ 有 3 分钟
  硬超时，用户在设置里摸索很容易超时。
- API 33+ 要运行时申请 `POST_NOTIFICATIONS`，且必须在 App 处于前台时启动这个服务。

依赖：libadb-android、sun-security-android、conscrypt-android（均走 JitPack，settings 里已加仓库）。

## 已知待实测

- `BinderSender` 目前只实现 API 31+（AttributionSource）路径，API 30 待补。
- 特权进程的 binder 线程池是否自动启动、`ServerMain` 保活方式，需在 2b 能真正启动后验证。
