# FuckAndes

干掉 ColorOS 小布助手。电源键长按唤起 Google Gemini，手势条长按和双指识屏换成一圈即搜；官方隐藏手势指示条后，仍然保留底部长按识屏。

一个基于 libxposed API 101 的极简 Xposed 模块。

> 仅在 RMX5200（真我GT8 Pro）/ realme UI (ColorOS) 16.0.5.704 / Android 16 (API 36) 上验证；理论上适用于所有 ColorOS 16 设备。

## 痛点

小布助手难用、糟糕，底座模型更是落后于时代，却占着电源键长按和手势条长按这两个高频入口。当前模块就是把这些入口换成真正可用的 Google Gemini 和一圈即搜。

AI 助手的上限首先由底座模型决定，尤其手机这种高频图片输入、多模态交互场景。与其堆一堆花哨功能，不如先接入一个多模态能力正常的第三方模型做保底；用户买单的是体验，不是“自研”叙事或 PPT。

1. **电源键长按** —— 不再走小布，直接唤起 Google Gemini。模块接管入口后优先走 Google `voiceinteraction`；如果"默认数字助理应用"不是 Google，会自动恢复绑定，并在 `voiceinteraction` 重建期间做一次性延迟重试，避免第一次长按只打开 Google App 或完全无响应。
2. **手势条长按 / 双指识屏** —— 拦截小布识屏，改为触发一圈即搜。同时在 Google 进程内伪装设备为 Samsung S24 Ultra，以放开一圈即搜能力。
3. **隐藏手势指示条但保留识屏** —— 继续使用系统设置里的官方“隐藏手势指示条”开关。开启后去掉底部白条区域和小横条，但长按底部仍然能按原来的方式唤起识屏 / 一圈即搜。
4. **Google 设备资格补齐** —— 在 Google App 进程内补齐 `ro.opa.eligible_device` 和 Google Experience feature，减少非完整 GMS 机型上 Gemini/Assistant 能力被降级的概率。
5. **锁屏唤醒后进入语音输入** —— Gemini 浮窗从锁屏唤起时，如果 Google 没有稳定进入语音识别态，模块会短延迟补发一次 `ACTION_VOICE_COMMAND`，相当于自动点麦克风。
6. **息屏后维持 Hey Google 可用** —— Google 在部分 ColorOS 场景会在息屏后停掉软件热词检测，模块会在息屏完成后短延迟恢复已有的 Google hotword session，避免“开机第一次能唤醒，之后息屏唤不醒”。

## 实现原理

模块在四个进程中分别作了针对性的 Hook：

**system_server**

- **电源键接管**：Hook `PhoneWindowManagerExtImpl$OplusSpeechHandler.handleMessage()` 拦截系统分发给小布的唤醒消息（`what == 0x3F3`），接管电源键长按的最终入口。
- **数字助理配置修复**：开机、解锁、切用户时，通过 `AssistantManager` 低频校正 `android.app.role.ASSISTANT` 及相关 secure settings，自动校正并尽量维持 Google 为默认助理。
- **唤起逻辑优化**：优先使用 `VoiceInteractionManagerService` 拉起 Google `voiceinteraction`。如果助理配置刚恢复而服务还没 ready，会在系统 handler 上做有限次的延迟重试补偿。
- **息屏后维持 Hey Google 可用**：Hook `PhoneWindowManager.screenTurnedOff()`，在默认显示息屏后短延迟检查 Google 的 `SoftwareTrustedHotwordDetectorSession`。只有已有 `mSoftwareCallback` 且当前未 running 时，才恢复 `startListeningFromMicLocked()`；亮屏或恢复成功后会取消未执行任务。
- **一圈即搜支持**：强制启用 `ContextualSearchManagerService`，将包名指向 Google App，并放行 `SystemUI` 与 ColorDirectService 的调用权限。
- **隐藏导航栏占位**：在 `system_server` 内 Hook `Resources.getDimension*()`，仅当官方“隐藏手势指示条”开关开启时，覆盖导航栏 frame/height/width 等 dimen 为 0，避免隐藏小横条后底部仍保留白条占位。

**SystemUI**

- **手势条长按识屏接管**：拦截底部手势条长按触发的 OPPO OCR 识屏，通过 binder 直接调用 `contextual_search` 服务触发一圈即搜。
- **官方隐藏手势指示条增强**：在 `SystemUI` 内 Hook `Resources.getDimension*()`，官方隐藏开关开启时把手势小横条的宽、高、圆角和底部偏移置 0；同时保留必要的底部手势区域高度，避免把长按识屏入口一起清掉。
- **隐藏后补回长按事件**：Hook `SideGestureDetector.onMotionEventImpl(MotionEvent)`，只在官方隐藏手势指示条开启时处理底部 `ACTION_DOWN/MOVE/UP/CANCEL`。命中底部手势区域后，按原厂时序调用 `GestureHomeHandleEventController` 的 `onDown()`、`onShowPress()`、`onPreLongPress()`、`onLongClick()`，让原本依赖手势指示条的长按识屏链路继续进入 `OplusOcrScreenBusiness.onLongPressed()`。

