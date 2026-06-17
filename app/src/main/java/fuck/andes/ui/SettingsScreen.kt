package fuck.andes.ui

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import fuck.andes.FuckAndesApp
import fuck.andes.config.Prefs
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 模块配置界面。
 *
 * 开关默认开启（与历史硬编码行为一致）。切换时同步提交（RemotePreferences.commit
 * 会同步等待 binder 提交到 LSPosed 数据库，失败返回 false；本地 fallback 走普通
 * SharedPreferences.commit 同步落盘），hook 进程下次读取即生效。
 */
@Composable
internal fun SettingsScreen(context: Context) {
    val scrollBehavior = MiuixScrollBehavior()

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时回退本地 SharedPreferences，UI 仍可操作但不会同步到 hook。
    var prefs by remember { mutableStateOf(Prefs.sharedPreferencesForUi(context, FuckAndesApp.serviceInstance)) }
    DisposableEffect(Unit) {
        val listener = object : FuckAndesApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.sharedPreferencesForUi(context, service)
            }
        }
        FuckAndesApp.addServiceStateListener(listener, notifyImmediately = true)
        onDispose { FuckAndesApp.removeServiceStateListener(listener) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "FuckAndes",
                largeTitle = "FuckAndes",
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = innerPadding,
        ) {
            item(key = "section_features") {
                SmallTitle("功能")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    SwitchPref(
                        prefs = prefs,
                        title = "电源键长按接管 Gemini",
                        summary = "电源键长按不再走小布，直接唤起 Gemini",
                        key = Prefs.Keys.POWER_KEY_TAKEOVER,
                    )
                    SwitchPref(
                        prefs = prefs,
                        title = "手势条长按 → 一圈即搜",
                        summary = "拦截底部手势条长按的 OPPO OCR 识屏，改触发一圈即搜",
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                    )
                    SwitchPref(
                        prefs = prefs,
                        title = "双指识屏 → 一圈即搜",
                        summary = "拦截双指识屏，改触发一圈即搜",
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                    )
                    SwitchPref(
                        prefs = prefs,
                        title = "开机自动校正默认助理",
                        summary = "开机 / 解锁 / 切用户时自动把默认助理设为 Google",
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                    )
                    SwitchPref(
                        prefs = prefs,
                        title = "息屏后维持 Hey Google",
                        summary = "息屏后恢复 Google 软件热词检测，避免唤不醒",
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                    )
                    SwitchPref(
                        prefs = prefs,
                        title = "锁屏唤起补语音输入",
                        summary = "锁屏唤起 Gemini 浮窗后自动补发语音输入",
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                    )
                }
            }
        }
    }
}

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * prefs 从本地 fallback 切到 RemotePreferences（XposedService 到达）时，
 * 通过 [remember(prefs, key)] 重算初始值；切换写入用 [putBooleanSync] 同步提交，
 * 避免 RemotePreferences.apply() 异步 binder 失败后 UI 显示与 hook 侧不一致。
 */
@Composable
private fun SwitchPref(
    prefs: android.content.SharedPreferences,
    title: String,
    summary: String,
    key: String,
) {
    var checked by remember(prefs, key) { mutableStateOf(prefs.getBoolean(key, true)) }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            if (putBooleanSync(prefs, key, value)) {
                checked = value
            }
        },
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 普通 SharedPreferences 的 commit 同步落盘。返回是否提交成功，供调用方决定是否回滚 UI。
 */
private fun putBooleanSync(
    prefs: android.content.SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)
