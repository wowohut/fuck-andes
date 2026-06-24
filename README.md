# FuckAndes

干掉 ColorOS 小布助手。电源键长按唤起 Google Gemini，手势条长按和双指识屏换成一圈即搜。

> 基于 [libxposed API 102](https://github.com/libxposed/api) 的 Xposed 模块，极简干净。适配 ColorOS 16（Android 16）。

## 痛点

用户花大几千买的旗舰机，AI 体验远不如一个免费的豆包 APP，这像话吗？

ColorOS 的小布助手，连针对屏幕内容提问这一个需求都做不好。

底座模型能力大于一切，而在手机这种高频图片输入、多模态交互的场景下更是如此。

OPPO 每年砸那么多钱搞 AI，月月更新堆出一堆花里胡哨的 AI 功能，结果底座模型还是烂成一坨——去年 DeepSeek 爆火，屁颠屁颠就去接了 R1，平时却连 Kimi K2.6、Doubao Seed 2.0 这类多模态能力像样的模型都不肯正经接入，一切都是徒劳。

非要抱着那破烂「自研」闭门造车，没有豆包手机（系统级 GUI Agent）那样的激进也就算了——反正以你 OPPO 的技术积累，也根本不可能训练出 Kimi K2.6 这种级别的底座模型，能不能先正经接入一个多模态能力正常的第三方保底？用户买单的是体验，不是 PPT。

当前模块不打算「重新定义 AI」，只是把被小布浪费掉的入口，还给真正能用的 Google Gemini 和一圈即搜。当然 Gemini 现在也很烂，3.5 Flash 尤甚，但总比小布强。

## 功能

1. **电源键长按** —— 不再走小布，直接唤起 Google Gemini。模块接管入口后优先走 Google `voiceinteraction`；如果"默认数字助理应用"不是 Google，会自动恢复绑定，并在 `voiceinteraction` 重建期间做一次性延迟重试，避免第一次长按只打开 Google App 或完全无响应。
2. **手势条长按 / 双指识屏** —— 拦截小布识屏，改为触发一圈即搜。同时在 Google 进程内伪装设备为 Samsung S24 Ultra 并补齐资格属性，以放开一圈即搜能力。
3. **锁屏唤醒后进入语音输入** —— Gemini 浮窗从锁屏唤起时，如果 Google 没有稳定进入语音识别态，模块会短延迟补发一次 `ACTION_VOICE_COMMAND`，相当于自动点麦克风。
4. **亮屏唤起自动语音输入** —— 解锁态唤起 Gemini 浮窗时同样偶发只显示输入框、不自动录音，模块对称地短延迟补发一次 `ACTION_VOICE_COMMAND`，避免还要手动点麦克风。
5. **息屏后维持 Hey Google 可用** —— Google 在部分 ColorOS 场景会在息屏后停掉软件热词检测，模块会在息屏完成后短延迟恢复已有的 Google hotword session，避免"开机第一次能唤醒，之后息屏唤不醒"的问题。
6. **将 Google App 转为系统应用** —— 通过 Magisk/KernelSU 模块将 Google App 安装为系统 priv-app，使其获得语音唤醒权限、更少的自启限制，体验接近原生。

以上功能均可在模块自带配置界面中独立开关。系统化安装入口位于配置界面「高级」分组，点击后弹窗确认，通过 root 执行安装。

## 配置界面

模块 UI 基于 [Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.2，安装后桌面出现 FuckAndes 图标。所有开关默认开启，切换实时生效。设置页按「交互接管」「助理配置」「高级」三组展示，每个设置项带分组色图标。

## 实现

模块在 `system_server`、`SystemUI`、`ColorDirectService`、`Google App` 四个进程中针对性 Hook。详见 [docs/TECHNICAL.md](docs/TECHNICAL.md)。

## 项目结构

核心代码在 `app/src/main/java/fuck/andes/` 下：

```
ModuleMain.kt              模块入口，按进程分发 Hook，加载 RemotePreferences
PowerHooks.kt              电源键长按语音入口接管与延迟重试
AssistantManager.kt        默认助理绑定校正与 voiceinteraction 自动恢复
HotwordSelfHealHooks.kt    息屏后恢复 Hey Google 监听
ContextualSearchHooks.kt   补启动 contextual_search 服务并接管包名/权限
SystemUiHooks.kt           手势条长按识屏拦截，转发一圈即搜
ColorDirectHooks.kt        双指识屏入口拦截，转发一圈即搜
CircleToSearchInvoker.kt   contextual_search binder 调用封装
GoogleAppHooks.kt          Google 进程内设备伪装与锁屏/亮屏补语音输入
GoogleEligibilityHooks.kt  Google 进程内资格属性与 feature 补齐
SystemServerHooks.kt       system_server Hook 总入口
HookSupport.kt             反射与 Hook 工具方法
ModuleConfig.kt            常量集中管理
ModuleLogger.kt            日志封装
FuckAndesApp.kt            UI 进程 Application，注册 XposedService 桥
config/Prefs.kt            配置中枢，RemotePreferences 读写
systemizer/GoogleAppSystemizerInstaller.kt  Google App 系统化安装器，Magisk/KernelSU 模块安装
ui/MainActivity.kt         Compose 配置界面入口
ui/SettingsScreen.kt       Miuix 设置页，功能开关与系统化入口
```