**ColorDirectService**

拦截 `com.coloros.directui.ui.CollectInfoActivity.M(Intent)`，读取 `startInfo.directExt` 中的 `fingerTrigger` 与 `touchInfo.fingerCount`。确认是双指识屏后，直接调用 `contextual_search` 服务触发一圈即搜，并关闭小布识屏页面；调用失败才回退小布原逻辑。

**Google App**

伪装设备为 Samsung S24 Ultra，使 Google 启用一圈即搜能力；同时拦截 `SystemProperties` 和 `PackageManager.hasSystemFeature()` 的关键查询，让 Google App 看到 `ro.opa.eligible_device=true`、`GOOGLE_BUILD` 与 `GOOGLE_EXPERIENCE`。这对应现成 Google App Magisk 模块和 OpenGApps 常用的 OPA eligibility 做法，但限定在 Google App 进程内，不改系统文件。

锁屏唤起 Gemini 浮窗后，Google 偶发只显示输入框、不启动录音。模块优先直接 Hook `FloatyActivity.onResume()`，找不到目标类时才回退到全局 `Activity.onResume()`；确认仍处于锁屏后，带冷却地补发一次 `ACTION_VOICE_COMMAND`，避免用户还要手动点麦克风。

## 功耗与开销

追求极简，绝不给系统增加额外负担：

- 不轮询、不保活 Google 进程、不常驻额外线程、不持续写日志
- 热路径只保留当前机型实际验证有效的 `OplusSpeechHandler` hook
- 默认助理配置检查带 15 秒冷却，息屏后的 Hey Google 恢复路径不主动查写默认助理配置
- 成功路径默认静默（`ENABLE_VERBOSE_LOGS=false`）
- 只有在默认助理刚恢复但 `voiceinteraction` 尚未完成重建时，才会追加少量一次性延迟重试，成功后立即失效，不留后台负担
- 息屏后的 Hey Google 恢复只响应系统息屏事件；最多串行尝试 3 次，失败才投递下一次，亮屏/成功/结束都会移除未执行 callback
- Google App 的锁屏语音输入优先 Hook 固定 FloatyActivity，不常驻拦截 Google App 所有页面
- 隐藏手势指示条只在官方开关打开时生效；`Resources` hook 只命中特定导航栏/手势条 dimen，其余资源读取全部放行
- 隐藏后补回长按识屏只观察 `SideGestureDetector` 的触摸事件，原厂手势逻辑继续执行；控制器方法通过 libxposed `getInvoker()` 缓存调用，失败才打节流日志

## 构建验证

- `./gradlew assembleDebug`
- `./gradlew assembleRelease`

Release 构建开启 R8 minify 与资源收缩，使用 `proguard-android-optimize.txt`。Xposed 入口 `fuck.andes.ModuleMain` 通过窄范围 keep 规则保留，`META-INF/xposed/java_init.list`、`scope.list`、`module.prop` 会保留进 APK。

## 预期行为

正常情况下，第一次长按电源键就能直接唤起 Gemini。

如果模块刚把"默认数字助理应用"切回 Google，系统还在异步重建相关服务，模块会尽量拦截掉这期间失败的调用流程（避免它傻傻地回退去打开 Google App 主界面），并在后台短时间内发起最多 3 次的延迟重试（从 1.2 秒起步）。实测即便在这种刚恢复配置的情况下，第一次长按通常也能顺利拉起 Gemini 浮窗。

在 `系统导航方式 -> 全面屏手势` 中打开官方“隐藏手势指示条”后，底部白条区域和小横条会消失；只要“长按手势指示条唤醒小布识屏”仍开启，继续长按底部手势区域即可触发一圈即搜。关闭官方隐藏开关后，模块不再覆盖这些导航栏 dimen，系统手势指示条按原样显示。

## 项目结构

核心代码在 `app/src/main/java/fuck/andes/` 下：

```
ModuleMain.kt              模块入口，按进程分发 Hook
PowerHooks.kt              电源键长按语音入口接管与延迟重试
AssistantManager.kt        默认助理绑定校正与 voiceinteraction 自动恢复
HotwordSelfHealHooks.kt    息屏后恢复 Hey Google 监听
ContextualSearchHooks.kt   补启动 contextual_search 服务并接管包名/权限
SystemUiHooks.kt           手势条长按识屏拦截，转发一圈即搜
NavigationBarHideHooks.kt  跟随官方隐藏开关，隐藏导航栏占位和手势小横条
HiddenGestureHandleHooks.kt 隐藏手势小横条后补回底部长按识屏事件链
ColorDirectHooks.kt        双指识屏入口拦截，转发一圈即搜
CircleToSearchInvoker.kt   contextual_search binder 调用封装
GoogleAppHooks.kt          Google 进程内设备伪装
GoogleEligibilityHooks.kt  Google 进程内资格属性与 feature 补齐
SystemServerHooks.kt       system_server Hook 总入口
HookSupport.kt             反射与 Hook 工具方法
ModuleConfig.kt            常量集中管理
ModuleLogger.kt            日志封装
```
