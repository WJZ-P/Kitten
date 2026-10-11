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

## 版本下限

minSdk = 33（Android 13），由三条约束叠出来：

- **API 30**：无线调试本身才出现。我们不支持 root、也不碰电脑，手机内无线 ADB
  是唯一的提权路径，没有更低的退路。
- **API 31**：`BinderSender` 交回 binder 走的是 `IContentProvider.call` 的
  `AttributionSource` 签名，API 30 上签名不同。低于 31 整条链路跑不通。
- **API 33**：开机自动恢复特权进程要靠系统记住"受信任的 WiFi 网络"，而
  `adb_temp_keys.xml` 的解析 bug 到 Android 13 才修（AOSP change 2128832，
  Bug 236299256）。在那之前每次重启都会被当成陌生网络、弹框等用户确认。
  Shizuku 的开机自启也卡在同一刀上。

## 配一次，以后全自动

ADB 只需要人工走一次。之后即使重启，Kitten 也能自己把特权链路重建起来。

支点是 **WRITE_SECURE_SETTINGS**：它的 protectionLevel 带 `development` 标志，
所以能用 `pm grant` 授给普通应用，而且**授给的是 App 自己的 uid，永久有效**——
扛重启，也不需要特权进程在场。

```
首次（人工一次）
  配对 → 连接 → pm grant WRITE_SECURE_SETTINGS → 拉起特权进程
                        ↓
              App 自己开无障碍 / 通知使用权

重启后（零交互，BootCompletedReceiver）
  自己打开无线调试 → mDNS 发现端口 → 用本地密钥连上 → 重新拉起特权进程
```

能这样闭环，是因为配对状态两边都持久化了：我们的私钥和证书在 `filesDir`，
公钥在系统的已授权列表里。连接只校验公钥，不需要配对码。

注意能力分两层：

- **一次性授权就够的**：`WRITE_SECURE_SETTINGS`（带 `development`）
- **必须有活着的特权进程**：`INJECT_EVENTS`、`CAPTURE_VIDEO_OUTPUT`、
  `INTERNAL_SYSTEM_WINDOW`、`REAL_GET_TASKS` —— 全是纯 signature 级，
  `pm grant` 授不了，所以每次开机都要重建进程

### 实测记录（HyperOS / Android 16，2026-10-11）

重启后全自动恢复**跑通**，关键日志：

```
KittenBoot:       开机自动恢复开始，action=android.intent.action.BOOT_COMPLETED
KittenSelfConfig: 打开无线调试：adb_wifi_enabled 0 -> 1，adb_enabled=1，回读正常
KittenWirelessAdb: 开始自动连接
```

结果：特权进程 Uid=2000、PPid=1、fd 指向 /dev/null，无 shell 泄漏。

两点 ROM 差异要知道：

- **小米的自启动管控会拦开机广播**，日志为
  `BroadcastQueueInjector: ... process is not permitted to auto start`。
  我们能收到，是因为无障碍服务先把进程拉起来了（系统拉起的服务不受管控），
  广播才搭上车——代价是比开机晚了约 50 秒。
- **通知使用权可能扛不住重启**（实测重启后又变回未开启）。`applyAll` 每次回到
  前台都跑，正好自愈；只在首次配置跑一遍的话这里会悄悄失效。

### 为什么不支持 direct boot

ADB 密钥存在 `filesDir`，属于凭据加密存储，**用户解锁前读不到**。所以开机接收器
不标 `directBootAware`、也不听 `LOCKED_BOOT_COMPLETED`——解锁前跑只会误报
"从没配对过"然后白跑一趟。

Shizuku 的做法是把密钥放进 `createDeviceProtectedStorageContext()`，于是解锁前
也能恢复。我们不跟：ADB 私钥等同于设备的 shell 权限，不该为了早几秒就放进
"无需用户凭据即可解密"的存储里。

## 看日志

所有 tag 都以 `Kitten` 开头，一条命令全抓：

```
adb logcat -s KittenWirelessAdb:V KittenAdbShell:V KittenAdbPairing:V \
              KittenPrivilege:V KittenPrivServer:V KittenPrivSender:V \
              KittenSelfConfig:V KittenBoot:V
```

| tag | 负责哪一段 |
|---|---|
| `KittenAdbPairing` | 配对通知：mDNS 发现端口、收到配对码 |
| `KittenWirelessAdb` | 连接 adbd、自助授权、拉起特权进程 |
| `KittenAdbShell` | ADB shell 的原始回显（`pm grant` 的输出在这里） |
| `KittenPrivServer` | 特权进程自己（uid 应为 2000） |
| `KittenPrivSender` | 交回 binder 的反射调用 |
| `KittenPrivilege` | App 侧收到 binder、最终就绪状态 |
| `KittenSelfConfig` | 自助开无障碍 / 通知使用权 / 无线调试 |
| `KittenBoot` | 开机自动恢复的起止和耗时 |

首次配对成功时，关键几行长这样：

```
KittenWirelessAdb: 已连上 adbd
KittenWirelessAdb: 尚未取得 WRITE_SECURE_SETTINGS，执行一次性自助授权
KittenWirelessAdb: 自助授权成功：已取得 WRITE_SECURE_SETTINGS，之后可开机自动恢复
KittenWirelessAdb: 正在拉起特权进程…
KittenPrivServer:  privilege server starting, uid=2000
KittenPrivilege:   特权已就绪，uid=2000, version=1
KittenSelfConfig:  自助配置：无障碍=TURNED_ON 通知使用权=TURNED_ON ADB授权超时=TURNED_ON
```

重启后应该看到（全程无人操作）：

```
KittenBoot:        开机自动恢复开始，action=android.intent.action.LOCKED_BOOT_COMPLETED
KittenSelfConfig:  打开无线调试：adb_wifi_enabled 0 -> 1，adb_enabled=1，回读正常
KittenWirelessAdb: 开始自动连接
KittenPrivilege:   特权已就绪，uid=2000, version=1
KittenBoot:        开机自动恢复结束，耗时 3421ms，特权状态=Ready(uid=2000)
```

排查要点：

- **`自助授权未生效`** → 去 `KittenAdbShell` 看 `pm grant` 的原始输出
- **`回读异常，这个 ROM 可能不认这个设置项`** → 该 ROM 写了 `adb_wifi_enabled`
  但没真的启动 adbd 监听，开机自动恢复在这台设备上走不通
- **特权进程起不来** → `adb shell cat /data/local/tmp/kitten_privilege.log`，
  ServerMain 启动**之前**的崩溃只会出现在那里

## 已验证

- 特权进程的 binder 线程池会自动启动，`Looper.prepareMainLooper()` + `loop()`
  的保活方式有效（真机日志：`特权已就绪，uid=2000, version=1`）。
