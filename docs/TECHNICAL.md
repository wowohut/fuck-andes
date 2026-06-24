# 技术实现

模块在四个进程中分别作了针对性的 Hook：

## system_server

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 拦截系统分发给小布的唤醒消息（`what == 0x3F3`），接管电源键长按的最终入口。
- **数字助理配置修复**：开机、解锁、切用户时，通过 `AssistantManager` 低频校正 `android.app.role.ASSISTANT` 及相关 secure settings，自动校正并尽量维持 Google 为默认助理。
- **唤起逻辑优化**：优先使用 `VoiceInteractionManagerService` 拉起 Google `voiceinteraction`。如果助理配置刚恢复而服务还没 ready，会在系统 handler 上做有限次的延迟重试补偿。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。作为一圈即搜的底层依赖始终执行，不可关闭。

## SystemUI

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

## ColorDirectService

拦截 `com.coloros.directui.ui.CollectInfoActivity.M(Intent)`，读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`。确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

## Google App

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力；同时拦截 `SystemProperties` 和 `PackageManager.hasSystemFeature()` 的关键查询，让 Google App 看到 `ro.opa.eligible_device=true`、`GOOGLE_BUILD` 与 `GOOGLE_EXPERIENCE`。这对应现成 Google App Magisk 模块和 OpenGApps 常用的 OPA eligibility 做法，但限定在 Google App 进程内，不改系统文件。机型伪装与资格补齐作为一圈即搜的底层依赖始终执行，不可关闭。

锁屏唤起 Gemini 浮窗后，Google 偶发只显示输入框、不启动录音。模块优先直接 Hook `FloatyActivity.onResume()`，找不到目标类时才回退到全局 `Activity.onResume()`；确认仍处于锁屏后，带冷却地补发一次 `ACTION_VOICE_COMMAND`，避免用户还要手动点麦克风。亮屏（解锁态）唤起时同样存在该偶发问题，因此在同一 hook 点对称增加亮屏分支：确认仍处于解锁态后同样补发一次 `ACTION_VOICE_COMMAND`。锁屏与亮屏共用同一冷却时间戳，防止同一浮窗 `onResume` 短时间内被两个分支重复补发；两分支各自在延迟任务执行前复查对应开关与锁屏状态是否仍匹配。

## Google App 系统化

Google App 作为普通用户应用时，缺乏语音唤醒所需的系统权限，且容易 ColorOS 被自启管理杀掉。模块内置了 Magisk/KernelSU 模块，可将 Google App 安装为系统 priv-app。

安装流程由 `GoogleAppSystemizerInstaller` 负责：

- 检测 root 管理器类型（Magisk 或 KernelSU）
- KernelSU 需先安装 meta-overlayfs 模块，否则不支持模块安装
- 将内置的 Google App 系统化模块通过 root 执行安装
- 安装成功后提示用户重启生效

系统化安装是用户主动操作，不自动执行。安装入口位于设置页「高级」分组，点击后弹窗确认说明原因与操作方式，用户确认后才开始安装。

## 配置与实时生效

模块 UI 基于 Miuix 0.9.2。配置链路如下：

- **UI 进程**：`FuckAndesApp` 在 `Application.onCreate` 注册 `XposedServiceHelper`，框架通过 `XposedProvider` 推送 binder 后拿到 `XposedService`。设置页通过 `XposedService.getRemotePreferences()` 获取可写的 `SharedPreferences`，写入用 `commit()` 同步等待 binder 提交到 LSPosed 数据库；提交失败时保持原开关状态。
- **Hook 进程**：`ModuleMain.onModuleLoaded` 调用 `XposedInterface.getRemotePreferences()` 缓存只读 `SharedPreferences` 到 `Prefs`。各 Hook 拦截回调入口直接读 `Prefs.isEnabled(key)`，关闭则走原逻辑；因此正常使用时，配置切换后的下一次相关触发表现为实时生效。这里的实时生效来自 Hook 入口读取当前配置，不是 libxposed API 102 的 hot reload 特性。
- **延迟任务复查**：已排队的延迟任务（`PowerHooks` recovery 重试、`HotwordSelfHealHooks` retry、`GoogleAppHooks` 锁屏/亮屏语音命令）在执行前再次检查对应开关，避免用户在任务排队期间关闭开关后被已排队任务绕过。

不可关闭的底层依赖（ContextualSearch 服务补齐、机型伪装、资格补齐）始终执行，不暴露开关。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不常驻额外线程、不持续写日志
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，息屏后的 Hey Google 恢复路径不主动查写默认助理配置
- 成功路径默认静默（`ENABLE_VERBOSE_LOGS=false`）
- 只有在默认助理刚恢复但 `voiceinteraction` 尚未完成重建时，才会追加少量一次性延迟重试，成功后立即失效，不留后台负担
- 息屏后的 Hey Google 恢复只响应系统息屏事件；最多串行尝试 3 次，失败才投递下一次，亮屏/成功/结束都会移除未执行 callback
- Google App 的锁屏/亮屏语音输入优先 Hook 固定 FloatyActivity，不常驻拦截 Google App 所有页面；锁屏与亮屏分支共用同一冷却时间戳，不会重复补发

## 预期行为

正常情况下，第一次长按电源键就能直接唤起 Gemini。

如果模块刚把"默认数字助理应用"切回 Google，系统还在异步重建相关服务，模块会尽量拦截掉这期间失败的调用流程（避免它傻傻地回退去打开 Google App 主界面），并在后台短时间内发起最多 3 次的延迟重试（从 1.2 秒起步）。实测即便在这种刚恢复配置的情况下，第一次长按通常也能顺利拉起 Gemini 浮窗。

配置界面切换开关后会同步提交到 LSPosed 侧 RemotePreferences；Hook 回调和延迟任务执行前都会读取对应开关，所以后续触发按当前配置执行。
