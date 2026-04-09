package fuck.andes

import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

internal object SystemUiHooks {
    private const val OCR_LONG_PRESS_HAPTIC_EFFECT_ID = 1

    @Volatile
    private var getServiceMethod: Method? = null

    @Volatile
    private var asInterfaceMethod: Method? = null

    @Volatile
    private var startContextualSearchMethod: Method? = null

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
            val context = resolveContext(chain.getThisObject())
            if (context == null) {
                logger.warnThrottled("systemui_context", "SystemUI 无法取得 Context，回退原 OCR 逻辑")
                return@hookMethod chain.proceed()
            }

            if (!canTriggerCircleToSearch(context, logger)) {
                return@hookMethod chain.proceed()
            }

            if (triggerCircleToSearch(logger)) {
                performOriginalLongPressHaptic(context, logger)
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun canTriggerCircleToSearch(context: Context, logger: ModuleLogger): Boolean {
        if (!HookSupport.isPackageInstalled(context, ModuleConfig.GOOGLE_PACKAGE)) {
            logger.warnThrottled("cts_google_missing", "Circle to Search: Google App 未安装，回退原 OCR 逻辑")
            return false
        }

        val intent = Intent(ModuleConfig.CONTEXTUAL_SEARCH_ACTION).setPackage(ModuleConfig.GOOGLE_PACKAGE)
        if (!HookSupport.resolvesActivity(context, intent)) {
            logger.warnThrottled(
                "cts_entry_missing",
                "Circle to Search: Google App 未暴露 Contextual Search 入口，回退原 OCR 逻辑"
            )
            return false
        }

        val binder = getContextualSearchBinder() ?: run {
            logger.warnThrottled(
                "cts_service_missing",
                "Circle to Search: contextual_search service 不可用，回退原 OCR 逻辑"
            )
            return false
        }

        return binder.isBinderAlive
    }

    private fun triggerCircleToSearch(logger: ModuleLogger): Boolean {
        val binder = getContextualSearchBinder() ?: return false
        return runCatching {
            // 直接调用系统 binder，避免再走 OEM OCR/识屏分发链。
            val asInterface = resolveAsInterfaceMethod() ?: return@runCatching false
            val startContextualSearch = resolveStartContextualSearchMethod() ?: return@runCatching false
            val service = asInterface.invoke(null, binder) ?: return@runCatching false
            startContextualSearch.invoke(service, ModuleConfig.CIRCLE_TO_SEARCH_ENTRYPOINT)
            logger.debug("SystemUI: 已触发 Circle to Search")
            true
        }.getOrElse { throwable ->
            logger.error("SystemUI: 触发 Circle to Search 失败，回退原 OCR 逻辑", throwable)
            false
        }
    }

    private fun getContextualSearchBinder(): IBinder? =
        runCatching {
            resolveGetServiceMethod()?.invoke(null, ModuleConfig.CONTEXTUAL_SEARCH_SERVICE) as? IBinder
        }.getOrNull()

    private fun resolveContext(target: Any): Context? =
        HookSupport.invokeNoArgs(target, "getContext") as? Context
            ?: HookSupport.getFieldValue(target, "context") as? Context
            ?: HookSupport.getFieldValue(target, "mContext") as? Context
            ?: HookSupport.getFieldValue(target, "mOcrContext") as? Context

    private fun resolveGetServiceMethod(): Method? {
        getServiceMethod?.let { return it }
        return runCatching {
            Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { getServiceMethod = it }
    }

    private fun resolveAsInterfaceMethod(): Method? {
        asInterfaceMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager\$Stub")
                .getDeclaredMethod("asInterface", IBinder::class.java)
                .apply { isAccessible = true }
        }.getOrNull()?.also { asInterfaceMethod = it }
    }

    private fun resolveStartContextualSearchMethod(): Method? {
        startContextualSearchMethod?.let { return it }
        return runCatching {
            Class.forName("android.app.contextualsearch.IContextualSearchManager")
                .getDeclaredMethod("startContextualSearch", Int::class.javaPrimitiveType!!)
                .apply { isAccessible = true }
        }.getOrNull()?.also { startContextualSearchMethod = it }
    }

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
