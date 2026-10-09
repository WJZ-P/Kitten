# Kitten 🐾

一个住在你安卓手机里的猫娘 AI 助手：一句话，帮你回消息、比价、操作 App。

> 🚧 早期开发中，目前只有项目骨架。

## 设计原则

- **你说了算**：发送消息、付款等敏感操作，始终由你确认后才执行。
- **看得见**：Kitten 在做什么、做到哪一步，随时可见，随时可以叫停。
- **隐私优先**：通知内容等个人信息不写入日志；能在端侧处理的尽量不出手机。

## 技术方案

| 能力 | 实现 |
| --- | --- |
| 感知新消息 | `NotificationListenerService` |
| 读取屏幕、执行操作 | `AccessibilityService` |
| 增强能力（可选） | 基于 ADB 权限的特权后端（Shizuku / 内置），规划中 |
| 界面 | Jetpack Compose + Material 3 |

## 构建

- Android Studio 2026.2.1 或更新版本
- JDK 17+
- Android SDK Platform 37，Build Tools 36.0.0
- 最低支持 Android 11（API 30）

用 Android Studio 打开项目根目录即可。

## 路线图

- [x] 项目骨架：无障碍服务、通知监听、权限引导页
- [ ] 读取当前页面，输出压缩后的 UI 树
- [ ] 按节点 id 执行点击、输入、滑动
- [ ] 接入 LLM，跑通 Agent 循环
- [ ] 回复草稿 + 用户确认后发送
- [ ] 悬浮窗：状态显示与停止按钮
- [ ] 评估任务集
