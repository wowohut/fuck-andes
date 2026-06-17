package fuck.andes

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

internal object HotwordSelfHealHooks {
    private const val RESUME_RETRY_COUNT = 3
    private const val RESUME_INITIAL_DELAY_MS = 1_200L
    private const val RESUME_STEP_DELAY_MS = 1_400L
    private const val SCREEN_OFF_COOLDOWN_MS = 8_000L

    @Volatile
    private var lastScreenOffScheduleUptime = 0L

    private val resumeGeneration = AtomicInteger(0)
    private val pendingLock = Any()
    private var pendingResumeHandler: Handler? = null
    private var pendingResumeRunnable: Runnable? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val phoneWindowManagerClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.PHONE_WINDOW_MANAGER_CLASS
        )
        val screenTurnedOffMethod = phoneWindowManagerClass?.let {
            HookSupport.findMethod(
                it,
                "screenTurnedOff",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )
        }
        if (screenTurnedOffMethod == null) {
            logger.warn("未找到 PhoneWindowManager.screenTurnedOff(int, boolean)")
            return
        }
        val screenTurnedOnMethod = phoneWindowManagerClass.let {
            HookSupport.findMethod(it, "screenTurnedOn", Int::class.javaPrimitiveType!!)
        }

        HookSupport.hookMethod(
            module,
            logger,
            screenTurnedOffMethod,
            "PhoneWindowManager.screenTurnedOff"
        ) { chain ->
            val displayId = chain.getArg(0) as? Int ?: -1
            val result = chain.proceed()
            // 即时生效：开关关闭则不恢复热词检测。
            if (displayId == 0 && Prefs.isEnabled(Prefs.Keys.HOTWORD_SELF_HEAL)) {
                scheduleHotwordResume(chain.getThisObject(), logger)
            }
            result
        }

        if (screenTurnedOnMethod != null) {
            HookSupport.hookMethod(
                module,
                logger,
                screenTurnedOnMethod,
                "PhoneWindowManager.screenTurnedOn"
            ) { chain ->
                val displayId = chain.getArg(0) as? Int ?: -1
                val result = chain.proceed()
                if (displayId == 0) {
                    cancelPendingResume()
                }
                result
            }
        } else {
            logger.warn("未找到 PhoneWindowManager.screenTurnedOn(int)")
        }
    }

    private fun scheduleHotwordResume(phoneWindowManager: Any, logger: ModuleLogger) {
        val now = SystemClock.uptimeMillis()
        if (now - lastScreenOffScheduleUptime < SCREEN_OFF_COOLDOWN_MS) {
            return
        }

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
            ?: return
        if (!isDeviceNonInteractive(context)) {
            return
        }
        lastScreenOffScheduleUptime = now
        val handler = HookSupport.getFieldValue(phoneWindowManager, "mHandler") as? Handler
            ?: Handler(Looper.getMainLooper())
        val generation = resumeGeneration.incrementAndGet()
        var attempt = 1

        lateinit var retryRunnable: Runnable
        retryRunnable = Runnable {
            if (resumeGeneration.get() != generation) {
                return@Runnable
            }
            // 即时关闭：开关在延迟任务排队期间可能已被用户关闭。
            if (!Prefs.isEnabled(Prefs.Keys.HOTWORD_SELF_HEAL)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }
            if (!isDeviceNonInteractive(context)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }

            val resumed = AssistantManager.resumeSoftwareHotwordDetection(
                logger = logger,
                source = "ScreenOffHotwordSelfHeal$attempt",
                logFailures = attempt == RESUME_RETRY_COUNT
            )
            if (resumed) {
                cancelPendingResume(resetCooldown = false)
                logger.debug("ScreenOffHotwordSelfHeal: 已恢复 Google 软件热词检测")
                return@Runnable
            }

            if (attempt >= RESUME_RETRY_COUNT) {
                clearPendingResume(resetCooldown = false)
                return@Runnable
            }

            attempt += 1
            handler.postDelayed(retryRunnable, RESUME_STEP_DELAY_MS)
        }

        replacePendingResume(handler, retryRunnable)
        handler.postDelayed(retryRunnable, RESUME_INITIAL_DELAY_MS)
    }

    private fun cancelPendingResume() {
        cancelPendingResume(resetCooldown = true)
    }

    private fun cancelPendingResume(resetCooldown: Boolean) {
        resumeGeneration.incrementAndGet()
        clearPendingResume(resetCooldown)
    }

    private fun replacePendingResume(handler: Handler, runnable: Runnable) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = handler
            pendingResumeRunnable = runnable
        }
    }

    private fun clearPendingResume(resetCooldown: Boolean) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = null
            pendingResumeRunnable = null
        }
        if (resetCooldown) {
            lastScreenOffScheduleUptime = 0L
        }
    }

    private fun isDeviceNonInteractive(context: Context): Boolean =
        runCatching {
            context.getSystemService(PowerManager::class.java)?.isInteractive == false
        }.getOrDefault(false)
}
