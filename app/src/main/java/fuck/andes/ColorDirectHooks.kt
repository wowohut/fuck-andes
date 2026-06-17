package fuck.andes

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.os.SystemClock
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import org.json.JSONObject

internal object ColorDirectHooks {
    private const val SOURCE = "ColorDirectActivity"
    private const val DIRECT_EXT_FINGER_TRIGGER = "fingerTrigger"
    private const val DIRECT_EXT_TOUCH_INFO = "touchInfo"
    private const val DIRECT_EXT_FINGER_COUNT = "fingerCount"

    @Volatile
    private var lastHandledUptime = 0L

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        hookCollectInfoActivity(module, logger, classLoader)
    }

    private fun hookCollectInfoActivity(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val activityClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_COLLECT_ACTIVITY_CLASS
        )
        if (activityClass == null) {
            logger.warn("未找到 CollectInfoActivity，无法接管双指识屏 Activity 入口")
            return
        }
        val startInfoClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.COLOR_DIRECT_START_INFO_CLASS
        )

        val method = HookSupport.findMethod(activityClass, "M", Intent::class.java)
        if (method == null) {
            logger.warn("未找到 CollectInfoActivity.M(Intent)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            method,
            "CollectInfoActivity.M"
        ) { chain ->
            // 即时生效：开关关闭则走原双指识屏逻辑。
            if (!Prefs.isEnabled(Prefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                return@hookMethod chain.proceed()
            }
            val activity = chain.getThisObject() as? Activity
            val intent = chain.getArg(0) as? Intent
            if (activity == null || !isDoubleFingerCollectIntent(intent, startInfoClass)) {
                return@hookMethod chain.proceed()
            }

            if (tryStartCircleToSearch(activity, logger)) {
                finishColorDirectActivity(activity)
                null
            } else {
                chain.proceed()
            }
        }

        logger.debug("$SOURCE: 双指识屏 Hook 已安装")
    }

    private fun tryStartCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastHandledUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug("$SOURCE: 命中去重窗口，吞掉重复双指识屏")
            return true
        }

        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "回退小布双指识屏")) {
            return false
        }

        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE 双指识屏")) {
            return false
        }

        lastHandledUptime = now
        logger.debug("$SOURCE: 命中双指识屏，已转发 Circle to Search")
        return true
    }

    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
    }

    private fun isDoubleFingerCollectIntent(intent: Intent?, startInfoClass: Class<*>?): Boolean {
        val directExt = resolveDirectExt(intent, startInfoClass) ?: return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(DIRECT_EXT_FINGER_TRIGGER, false) &&
                json.optJSONObject(DIRECT_EXT_TOUCH_INFO)
                    ?.optInt(DIRECT_EXT_FINGER_COUNT, 0) == ModuleConfig.COLOR_DIRECT_DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(ModuleConfig.COLOR_DIRECT_EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typedStartInfoClass = startInfoClass as Class<Parcelable>
            intent.getParcelableExtra(
                ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO,
                typedStartInfoClass
            )?.let { return it }
        }

        return runCatching {
            intent.extras?.get(ModuleConfig.COLOR_DIRECT_EXTRA_START_INFO)
        }.getOrNull()
    }
}
