package fuck.andes

import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

internal object AssistantManager {
    private const val BOOT_COMPLETED_PHASE = 1_000
    private const val SHOW_SOURCE_PUSH_TO_TALK = 1 shl 5
    private const val DEFAULT_SHOW_FLAGS = SHOW_SOURCE_PUSH_TO_TALK
    private const val CONFIG_VERIFY_COOLDOWN_MS = 15_000L
    private const val ROLE_OPERATION_TIMEOUT_MS = 1_500L
    private const val REFRESH_COOLDOWN_MS = 5_000L

    @Volatile
    private var lastForcedRefreshUptime = 0L

    @Volatile
    private var lastVerifiedUserId = UserHandleHidden.USER_NULL

    @Volatile
    private var lastVerifiedUptime = 0L

    @Volatile
    private var voiceInteractionManagerStub: Any? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val serviceClass = HookSupport.findClassOrNull(
            classLoader,
            ModuleConfig.VOICE_INTERACTION_MANAGER_SERVICE_CLASS
        )
        val onBootPhaseMethod = serviceClass?.let {
            HookSupport.findMethod(it, "onBootPhase", Int::class.javaPrimitiveType!!)
        }
        if (onBootPhaseMethod == null) {
            logger.warn("未找到 VoiceInteractionManagerService.onBootPhase(int)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            onBootPhaseMethod,
            "VoiceInteractionManagerService.onBootPhase"
        ) { chain ->
            val phase = chain.getArg(0) as Int
            val result = chain.proceed()
            captureVoiceInteractionManagerStub(chain.getThisObject())
            if (phase == BOOT_COMPLETED_PHASE) {
                // 即时生效：开关关闭则不自动校正默认助理。
                if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
                    logger.debug("AssistantManager: 自动校正已关闭，跳过 boot 校正")
                } else {
                    val context = HookSupport.getFieldValue(chain.getThisObject(), "mContext") as? Context
                    if (context == null) {
                        logger.warnThrottled(
                            "assistant_boot_missing_context",
                            "AssistantManager: boot completed 时无法取得 mContext"
                        )
                    } else {
                        ensureGoogleAssistantConfigured(context, logger)
                    }
                }
            }
            result
        }

