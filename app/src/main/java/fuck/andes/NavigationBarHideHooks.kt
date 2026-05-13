package fuck.andes

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import kotlin.math.roundToInt

internal object NavigationBarHideHooks {
    private const val GESTURE_HANDLE_HEIGHT_DP = 16f
    private const val SETTINGS_CACHE_WINDOW_MS = 500L
    private const val KEY_NAV_STATE = "hide_navigationbar_enable"
    private const val KEY_SIDE_GESTURE_HIDE_BAR = "gesture_side_hide_bar_prevention_enable"
    private const val KEY_UP_GESTURE_HIDE_BAR = "hide_gesture_bar_enable"
    private const val NAV_STATE_SWIPE_UP_GESTURE = 2
    private const val NAV_STATE_SWIPE_SIDE_GESTURE = 3

    private val DIMENSION_OVERRIDES: Map<String, DimensionOverride> = mapOf(
        "android:dimen/navigation_bar_frame_height" to DimensionOverride.ZERO,
        "android:dimen/navigation_bar_height" to DimensionOverride.ZERO,
        "android:dimen/navigation_bar_height_landscape" to DimensionOverride.ZERO,
        "android:dimen/navigation_bar_width" to DimensionOverride.ZERO,
        "android:dimen/navigation_bar_gesture_height" to DimensionOverride.GESTURE_HEIGHT,
        "${ModuleConfig.SYSTEM_UI_PACKAGE}:dimen/navigation_handle_radius" to DimensionOverride.ZERO,
        "${ModuleConfig.SYSTEM_UI_PACKAGE}:dimen/navigation_handle_height" to DimensionOverride.ZERO,
        "${ModuleConfig.SYSTEM_UI_PACKAGE}:dimen/navigation_handle_bottom" to DimensionOverride.ZERO,
        "${ModuleConfig.SYSTEM_UI_PACKAGE}:dimen/navigation_home_handle_width" to DimensionOverride.ZERO
    )

    @Volatile
    private var cachedSettingsUptime = 0L

    @Volatile
    private var cachedOfficialGestureBarHidden = false

    @Volatile
    private var cachedContext: Context? = null

    fun install(module: XposedModule, logger: ModuleLogger, processLabel: String) {
        hookDimensionMethod(
            module,
            logger,
            "getDimensionPixelSize",
            processLabel
        ) { override, resources -> override.toPx(resources).roundToInt() }
        hookDimensionMethod(
            module,
            logger,
            "getDimensionPixelOffset",
            processLabel
        ) { override, resources -> override.toPx(resources).toInt() }
        hookDimensionMethod(
            module,
            logger,
            "getDimension",
            processLabel
        ) { override, resources -> override.toPx(resources) }
    }

    private fun hookDimensionMethod(
        module: XposedModule,
        logger: ModuleLogger,
        methodName: String,
        processLabel: String,
        result: (DimensionOverride, Resources) -> Any
    ) {
        val method = Resources::class.java.getDeclaredMethod(
            methodName,
            Int::class.javaPrimitiveType!!
        ).apply { isAccessible = true }

        HookSupport.hookMethod(
            module,
            logger,
            method,
            "$processLabel Resources.$methodName"
        ) { chain -> handleDimension(chain, result) }
    }

    private fun handleDimension(
        chain: XposedInterface.Chain,
        result: (DimensionOverride, Resources) -> Any
    ): Any? {
        if (!isOfficialGestureBarHidden()) {
            return chain.proceed()
        }

        val resources = chain.getThisObject() as? Resources ?: return chain.proceed()
        val id = chain.getArg(0) as? Int ?: return chain.proceed()
        val override = resolveOverride(resources, id)
        return if (override == DimensionOverride.NONE) {
            chain.proceed()
        } else {
            result(override, resources)
        }
    }

    private fun resolveOverride(resources: Resources, id: Int): DimensionOverride {
        if (id == Resources.ID_NULL) {
            return DimensionOverride.NONE
        }
        val resourceName = runCatching { resources.getResourceName(id) }.getOrNull()
            ?: return DimensionOverride.NONE
        return DIMENSION_OVERRIDES[resourceName] ?: DimensionOverride.NONE
    }

    fun isOfficialGestureBarHidden(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - cachedSettingsUptime < SETTINGS_CACHE_WINDOW_MS) {
            return cachedOfficialGestureBarHidden
        }

        val hidden = resolveContext()?.let(::readOfficialGestureBarHidden) ?: false
        cachedOfficialGestureBarHidden = hidden
        cachedSettingsUptime = now
        return hidden
    }

    private fun readOfficialGestureBarHidden(context: Context): Boolean {
        val resolver = context.contentResolver
        return when (Settings.Secure.getInt(resolver, KEY_NAV_STATE, 0)) {
            NAV_STATE_SWIPE_SIDE_GESTURE -> {
                Settings.Secure.getInt(resolver, KEY_SIDE_GESTURE_HIDE_BAR, 0) == 1
            }
            NAV_STATE_SWIPE_UP_GESTURE -> {
                Settings.Secure.getInt(resolver, KEY_UP_GESTURE_HIDE_BAR, 0) == 1
            }
            else -> false
        }
    }

    private fun resolveContext(): Context? {
        cachedContext?.let { return it }
        return runCatching {
            val activityThread = Class.forName("android.app.ActivityThread")
            activityThread.getDeclaredMethod("currentApplication")
                .apply { isAccessible = true }
                .invoke(null) as? Context
                ?: run {
                    val thread = activityThread.getDeclaredMethod("currentActivityThread")
                        .apply { isAccessible = true }
                        .invoke(null)
                    thread?.javaClass?.getDeclaredMethod("getSystemContext")
                        ?.apply { isAccessible = true }
                        ?.invoke(thread) as? Context
                }
        }.getOrNull()?.also { cachedContext = it }
    }

    private enum class DimensionOverride {
        NONE,
        ZERO,
        GESTURE_HEIGHT;

        fun toPx(resources: Resources): Float {
            if (this == ZERO) {
                return 0f
            }
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                GESTURE_HANDLE_HEIGHT_DP,
                resources.displayMetrics
            )
        }
    }
}
