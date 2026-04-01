# FuckAndes

干掉 ColorOS 小布助手。电源键长按唤起 Google Gemini，手势条长按识屏换成一圈即搜。

一个基于 libxposed API 101 的极简 Xposed 模块。

> 仅在 RMX5200（真我GT8 Pro）/ realme UI (ColorOS) 16.0.5.702 / Android 16 (API 36) 上验证。

## 痛点

ColorOS 的小布助手极其难用、极其蠢，但系统偏偏把它塞进了电源键长按和手势条长按两个最高频的入口。这个模块就是要把小布从这些入口里踢出去，换上真正能用的东西：

1. **电源键长按** —— 不再走小布，直接唤起 Google Gemini。模块接管入口后优先走 Google `voiceinteraction`；如果"默认数字助理应用"不是 Google，会自动恢复绑定，并在 `voiceinteraction` 重建期间做一次性延迟重试，避免第一次长按只打开 Google App 或完全无响应。
2. **手势条长按** —— 拦截小布识屏，改为触发一圈即搜。同时在 Google 进程内伪装设备为 Samsung S24 Ultra，以放开一圈即搜能力。

## 实现原理

模块在三个进程中分别作了针对性的 Hook：

**system_server**

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 拦截系统分发给小布的唤醒消息（`what == 0x3F3`），接管电源键长按的最终入口。
- **数字助理配置修复**：开机、解锁、切用户时，通过 `AssistantManager` 低频校正 `android.app.role.ASSISTANT` 及相关 secure settings，自动校正并尽量维持 Google 为默认助理。
- **唤起逻辑优化**：优先使用 `VoiceInteractionManagerService` 拉起 Google `voiceinteraction`。如果助理配置刚恢复而服务还没 ready，会在系统 handler 上做有限次的延迟重试补偿。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 的调用权限。

**SystemUI**

拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。

**Google App**

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不常驻额外线程、不持续写日志
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，避免重复查 `RoleManager` 和 `Settings.Secure`
- 成功路径默认静默（`ENABLE_VERBOSE_LOGS=false`）
- 只有在默认助理刚恢复但 `voiceinteraction` 尚未完成重建时，才会追加少量一次性延迟重试，成功后立即失效，不留后台负担

## 预期行为

正常情况下，第一次长按电源键就能直接唤起 Gemini。

如果模块刚把"默认数字助理应用"切回 Google，系统还在异步重建相关服务，模块会尽量拦截掉这期间失败的调用流程（避免它傻傻地回退去打开 Google App 主界面），并在后台短时间内发起最多 3 次的延迟重试（从 1.2 秒起步）。实测即便在这种刚恢复配置的情况下，第一次长按通常也能顺利拉起 Gemini 浮窗。

## 项目结构

核心代码在 `app/src/main/java/fuck/andes/` 下：

```
ModuleMain.kt              模块入口，按进程分发 Hook
PowerHooks.kt              电源键长按语音入口接管与延迟重试
AssistantManager.kt        默认助理绑定校正与 voiceinteraction 自动恢复
ContextualSearchHooks.kt   补启动 contextual_search 服务并接管包名/权限
SystemUiHooks.kt           手势条长按识屏拦截，转发一圈即搜
GoogleAppHooks.kt          Google 进程内设备伪装
SystemServerHooks.kt       system_server Hook 总入口
HookSupport.kt             反射与 Hook 工具方法
ModuleConfig.kt            常量集中管理
ModuleLogger.kt            日志封装
```