        hookUserLifecycleSelfHeal(module, logger, serviceClass, "onUserUnlocking", 1)
        hookUserLifecycleSelfHeal(module, logger, serviceClass, "onUserSwitching", 2)
    }

    fun ensureGoogleAssistantConfigured(
        context: Context,
        logger: ModuleLogger,
        forceRefresh: Boolean = false
    ): Boolean = ensureGoogleAssistantConfiguredForUser(
        context = context,
        userId = resolveCurrentUserId(),
        logger = logger,
        forceRefresh = forceRefresh
    )

    fun showGoogleAssistantSession(
        context: Context,
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean = false
    ): Boolean {
        val service = resolveVoiceInteractionService(logger, source, logFailures) ?: return false

        if (isKeyguardLocked(context)) {
            val launchFromKeyguardMethod = service.javaClass.methods.firstOrNull {
                it.name == "launchVoiceAssistFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunchMethod = service.javaClass.methods.firstOrNull {
                it.name == "activeServiceSupportsLaunchFromKeyguard" && it.parameterTypes.isEmpty()
            }
            val supportsLaunch = runCatching {
                supportsLaunchMethod?.invoke(service) as? Boolean ?: false
            }.getOrDefault(false)
            if (supportsLaunch && launchFromKeyguardMethod != null) {
                return runCatching {
                    launchFromKeyguardMethod.invoke(service)
                    logger.debug("$source: 已通过 voiceinteraction 从锁屏启动 Google")
                    true
                }.getOrElse { throwable ->
                    logShowSessionFailure(
                        logger,
                        "${source}_launch_keyguard_failed",
                        "$source: launchVoiceAssistFromKeyguard 失败: ${throwable.message}",
                        logFailures
                    )
                    false
                }
            }
        }

        val showSessionMethod = service.javaClass.methods.firstOrNull {
            it.name == "showSessionForActiveService" && it.parameterTypes.size == 5
        }
        if (showSessionMethod == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing_show",
                "$source: voiceinteraction 缺少 showSessionForActiveService",
                logFailures
            )
            return false
        }

        return runCatching {
            showSessionMethod.invoke(
                service,
                Bundle(),
                DEFAULT_SHOW_FLAGS,
                null,
                null,
                null
            ) as? Boolean ?: false
        }.onFailure { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_failed",
                "$source: 调用 showSessionForActiveService 失败: ${throwable.message}",
                logFailures
            )
        }.getOrDefault(false).also { shown ->
            if (!shown) {
                logShowSessionFailure(
                    logger,
                    "${source}_voice_service_returned_false",
                    "$source: showSessionForActiveService 返回 false",
                    logFailures
                )
            }
        }
    }

    fun rebuildVoiceInteractionImplementation(
        logger: ModuleLogger,
        userId: Int = resolveCurrentUserId(),
        force: Boolean,
        logFailures: Boolean = false
    ): Boolean {
        val stub = voiceInteractionManagerStub ?: run {
            logShowSessionFailure(
                logger,
                "assistant_stub_missing",
                "AssistantManager: mServiceStub 尚未就绪，无法重建 voice interaction 实现",
                logFailures
            )
            return false
        }
        val initForUserMethod = stub.javaClass.methods.firstOrNull {
            it.name == "initForUser" && it.parameterTypes.size == 1
        }
        val switchImplementationMethod = stub.javaClass.methods.firstOrNull {
            it.name == "switchImplementationIfNeeded" && it.parameterTypes.size == 1
        }
        if (initForUserMethod == null || switchImplementationMethod == null) {
            logShowSessionFailure(
                logger,
                "assistant_stub_methods_missing",
                "AssistantManager: mServiceStub 缺少 initForUser/switchImplementationIfNeeded",
                logFailures
            )
            return false
        }

        return runCatching {
            initForUserMethod.invoke(stub, userId)
            switchImplementationMethod.invoke(stub, force)
            true
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "assistant_stub_rebuild_failed",
                "AssistantManager: 重建 voice interaction 实现失败: ${throwable.message}",
                logFailures
            )
            false
        }
    }

    fun resumeSoftwareHotwordDetection(
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean = false
    ): Boolean {
        val stub = voiceInteractionManagerStub ?: run {
            logShowSessionFailure(
                logger,
                "${source}_hotword_stub_missing",
                "$source: mServiceStub 尚未就绪，无法恢复软件热词检测",
                logFailures
            )
            return false
        }

        return runCatching {
            synchronized(stub) {
                val impl = HookSupport.getFieldValue(stub, "mImpl") ?: return@synchronized false
                val component = HookSupport.getFieldValue(impl, "mComponent") as? ComponentName
                if (component?.packageName != ModuleConfig.GOOGLE_PACKAGE) {
                    return@synchronized false
                }

                val session = findSoftwareHotwordSession(impl) ?: return@synchronized false
                val running = HookSupport.getFieldValue(
                    session,
                    "mPerformingSoftwareHotwordDetection"
                ) as? Boolean ?: false
                if (running) {
                    return@synchronized false
                }

                val callback = HookSupport.getFieldValue(session, "mSoftwareCallback")
                    ?: return@synchronized false
                val startListeningMethod = impl.javaClass.declaredMethods.firstOrNull {
                    it.name == "startListeningFromMicLocked" && it.parameterTypes.size == 2
                }?.apply { isAccessible = true } ?: return@synchronized false

                startListeningMethod.invoke(impl, null, callback)
                true
            }
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_hotword_resume_failed",
                "$source: 恢复软件热词检测失败: ${throwable.message}",
                logFailures
            )
            false
        }
    }

    private fun ensureGoogleAssistantConfiguredForUser(
        context: Context,
        userId: Int,
        logger: ModuleLogger,
        forceRefresh: Boolean = false
    ): Boolean {
        val now = SystemClock.uptimeMillis()
        if (!forceRefresh &&
            userId == lastVerifiedUserId &&
            now - lastVerifiedUptime < CONFIG_VERIFY_COOLDOWN_MS
        ) {
            return true
        }

        val roleOk = hasGoogleAssistantRole(context, userId)
        val settingsOk = hasGoogleAssistantSettings(context, userId)
        if (!forceRefresh && roleOk && settingsOk) {
            markVerified(userId, now)
            return true
        }

        if (forceRefresh && now - lastForcedRefreshUptime < REFRESH_COOLDOWN_MS) {
            return roleOk && settingsOk
        }
        if (forceRefresh) {
            lastForcedRefreshUptime = now
        }

        val roleChanged = if (forceRefresh) {
            refreshGoogleAssistantRole(context, userId, logger)
        } else {
            ensureGoogleAssistantRole(context, userId, logger)
        }
        val settingsChanged = updateGoogleAssistantSettings(context, userId, forceRefresh, logger)
        val verified = hasGoogleAssistantRole(context, userId) && hasGoogleAssistantSettings(context, userId)

        if (verified) {
            markVerified(userId, now)
            if (roleChanged || settingsChanged || forceRefresh) {
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = forceRefresh || roleChanged || settingsChanged,
                    logFailures = false
                )
                logger.debug(
                    if (forceRefresh) {
                        "AssistantManager: 已刷新 Google 默认助理绑定"
                    } else {
                        "AssistantManager: 已校正 Google 默认助理绑定"
                    }
                )
            }
        } else {
            invalidateVerificationCache()
        }

        return verified
    }

    private fun resolveVoiceInteractionService(
        logger: ModuleLogger,
        source: String,
        logFailures: Boolean
    ): Any? {
        val binder = runCatching {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String::class.java)
            getServiceMethod.invoke(null, ModuleConfig.VOICE_INTERACTION_SERVICE) as? IBinder
        }.getOrNull()
        if (binder == null) {
            logShowSessionFailure(
                logger,
                "${source}_voice_service_missing",
                "$source: 无法取得 voiceinteraction binder",
                logFailures
            )
            return null
        }

        return runCatching {
            val stubClass = Class.forName("com.android.internal.app.IVoiceInteractionManagerService\$Stub")
            val asInterfaceMethod = stubClass.getDeclaredMethod("asInterface", IBinder::class.java)
            asInterfaceMethod.invoke(null, binder)
        }.getOrElse { throwable ->
            logShowSessionFailure(
                logger,
                "${source}_voice_service_as_interface_failed",
                "$source: 解析 IVoiceInteractionManagerService 失败: ${throwable.message}",
                logFailures
            )
            null
        }
    }

    private fun ensureGoogleAssistantRole(
        context: Context,
        userId: Int,
        logger: ModuleLogger
    ): Boolean {
        if (hasGoogleAssistantRole(context, userId)) {
            return false
        }
        return mutateRoleHolders(
            context,
            userId,
            "addRoleHolderAsUser",
            arrayOf(
                ModuleConfig.ASSISTANT_ROLE,
                ModuleConfig.GOOGLE_PACKAGE,
                0,
                resolveUserHandle(userId)
            ),
            logger
        )
    }

    private fun refreshGoogleAssistantRole(
        context: Context,
        userId: Int,
        logger: ModuleLogger
    ): Boolean {
        val cleared = mutateRoleHolders(
            context,
            userId,
            "clearRoleHoldersAsUser",
            arrayOf(ModuleConfig.ASSISTANT_ROLE, 0, resolveUserHandle(userId)),
            logger
        )
        val added = mutateRoleHolders(
            context,
            userId,
            "addRoleHolderAsUser",
            arrayOf(
                ModuleConfig.ASSISTANT_ROLE,
                ModuleConfig.GOOGLE_PACKAGE,
                0,
                resolveUserHandle(userId)
            ),
            logger
        )
        return cleared || added
    }

    private fun mutateRoleHolders(
        context: Context,
        userId: Int,
        methodName: String,
        baseArgs: Array<Any>,
        logger: ModuleLogger
    ): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == methodName && it.parameterTypes.size == baseArgs.size + 2
        } ?: run {
            logger.warnThrottled(
                "assistant_role_method_$methodName",
                "AssistantManager: RoleManager 缺少 $methodName"
            )
            return false
        }

        val latch = CountDownLatch(1)
        var success = false
        val executor = Executor { runnable -> runnable.run() }
        val callback = Consumer<Boolean> { result ->
            success = result == true
            latch.countDown()
        }

        return runCatching {
            val args = arrayOfNulls<Any>(baseArgs.size + 2)
            baseArgs.copyInto(args, endIndex = baseArgs.size)
            args[baseArgs.size] = executor
            args[baseArgs.size + 1] = callback
            method.invoke(roleManager, *args)
            latch.await(ROLE_OPERATION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            success
        }.getOrElse { throwable ->
            logger.warnThrottled(
                "assistant_role_mutation_$methodName",
                "AssistantManager: $methodName 失败: ${throwable.message}"
            )
            false
        }
    }

    private fun updateGoogleAssistantSettings(
        context: Context,
        userId: Int,
        forceRefresh: Boolean,
        logger: ModuleLogger
    ): Boolean {
        val resolver = context.contentResolver
        var changed = false
        changed = updateSecureString(
            resolver,
            ModuleConfig.SECURE_ASSISTANT,
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
            userId,
            forceRefresh
        ) || changed
        changed = updateSecureString(
            resolver,
            ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT,
            userId,
            forceRefresh
        ) || changed
        if (changed) {
            logger.debug("AssistantManager: 已写入 Google 助理 secure 配置")
        }
        return changed
    }

    private fun updateSecureString(
        resolver: ContentResolver,
        key: String,
        targetValue: String,
        userId: Int,
        forceRefresh: Boolean
    ): Boolean {
        val currentValue = getSecureStringForUser(resolver, key, userId)
        if (!forceRefresh && currentValue == targetValue) {
            return false
        }
        if (forceRefresh) {
            putSecureStringForUser(resolver, key, null, userId)
        }
        return putSecureStringForUser(resolver, key, targetValue, userId)
    }

    private fun hasGoogleAssistantRole(context: Context, userId: Int): Boolean {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return false
        val method = roleManager.javaClass.methods.firstOrNull {
            it.name == "getRoleHoldersAsUser" && it.parameterTypes.size == 2
        } ?: return false
        val holders = runCatching {
            @Suppress("UNCHECKED_CAST")
            method.invoke(roleManager, ModuleConfig.ASSISTANT_ROLE, resolveUserHandle(userId)) as? List<String>
        }.getOrNull().orEmpty()
        return holders.contains(ModuleConfig.GOOGLE_PACKAGE)
    }

    private fun hasGoogleAssistantSettings(context: Context, userId: Int): Boolean {
        val resolver = context.contentResolver
        return getSecureStringForUser(resolver, ModuleConfig.SECURE_ASSISTANT, userId) ==
            ModuleConfig.GOOGLE_ASSISTANT_COMPONENT &&
            getSecureStringForUser(
                resolver,
                ModuleConfig.SECURE_VOICE_INTERACTION_SERVICE,
                userId
            ) == ModuleConfig.GOOGLE_ASSISTANT_COMPONENT
    }

    private fun getSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        userId: Int
    ): String? =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "getStringForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, userId) as? String
        }.getOrNull()

    private fun putSecureStringForUser(
        resolver: ContentResolver,
        key: String,
        value: String?,
        userId: Int
    ): Boolean =
        runCatching {
            val method = Settings.Secure::class.java.getDeclaredMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, key, value, userId) as? Boolean ?: false
        }.getOrDefault(false)

    private fun resolveUserHandle(userId: Int): Any =
        runCatching {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val ofMethod = userHandleClass.methods.firstOrNull {
                it.name == "of" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
            }
            if (ofMethod != null) {
                return@runCatching ofMethod.invoke(null, userId) ?: error("UserHandle.of 返回 null")
            }
            val constructor = userHandleClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            constructor.isAccessible = true
            constructor.newInstance(userId) ?: error("UserHandle(int) 返回 null")
        }.getOrElse {
            error("无法构造 user=$userId 的 UserHandle")
        }

    private fun resolveCurrentUserId(): Int =
        runCatching {
            val activityManagerClass = Class.forName("android.app.ActivityManager")
            val method = activityManagerClass.getDeclaredMethod("getCurrentUser")
            method.invoke(null) as Int
        }.getOrDefault(0)

    private fun hookUserLifecycleSelfHeal(
        module: XposedModule,
        logger: ModuleLogger,
        serviceClass: Class<*>?,
        methodName: String,
        parameterCount: Int
    ) {
        val method = serviceClass?.methods?.firstOrNull {
            it.name == methodName && it.parameterTypes.size == parameterCount
        }
        if (method == null) {
            logger.debug("AssistantManager: 未找到 $methodName($parameterCount)")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            method,
            "VoiceInteractionManagerService.$methodName"
        ) { chain ->
            val result = chain.proceed()
            captureVoiceInteractionManagerStub(chain.getThisObject())
            // 即时生效：开关关闭则不自动校正默认助理。
            if (!Prefs.isEnabled(Prefs.Keys.ASSISTANT_AUTO_CONFIG)) {
                return@hookMethod result
            }
            val context = HookSupport.getFieldValue(chain.getThisObject(), "mContext") as? Context
            if (context != null) {
                val userId = when (methodName) {
                    "onUserSwitching" -> resolveTargetUserId(chain.getArg(1))
                    else -> resolveTargetUserId(chain.getArg(0))
                } ?: resolveCurrentUserId()
                ensureGoogleAssistantConfiguredForUser(
                    context = context,
                    userId = userId,
                    logger = logger,
                    forceRefresh = false
                )
                rebuildVoiceInteractionImplementation(
                    logger = logger,
                    userId = userId,
                    force = false,
                    logFailures = false
                )
            }
            result
        }
    }

    private fun resolveTargetUserId(targetUser: Any?): Int? =
        targetUser?.let {
            runCatching {
                val method = it.javaClass.methods.firstOrNull { candidate ->
                    candidate.name == "getUserIdentifier" && candidate.parameterTypes.isEmpty()
                } ?: return@runCatching null
                method.invoke(it) as? Int
            }.getOrNull()
        }

    private fun captureVoiceInteractionManagerStub(serviceInstance: Any) {
        val stub = HookSupport.getFieldValue(serviceInstance, "mServiceStub") ?: return
        voiceInteractionManagerStub = stub
    }

    private fun findSoftwareHotwordSession(impl: Any): Any? {
        val connection = HookSupport.getFieldValue(impl, "mHotwordDetectionConnection") ?: return null
        val detectorSessions = HookSupport.getFieldValue(connection, "mDetectorSessions") ?: return null
        val sizeMethod = HookSupport.findMethod(detectorSessions.javaClass, "size") ?: return null
        val valueAtMethod = HookSupport.findMethod(
            detectorSessions.javaClass,
            "valueAt",
            Int::class.javaPrimitiveType!!
        ) ?: return null
        val size = sizeMethod.invoke(detectorSessions) as? Int ?: return null
        repeat(size) { index ->
            val session = valueAtMethod.invoke(detectorSessions, index) ?: return@repeat
            if (session.javaClass.name == "com.android.server.voiceinteraction.SoftwareTrustedHotwordDetectorSession") {
                return session
            }
        }
        return null
    }

    private fun markVerified(userId: Int, now: Long) {
        lastVerifiedUserId = userId
        lastVerifiedUptime = now
    }

    private fun invalidateVerificationCache() {
        lastVerifiedUserId = UserHandleHidden.USER_NULL
        lastVerifiedUptime = 0L
    }

    private fun logShowSessionFailure(
        logger: ModuleLogger,
        key: String,
        message: String,
        logFailures: Boolean
    ) {
        if (!logFailures) return
        logger.warnThrottled(key, message)
    }

    private fun isKeyguardLocked(context: Context): Boolean =
        runCatching {
            val keyguardManager = context.getSystemService(KeyguardManager::class.java)
            keyguardManager?.isKeyguardLocked == true
        }.getOrDefault(false)

    private object UserHandleHidden {
        const val USER_NULL = -10_000
    }
}
