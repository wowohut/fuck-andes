package fuck.andes.ui

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import fuck.andes.FuckAndesApp
import fuck.andes.config.Prefs
import fuck.andes.systemizer.GoogleAppSystemizerInstaller
import fuck.andes.systemizer.RootManager
import fuck.andes.systemizer.SystemizerInstallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Mic
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.Update
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ── 图标 tint 色 ─────────────────────────────────────────────────────────────
// 交互接管组 — 蓝色系
private val IconTintBlue = Color(0xFF3482F6)
// 助理配置组 — 绿色系
private val IconTintGreen = Color(0xFF34C759)
// 高级组 — 紫色系
private val IconTintPurple = Color(0xFFAF52DE)

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
            // ── LSPosed 未连接提示 ──────────────────────────────────────
            if (prefs == null) {
                item(key = "service_warning") {
                    Card(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        BasicComponent(
                            title = "LSPosed 服务未连接",
                        )
                    }
                }
            }

            // ── 交互接管 ────────────────────────────────────────────────
            item(key = "section_interaction") {
                SmallTitle("交互接管")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "长按电源键唤起 Gemini",
                        key = Prefs.Keys.POWER_KEY_TAKEOVER,
                        icon = MiuixIcons.Tune,
                        iconTint = IconTintBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "手势条长按触发一圈即搜",
                        key = Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
                        icon = MiuixIcons.Search,
                        iconTint = IconTintBlue,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "双指长按触发一圈即搜",
                        key = Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
                        icon = MiuixIcons.Scan,
                        iconTint = IconTintBlue,
                    )
                }
            }

            // ── 助理配置 ────────────────────────────────────────────────
            item(key = "section_assistant") {
                SmallTitle("助理配置")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "自动设置 Google 为默认助理",
                        key = Prefs.Keys.ASSISTANT_AUTO_CONFIG,
                        icon = MiuixIcons.Update,
                        iconTint = IconTintGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "息屏后维持 Hey Google 检测",
                        key = Prefs.Keys.HOTWORD_SELF_HEAL,
                        icon = MiuixIcons.Mic,
                        iconTint = IconTintGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "锁屏唤起自动语音输入",
                        key = Prefs.Keys.LOCKSCREEN_VOICE_COMMAND,
                        icon = MiuixIcons.Lock,
                        iconTint = IconTintGreen,
                    )
                    PrefDivider()
                    SwitchPref(
                        context = context,
                        prefs = prefs,
                        title = "亮屏唤起自动语音输入",
                        key = Prefs.Keys.SCREEN_ON_VOICE_COMMAND,
                        icon = MiuixIcons.Mic,
                        iconTint = IconTintGreen,
                    )
                }
            }

            // ── 高级 ────────────────────────────────────────────────────
            item(key = "section_systemizer") {
                SmallTitle("高级")
                Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        title = "将 Google App 转为系统应用",
                        startAction = {
                            TintedIcon(
                                icon = MiuixIcons.ConvertFile,
                                tint = IconTintPurple,
                            )
                        },
                        enabled = !installingSystemizer,
                        holdDownState = showSystemizerDialog,
                        onClick = {
                            if (!installingSystemizer) {
                                showSystemizerDialog = true
                            }
                        },
                    )
                    PrefDivider()
                    ArrowPreference(
                        title = "源代码",
                        startAction = {
                            TintedIcon(
                                icon = MiuixIcons.Link,
                                tint = IconTintPurple,
                            )
                        },
                        endActions = {
                            Text(
                                text = "GitHub",
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                        onClick = {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/wowohut/fuck-andes"),
                            )
                            context.startActivity(intent)
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

// ── 带色彩的图标（Miuix 风格：纯图标 + tint） ────────────────────────────────

@Composable
private fun TintedIcon(
    icon: ImageVector,
    tint: Color,
) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 16.dp).size(24.dp),
        tint = tint,
    )
}

// ── Card 内分隔线 ───────────────────────────────────────────────────────────

@Composable
private fun PrefDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(
            // 对齐 BasicComponent 内文字起始位置：
            // insideMargin(16) + 图标 padding end(16) + 图标宽度(24) + startAction 与 center 间距(8) = 64dp
            start = 64.dp,
        ),
    )
}

// ── 系统化确认对话框 ─────────────────────────────────────────────────────────

@Composable
private fun SystemizerConfirmDialog(
    show: Boolean,
    installing: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = show,
        title = "将 Google App 转为系统应用",
        onDismissRequest = onDismissRequest,
    ) {
        Text(
            text = "系统应用享有语音唤醒权限、更少的自启限制，体验接近原生。",
            modifier = Modifier.fillMaxWidth(),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "通过 Magisk / KernelSU 模块安装，重启后生效。",
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            fontSize = MiuixTheme.textStyles.footnote1.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
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

// ── 带图标的布尔开关 ─────────────────────────────────────────────────────────

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
    key: String,
    icon: ImageVector,
    iconTint: Color,
) {
    val enabled = prefs != null
    var checked by remember(prefs, key) { mutableStateOf(prefs?.getBoolean(key, true) ?: true) }
    SwitchPreference(
        title = title,
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
        startAction = {
            TintedIcon(icon = icon, tint = iconTint)
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
        SystemizerInstallResult.KernelSuMetamoduleMissing -> "KernelSU 需先启用 metamodule 支持"
        is SystemizerInstallResult.RootPermissionUnavailable -> when (rootManager) {
            RootManager.KERNEL_SU -> "请在 KernelSU 中授予 FuckAndes root 权限"
            RootManager.MAGISK -> "请在 Magisk 中授予 FuckAndes root 权限"
            RootManager.UNSUPPORTED -> "未获得 root 权限"
        }
        is SystemizerInstallResult.InstalledRebootRequired -> "安装完成，重启后生效"
        is SystemizerInstallResult.Failed -> commandOutput
            .lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.let { "$message：$it" }
            ?: message
    }
