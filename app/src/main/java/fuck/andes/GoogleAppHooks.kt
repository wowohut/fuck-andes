package fuck.andes

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

internal object GoogleAppHooks {
    private const val FLOATY_ACTIVITY_CLASS =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"
    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val VOICE_COMMAND_COOLDOWN_MS = 6_000L

    @Volatile
    private var lastVoiceCommandUptime = 0L

    private val voiceCommandCooldownLock = Any()

    @Suppress("UNUSED_PARAMETER")
    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        // 机型伪装：在 Google 进程内伪装为 Samsung S24 Ultra，以放开一圈即搜能力。
        // Build 字段是启动时一次性写入的副作用，作为一圈即搜的底层依赖始终执行。
        setBuildField(logger, Build::class.java, "MANUFACTURER", ModuleConfig.SPOOF_MANUFACTURER)
        setBuildField(logger, Build::class.java, "BRAND", ModuleConfig.SPOOF_BRAND)
        setBuildField(logger, Build::class.java, "MODEL", ModuleConfig.SPOOF_MODEL)
        setBuildField(logger, Build::class.java, "PRODUCT", ModuleConfig.SPOOF_PRODUCT)
        setBuildField(logger, Build::class.java, "DEVICE", ModuleConfig.SPOOF_DEVICE)

        // 锁屏/亮屏补语音输入：开关在拦截回调里即时判断。
        hookFloatyVoiceCommand(module, logger, classLoader)
    }

    private fun hookFloatyVoiceCommand(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val floatyOnResumeMethod = HookSupport.findClassOrNull(classLoader, FLOATY_ACTIVITY_CLASS)
            ?.let { clazz ->
                runCatching {
                    clazz.getDeclaredMethod("onResume").apply { isAccessible = true }
                }.getOrNull()
            }
        if (floatyOnResumeMethod != null) {
            HookSupport.hookMethod(
                module,
                logger,
                floatyOnResumeMethod,
                "FloatyActivity.onResume(Google voice command)"
            ) { chain ->
                val result = chain.proceed()
                val activity = chain.getThisObject() as? Activity
                if (activity != null) {
                    scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
                }
                result
            }
            return
        }

        val onResumeMethod = HookSupport.findMethod(Activity::class.java, "onResume")
        if (onResumeMethod == null) {
            logger.warn("GSA: 未找到 Activity.onResume()，跳过 Gemini 锁屏/亮屏语音补偿")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            onResumeMethod,
            "Activity.onResume(Google Floaty voice command)"
        ) { chain ->
            val result = chain.proceed()
            val activity = chain.getThisObject() as? Activity
            if (activity?.javaClass?.name == FLOATY_ACTIVITY_CLASS) {
                scheduleVoiceCommand(activity, logger, fromKeyguard = activity.isKeyguardLocked())
            }
            result
        }
    }

    private fun scheduleVoiceCommand(activity: Activity, logger: ModuleLogger, fromKeyguard: Boolean) {
        // 锁屏走 LOCKSCREEN_VOICE_COMMAND，亮屏走 SCREEN_ON_VOICE_COMMAND；开关关闭则不补发。
        val prefKey = if (fromKeyguard) {
            Prefs.Keys.LOCKSCREEN_VOICE_COMMAND
        } else {
            Prefs.Keys.SCREEN_ON_VOICE_COMMAND
        }
        if (!Prefs.isEnabled(prefKey)) return
        // 冷却只在通过延迟复查并准备补发时消耗：若排队后因状态变化放弃，不占用冷却窗口，
        // 避免吞掉紧随其来的另一路（锁屏↔亮屏）补偿。
        if (isVoiceCommandCoolingDown()) {
            return
        }

        val scenario = if (fromKeyguard) "锁屏" else "亮屏"
        Handler(Looper.getMainLooper()).postDelayed({
            // 即时关闭：开关在延迟任务排队期间可能已被用户关闭。
            if (!Prefs.isEnabled(prefKey)) return@postDelayed
            if (activity.isFinishing || activity.isDestroyed) {
                return@postDelayed
            }
            // 延迟期间锁屏状态发生变化则放弃：锁屏分支复查应仍锁屏，亮屏分支复查应仍解锁。
            if (activity.isKeyguardLocked() != fromKeyguard) {
                return@postDelayed
            }
            // 补发前原子消耗冷却：延迟窗口内另一路可能已进入补发路径。
            if (!tryConsumeVoiceCommandCooldown()) {
                return@postDelayed
            }
            runCatching {
                activity.startActivity(
                    Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        setPackage(ModuleConfig.GOOGLE_PACKAGE)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
                logger.debug("GSA: 已为${scenario} Gemini 浮窗补发 ACTION_VOICE_COMMAND")
            }.onFailure { throwable ->
                logger.warnThrottled(
                    "gsa_floaty_voice_command_failed",
                    "GSA: ${scenario} Gemini 浮窗补发 ACTION_VOICE_COMMAND 失败: ${throwable.message}"
                )
            }
        }, VOICE_COMMAND_DELAY_MS)
    }

    private fun isVoiceCommandCoolingDown(now: Long = SystemClock.uptimeMillis()): Boolean {
        val last = lastVoiceCommandUptime
        return last != 0L && now - last < VOICE_COMMAND_COOLDOWN_MS
    }

    private fun tryConsumeVoiceCommandCooldown(): Boolean =
        synchronized(voiceCommandCooldownLock) {
            val now = SystemClock.uptimeMillis()
            if (isVoiceCommandCoolingDown(now)) {
                false
            } else {
                lastVoiceCommandUptime = now
                true
            }
        }

    private fun Activity.isKeyguardLocked(): Boolean =
        runCatching {
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)

    private fun setBuildField(
        logger: ModuleLogger,
        clazz: Class<*>,
        fieldName: String,
        value: String
    ) {
        val field = runCatching {
            clazz.getDeclaredField(fieldName).apply { isAccessible = true }
        }.getOrElse { throwable ->
            logger.error("GSA: 找不到 Build.$fieldName", throwable)
            return
        }

        runCatching {
            field.set(null, value)
        }.recoverCatching {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafe = unsafeClass.getDeclaredField("theUnsafe").apply {
                isAccessible = true
            }.get(null)
            val base = unsafeClass.getDeclaredMethod("staticFieldBase", Field::class.java)
                .invoke(theUnsafe, field)
            val offset = unsafeClass.getDeclaredMethod("staticFieldOffset", Field::class.java)
                .invoke(theUnsafe, field) as Long
            unsafeClass.getDeclaredMethod(
                "putObjectVolatile",
                Any::class.java,
                Long::class.javaPrimitiveType!!,
                Any::class.java
            ).invoke(theUnsafe, base, offset, value)
        }.onFailure { throwable ->
            logger.error("GSA: 修改 Build.$fieldName 失败", throwable)
        }
    }
}
