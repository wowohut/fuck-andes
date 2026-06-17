package fuck.andes.config

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import java.util.concurrent.ConcurrentHashMap

/**
 * 模块配置中枢。
 *
 * - Hook 进程（system_server / SystemUI / Google / ColorDirect）在模块加载时调用
 *   [attachRemote]，缓存框架提供的只读 [SharedPreferences]；之后所有拦截回调用
 *   [isEnabled] 读最新值，即时生效，无需重启进程。
 * - UI 进程（模块自身）通过 [sharedPreferencesForUi] 拿到可写的 [SharedPreferences]
 *   （XposedService.getRemotePreferences），写入经 LSPosed 数据库同步到各 hook 进程。
 *   XposedService 未就绪时回退本地 SharedPreferences，UI 仍可用但不会同步到 hook。
 *
 * 基于 libxposed API 102 的 [io.github.libxposed.api.XposedInterface.getRemotePreferences]
 * 与 service 102 的 [XposedService.getRemotePreferences]，两端共用同一 group。
 */
internal object Prefs {

    /** 远程配置组名，UI 写入与 Hook 读取必须一致。 */
    const val GROUP = "fuck_andes_prefs"

    /** 本地回退文件名（XposedService 未就绪时用）。 */
    private const val LOCAL_FALLBACK = "fuck_andes_prefs_local"

    /** 所有功能开关 key。默认值统一为 true，保持与历史硬编码行为一致。 */
    object Keys {
        const val POWER_KEY_TAKEOVER = "power_key_takeover"
        const val ASSISTANT_AUTO_CONFIG = "assistant_auto_config"
        const val HOTWORD_SELF_HEAL = "hotword_self_heal"
        const val GESTURE_BAR_CIRCLE_TO_SEARCH = "gesture_bar_circle_to_search"
        const val DOUBLE_FINGER_CIRCLE_TO_SEARCH = "double_finger_circle_to_search"
        const val LOCKSCREEN_VOICE_COMMAND = "lockscreen_voice_command"

        /** 全部布尔开关及其默认值。 */
        val BOOLEAN_DEFAULTS: Map<String, Boolean> = mapOf(
            POWER_KEY_TAKEOVER to true,
            ASSISTANT_AUTO_CONFIG to true,
            HOTWORD_SELF_HEAL to true,
            GESTURE_BAR_CIRCLE_TO_SEARCH to true,
            DOUBLE_FINGER_CIRCLE_TO_SEARCH to true,
            LOCKSCREEN_VOICE_COMMAND to true
        )
    }

    /** Hook 进程缓存的只读 remote preferences，由 ModuleMain 在 onModuleLoaded 注入。 */
    @Volatile
    private var remote: SharedPreferences? = null

    /** Hook 进程调用：缓存框架提供的只读 SharedPreferences。 */
    fun attachRemote(prefs: SharedPreferences?) {
        remote = prefs
    }

    /**
     * 读取布尔开关。remote 不可用（框架未注入或调用失败）时回退默认值（全开），
     * 与历史行为一致。
     */
    fun isEnabled(key: String): Boolean {
        val default = Keys.BOOLEAN_DEFAULTS[key] ?: true
        return remote?.getBoolean(key, default) ?: default
    }

    /**
     * UI 进程获取可写的 SharedPreferences。
     *
     * 优先用 [XposedService.getRemotePreferences]（commit 同步等待 binder 提交到 LSPosed
     * 数据库，失败返回 false；详见 RemotePreferences.commit 源码）；
     * service 未就绪时回退本地 [Context.MODE_PRIVATE]，UI 仍可操作但不会同步到 hook。
     */
    fun sharedPreferencesForUi(context: Context, service: XposedService?): SharedPreferences =
        runCatching { service?.getRemotePreferences(GROUP) }.getOrNull()
            ?: context.getSharedPreferences(LOCAL_FALLBACK, Context.MODE_PRIVATE)
}
