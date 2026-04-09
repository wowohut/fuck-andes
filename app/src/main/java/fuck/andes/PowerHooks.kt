package fuck.andes

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

internal object PowerHooks {
    private const val SESSION_RETRY_COUNT = 3
    private const val SESSION_RETRY_SLEEP_MS = 80L
    private const val POST_REFRESH_SESSION_RETRY_COUNT = 4
    private const val POST_REFRESH_SESSION_RETRY_SLEEP_MS = 120L
    private const val RECOVERY_RETRY_COUNT = 3
    private const val RECOVERY_RETRY_INITIAL_DELAY_MS = 1_200L
    private const val RECOVERY_RETRY_STEP_DELAY_MS = 1_200L
    private const val RECOVERY_SUCCESS_SUPPRESS_WINDOW_MS = 5_000L
    private const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    private const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"
    private const val RECOVERY_SOURCE_MARKER = "_recovery"

    @Volatile
    private var lastInterceptUptime = 0L

    @Volatile
    private var lastSuccessfulLaunchUptime = 0L

    private val recoveryRetryGeneration = AtomicInteger(0)

    private enum class LaunchResult {
        LAUNCHED,
        RECOVERY_PENDING,
        NOT_HANDLED
    }

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        // 当前机型实测证明 OplusSpeechHandler 是必要路径。
        hookOplusSpeechHandler(module, logger, classLoader)
    }

    private fun hookOplusSpeechHandler(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val handlerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OP_LUS_SPEECH_HANDLER_CLASS)
        val handleMessageMethod = handlerClass?.let {
            HookSupport.findMethod(it, "handleMessage", Message::class.java)
        }
        if (handleMessageMethod == null) {
            logger.warn("未找到 OplusSpeechHandler.handleMessage(Message)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            handleMessageMethod,
            "PhoneWindowManagerExtImpl\$OplusSpeechHandler.handleMessage"
        ) { chain ->
            val message = chain.getArg(0) as? Message
            if (message?.what != ModuleConfig.OP_LUS_ASSIST_MESSAGE_WHAT) {
                return@hookMethod chain.proceed()
            }

            val handler = chain.getThisObject() as? Handler
            val pwm = resolvePhoneWindowManager(chain.getThisObject())
            if (pwm == null) {
                logger.warnThrottled(
                    "oplus_speech_missing_pwm",
                    "OplusSpeechHandler 未能解析 PhoneWindowManager，回退原逻辑"
                )
                return@hookMethod chain.proceed()
            }

            when (tryLaunchGoogleAssist(
                logger = logger,
                phoneWindowManager = pwm,
                source = "OplusSpeechHandler"
            )) {
                LaunchResult.LAUNCHED -> null
                LaunchResult.RECOVERY_PENDING -> {
                    scheduleRecoveryRetries(
                        handler = handler,
                        logger = logger,
                        phoneWindowManager = pwm,
                        source = "OplusSpeechHandler"
                    )
                    null
                }
                LaunchResult.NOT_HANDLED -> chain.proceed()
            }
        }
    }

    private fun tryLaunchGoogleAssist(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        allowActivityFallback: Boolean = true
    ): LaunchResult {
        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context
        if (context == null) {
            logger.warnThrottled("${source}_missing_context", "$source 缺少 mContext，回退原逻辑")
            return LaunchResult.NOT_HANDLED
        }

        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("${source}_google_missing", "$source: Google App 未安装，回退原逻辑")
            return LaunchResult.NOT_HANDLED
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastInterceptUptime <= ModuleConfig.INTERCEPT_DEDUP_WINDOW_MS) {
            logger.debug("$source: 命中去重窗口，直接吞掉重复触发")
            return LaunchResult.LAUNCHED
        }

        AssistantManager.ensureGoogleAssistantConfigured(context, logger)

        if (tryShowGoogleAssistantSession(
                context = context,
                logger = logger,
                source = source,
                attempts = 1,
                sleepMs = 0L
            )
        ) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug("$source: 已通过 voiceinteraction 启动 Google")
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.rebuildVoiceInteractionImplementation(
                logger = logger,
                force = true,
                logFailures = false
            ) &&
            tryShowGoogleAssistantSession(
                context = context,
                logger = logger,
                source = "${source}_rebuild",
                attempts = SESSION_RETRY_COUNT,
                sleepMs = SESSION_RETRY_SLEEP_MS
            )
        ) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug("$source: 重建 voiceinteraction 实现后已启动 Google")
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.ensureGoogleAssistantConfigured(context, logger, forceRefresh = true) &&
            AssistantManager.rebuildVoiceInteractionImplementation(
                logger = logger,
                force = true,
                logFailures = false
            ) &&
            tryShowGoogleAssistantSession(
                context = context,
                logger = logger,
                source = "${source}_retry",
                attempts = POST_REFRESH_SESSION_RETRY_COUNT,
                sleepMs = POST_REFRESH_SESSION_RETRY_SLEEP_MS
            )
        ) {
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug("$source: 刷新默认助理后已通过 voiceinteraction 启动 Google")
            return LaunchResult.LAUNCHED
        }

        if (AssistantManager.ensureGoogleAssistantConfigured(context, logger)) {
            lastInterceptUptime = now
            logger.warnThrottled(
                "${source}_assistant_recovery_pending",
                "$source: 默认助理刚恢复，等待 voiceinteraction 完成重建，本次不再回退到 Google App"
            )
            return LaunchResult.RECOVERY_PENDING
        }

        if (!allowActivityFallback) {
            return LaunchResult.NOT_HANDLED
        }

        if (startGoogleAssistActivity(context, logger, phoneWindowManager, source, now, Intent.ACTION_ASSIST)) {
            return LaunchResult.LAUNCHED
        }

        return if (startGoogleAssistActivity(
                context,
                logger,
                phoneWindowManager,
                source,
                now,
                Intent.ACTION_VOICE_COMMAND
            )
        ) {
            LaunchResult.LAUNCHED
        } else {
            LaunchResult.NOT_HANDLED
        }
    }

    private fun startGoogleAssistActivity(
        context: Context,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long,
        action: String
    ): Boolean {
        val intent = Intent(action).apply {
            setPackage(ModuleConfig.GOOGLE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled(
                "${source}_${action}_missing",
                "$source: Google 未暴露 $action，回退原逻辑"
            )
            return false
        }

        return runCatching {
            context.startActivity(intent)
            finalizeSuccessfulLaunch(logger, phoneWindowManager, source, now)
            logger.debug("$source: 已通过 $action 启动 Google")
            true
        }.getOrElse { throwable ->
            logger.warnThrottled(
                "${source}_${action}_failed",
                "$source: $action 启动失败，回退原逻辑: ${throwable.message}"
            )
            false
        }
    }

    private fun finalizeSuccessfulLaunch(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String,
        now: Long
    ) {
        markLaunchSuccess(now)
        maybePerformAssistantHapticFeedback(logger, phoneWindowManager, source)
    }

    private fun maybePerformAssistantHapticFeedback(
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (source.contains(RECOVERY_SOURCE_MARKER)) {
            logger.debug("$source: 延迟自愈重试成功，跳过补发长按助理震感")
            return
        }

        if (invokeOplusAssistantHapticFeedback(phoneWindowManager)) {
            logger.debug("$source: 已补发 Oplus 原生助理震感")
            return
        }

        logger.warnThrottled(
            "${source}_assistant_haptic_missing",
            "$source: 未找到 Oplus 原生长按助理震感入口"
        )
    }

    private fun invokeOplusAssistantHapticFeedback(phoneWindowManager: Any): Boolean {
        val wrapper = HookSupport.invokeNoArgs(phoneWindowManager, "getWrapper") ?: return false
        val wrapperMethod = HookSupport.findMethod(
            wrapper.javaClass,
            "performHapticFeedback",
            Int::class.javaPrimitiveType!!,
            String::class.java
        ) ?: return false
        return runCatching {
            wrapperMethod.invoke(wrapper, OEM_ASSISTANT_HAPTIC_EFFECT_ID, OEM_ASSISTANT_HAPTIC_REASON)
            true
        }.getOrDefault(false)
    }

    private fun tryShowGoogleAssistantSession(
        context: Context,
        logger: ModuleLogger,
        source: String,
        attempts: Int,
        sleepMs: Long
    ): Boolean {
        repeat(attempts) { index ->
            if (AssistantManager.showGoogleAssistantSession(
                    context = context,
                    logger = logger,
                    source = if (index == 0) source else "${source}_attempt${index + 1}",
                    logFailures = false
                )
            ) {
                return true
            }
            if (sleepMs > 0L && index != attempts - 1) {
                SystemClock.sleep(sleepMs)
            }
        }
        return false
    }

    private fun scheduleRecoveryRetries(
        handler: Handler?,
        logger: ModuleLogger,
        phoneWindowManager: Any,
        source: String
    ) {
        if (handler == null) {
            logger.warnThrottled(
                "${source}_recovery_missing_handler",
                "$source: 无法取得 OplusSpeechHandler 实例，跳过延迟自愈重试"
            )
            return
        }

        val generation = recoveryRetryGeneration.incrementAndGet()
        repeat(RECOVERY_RETRY_COUNT) { index ->
            val attempt = index + 1
            val delayMs = RECOVERY_RETRY_INITIAL_DELAY_MS + index * RECOVERY_RETRY_STEP_DELAY_MS
            handler.postDelayed({
                if (recoveryRetryGeneration.get() != generation) {
                    return@postDelayed
                }
                if (SystemClock.uptimeMillis() - lastSuccessfulLaunchUptime <= RECOVERY_SUCCESS_SUPPRESS_WINDOW_MS) {
                    return@postDelayed
                }

                when (tryLaunchGoogleAssist(
                    logger = logger,
                    phoneWindowManager = phoneWindowManager,
                    source = "${source}_recovery$attempt",
                    allowActivityFallback = false
                )) {
                    LaunchResult.LAUNCHED -> recoveryRetryGeneration.incrementAndGet()
                    LaunchResult.RECOVERY_PENDING -> Unit
                    LaunchResult.NOT_HANDLED -> Unit
                }
            }, delayMs)
        }
    }

    private fun markLaunchSuccess(now: Long) {
        lastInterceptUptime = now
        lastSuccessfulLaunchUptime = now
        recoveryRetryGeneration.incrementAndGet()
    }

    private fun resolvePhoneWindowManager(handlerInstance: Any): Any? {
        val owner = HookSupport.getFieldValue(handlerInstance, "this$0") ?: return null
        HookSupport.findField(owner.javaClass, "mPhoneWindowManager")?.let { field ->
            return runCatching { field.get(owner) }.getOrNull()
        }

        var current: Class<*>? = owner.javaClass
        while (current != null) {
            current.declaredFields.forEach { field ->
                if (field.type.name == ModuleConfig.PHONE_WINDOW_MANAGER_CLASS) {
                    field.isAccessible = true
                    return runCatching { field.get(owner) }.getOrNull()
                }
            }
            current = current.superclass
        }
        return null
    }
}
