package fuck.andes

import android.content.Context
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object SystemUiHooks {
    private const val OCR_LONG_PRESS_HAPTIC_EFFECT_ID = 1

    @Volatile
    private var systemUiClassLoader: ClassLoader? = null

    @Volatile
    private var vibrationHelperGetInstanceMethod: Method? = null

    @Volatile
    private var vibrationHelperVibrateCustomizedMethod: Method? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        systemUiClassLoader = classLoader
        val businessClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.OCR_BUSINESS_CLASS)
        val onLongPressedMethod = businessClass?.let {
            HookSupport.findMethod(it, "onLongPressed")
        }
        if (onLongPressedMethod == null) {
            logger.warn("未找到 OplusOcrScreenBusiness.onLongPressed()")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            onLongPressedMethod,
            "OplusOcrScreenBusiness.onLongPressed"
        ) { chain ->
            // 即时生效：开关关闭则走原 OCR 逻辑。
            if (!Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@hookMethod chain.proceed()
            }
            val context = resolveContext(chain.getThisObject())
            if (context == null) {
                logger.warnThrottled("systemui_context", "SystemUI 无法取得 Context，回退原 OCR 逻辑")
                return@hookMethod chain.proceed()
            }

            if (!CircleToSearchInvoker.isAvailable(
                    context,
                    logger,
                    "SystemUI",
                    "回退原 OCR 逻辑"
                )
            ) {
                return@hookMethod chain.proceed()
            }

            if (CircleToSearchInvoker.trigger(logger, "SystemUI")) {
                performOriginalLongPressHaptic(context, logger)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun resolveContext(target: Any): Context? =
        HookSupport.invokeNoArgs(target, "getContext") as? Context
            ?: HookSupport.getFieldValue(target, "context") as? Context
            ?: HookSupport.getFieldValue(target, "mContext") as? Context
            ?: HookSupport.getFieldValue(target, "mOcrContext") as? Context

    private fun performOriginalLongPressHaptic(context: Context, logger: ModuleLogger) {
        val vibrationHelper = resolveVibrationHelper(context) ?: run {
            logger.warnThrottled(
                "systemui_cts_vibration_helper_missing",
                "SystemUI: 无法取得原生 VibrationHelper，跳过导航条长按震动"
            )
            return
        }

        if (!invokeVibrateCustomized(vibrationHelper, context)) {
            logger.warnThrottled(
                "systemui_cts_linear_haptic_failed",
                "SystemUI: 调用原生导航条长按震动失败"
            )
        }
    }

    private fun resolveVibrationHelper(context: Context): Any? {
        val classLoader = systemUiClassLoader ?: return null
        val helperClass = HookSupport.findClassOrNull(
            classLoader,
            "com.oplus.systemui.navigationbar.gesture.VibrationHelper"
        ) ?: return null
        val getInstance = vibrationHelperGetInstanceMethod
            ?: HookSupport.findMethod(helperClass, "getInstance", Context::class.java)
                ?.also { vibrationHelperGetInstanceMethod = it }
            ?: return null
        return runCatching { getInstance.invoke(null, context) }.getOrNull()
    }

    private fun invokeVibrateCustomized(vibrationHelper: Any, context: Context): Boolean {
        val method = vibrationHelperVibrateCustomizedMethod
            ?: HookSupport.findMethod(
                vibrationHelper.javaClass,
                "vibrateCustomized",
                Context::class.java,
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            )?.also { vibrationHelperVibrateCustomizedMethod = it }
            ?: return false
        return runCatching {
            method.invoke(vibrationHelper, context, OCR_LONG_PRESS_HAPTIC_EFFECT_ID, false)
            true
        }.getOrDefault(false)
    }
}
