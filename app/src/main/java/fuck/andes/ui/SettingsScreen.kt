package fuck.andes.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import fuck.andes.FuckAndesApp
import fuck.andes.config.Prefs
import fuck.andes.systemizer.GoogleAppSystemizerInstaller
import fuck.andes.systemizer.SystemizerInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 模块配置界面。
 *
 * 开关默认开启（与历史硬编码行为一致）。切换时同步提交（RemotePreferences.commit
 * 会同步等待 binder 提交到 LSPosed 数据库，失败返回 false）；XposedService 未就绪时
 * 不允许写入，避免保存到 hook 进程不可见的本地配置。
 */
@Composable
internal fun SettingsScreen(context: Context) {
    val scrollBehavior = MiuixScrollBehavior()
    val coroutineScope = rememberCoroutineScope()
    var showSystemizerDialog by remember { mutableStateOf(false) }
    var installingSystemizer by remember { mutableStateOf(false) }

    // prefs 绑定到 XposedService：service 到达时切换到 RemotePreferences（跨进程提交到
    // LSPosed 数据库）；未就绪时保持 null，UI 禁止修改。
    var prefs by remember { mutableStateOf(Prefs.remotePreferencesForUi(FuckAndesApp.serviceInstance)) }
    DisposableEffect(Unit) {
        val listener = object : FuckAndesApp.ServiceStateListener {
            override fun onServiceStateChanged(service: io.github.libxposed.service.XposedService?) {
                prefs = Prefs.remotePreferencesForUi(service)
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
                    if (prefs == null) {
                        BasicComponent(
                            title = "LSPosed 服务未连接",
                            summary = "当前只能查看默认开关状态，模块激活并连接服务后才可修改配置。",
                        )
                    }
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "电源键长按接管 Gemini",
                        summary = "电源键长按不再走小布，直接唤起 Gemini",
                        key = Prefs.Keys.POWER_KEY_TAKEOVER,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "手势条长按 → 一圈即搜",
                        summary = "拦截底部手势条长按的 OPPO OCR 识屏，改触发一圈即搜",
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "双指识屏 → 一圈即搜",
                        summary = "拦截双指识屏，改触发一圈即搜",
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "开机自动校正默认助理",
                        summary = "开机 / 解锁 / 切用户时自动把默认助理设为 Google",
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "息屏后维持 Hey Google",
                        summary = "息屏后恢复 Google 软件热词检测，避免唤不醒",
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                    )
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "锁屏唤起补语音输入",
                        summary = "锁屏唤起 Gemini 浮窗后自动补发语音输入",
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                    )
                }
            }
            item(key = "section_systemizer") {
                SmallTitle("系统化")
                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                    ArrowPreference(
                        title = "转为系统应用",
                        summary = "将 Google App 安装为系统 priv-app，获得语音唤醒依赖，减少自启等烦恼，方便使用",
                        enabled = !installingSystemizer,
                        holdDownState = showSystemizerDialog,
                        onClick = {
                            if (!installingSystemizer) {
                                showSystemizerDialog = true
                            }
                        },
                    )
                }
            }
        }

        SystemizerConfirmDialog(
            show = showSystemizerDialog,
            installing = installingSystemizer,
            onDismissRequest = {
                if (!installingSystemizer) {
                    showSystemizerDialog = false
                }
            },
            onConfirm = {
                if (installingSystemizer) return@SystemizerConfirmDialog
                showSystemizerDialog = false
                installingSystemizer = true
                coroutineScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        GoogleAppSystemizerInstaller(context.applicationContext).install()
                    }
                    installingSystemizer = false
                    Toast.makeText(
                        context.applicationContext,
                        result.toToastMessage(),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            },
        )
    }
}

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "转为系统应用",
        summary = "将通过 root 安装 Google App 系统化模块，重启后生效。",
        onDismissRequest = onDismissRequest,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = "取消",
                onClick = onDismissRequest,
                modifier = Modifier.weight(1f),
                enabled = !installing,
            )
            TextButton(
                text = "确定",
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !installing,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

/**
 * 单个布尔开关：状态随 [prefs]/[key] 变化重读，切换时同步写入。
 *
 * XposedService 到达时通过 [remember(prefs, key)] 重算初始值；切换写入用
 * [putBooleanSync] 同步提交，避免 RemotePreferences.apply() 异步 binder 失败后 UI 显示
 * 与 hook 侧不一致。
 */
@Composable
private fun SwitchPref(
    context: Context,
    prefs: SharedPreferences?,
    title: String,
    summary: String,
    key: String,
) {
    val enabled = prefs != null
    var checked by remember(prefs, key) { mutableStateOf(prefs?.getBoolean(key, true) ?: true) }
    SwitchPreference(
        title = title,
        summary = summary,
        checked = checked,
        onCheckedChange = { value ->
            // 同步提交；RemotePreferences.commit() 失败（binder 提交失败）时回滚 UI 状态，
            // 避免 UI 显示已切换而 hook 进程实际未收到。
            val targetPrefs = prefs ?: return@SwitchPreference
            if (putBooleanSync(targetPrefs, key, value)) {
                checked = value
            } else {
                Toast.makeText(context.applicationContext, "配置写入失败", Toast.LENGTH_SHORT).show()
            }
        },
        enabled = enabled,
    )
}

/**
 * 同步写入布尔值。RemotePreferences 的 [commit] 先更新本进程 map 再同步等待 binder 提交，
 * 失败（binder RemoteException）返回 false 但本进程 map 已被改写——此时 hook 进程收不到新值。
 * 返回是否提交成功，供调用方决定是否更新 UI。
 */
private fun putBooleanSync(
    prefs: SharedPreferences,
    key: String,
    value: Boolean
): Boolean =
    runCatching { prefs.edit().putBoolean(key, value).commit() }.getOrDefault(false)

private fun SystemizerInstallResult.toToastMessage(): String =
    when (this) {
        SystemizerInstallResult.AlreadySystemized -> "Google App 已是系统 priv-app"
        SystemizerInstallResult.GoogleAppMissing -> "未安装 Google App"
        SystemizerInstallResult.UnsupportedRootManager -> "未检测到 Magisk 或 KernelSU"
        SystemizerInstallResult.KernelSuOverlayMissing -> "KernelSU 需先安装 meta-overlayfs 模块"
        is SystemizerInstallResult.InstalledRebootRequired -> "安装完成，重启后生效"
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
