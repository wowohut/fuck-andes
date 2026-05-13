package fuck.andes

import android.graphics.Point
import android.os.Handler
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs

internal object HiddenGestureHandleHooks {
    private const val SIDE_GESTURE_DETECTOR_CLASS =
        "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector"
    private const val GESTURE_HOME_HANDLE_EVENT_CONTROLLER_CLASS =
        "com.oplus.systemui.navigationbar.gesture.otherbusiness.GestureHomeHandleEventController"
    private const val NAV_BAR_FEATURE_OPTION_CLASS =
        "com.oplusos.systemui.common.feature.NavBarFeatureOption"
    private const val METHOD_ON_DOWN = "onDown"
    private const val METHOD_ON_SHOW_PRESS = "onShowPress"
    private const val METHOD_ON_PRE_LONG_PRESS = "onPreLongPress"
    private const val METHOD_ON_LONG_CLICK = "onLongClick"
    private const val METHOD_ON_LONG_PRESS_CANCEL = "onLongPressCancel"
    private const val SHOW_PRESS_DELAY_MS = 200L
    private const val PRE_LONG_PRESS_DELAY_MS = 300L
    private const val LONG_PRESS_DELAY_MS = 800L
    private const val FALLBACK_TOUCH_SLOP_PX = 24f

    private val sessions = WeakHashMap<Any, LongPressSession>()

    @Volatile
    private var cachedGestureHomeHandleCallbacks: GestureHomeHandleCallbacks? = null

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val detectorClass = HookSupport.findClassOrNull(classLoader, SIDE_GESTURE_DETECTOR_CLASS)
        val method = detectorClass?.let {
            HookSupport.findMethod(it, "onMotionEventImpl", MotionEvent::class.java)
        }
        if (method == null) {
            logger.warn("未找到 SideGestureDetector.onMotionEventImpl(MotionEvent)，无法恢复隐藏手势指示条后的长按识屏")
            return
        }

        HookSupport.hookMethod(
            module,
            logger,
            method,
            "SideGestureDetector.onMotionEventImpl hidden gesture long press"
        ) { chain ->
            val detector = chain.getThisObject()
            val motionEvent = chain.getArg(0) as? MotionEvent
            handleMotionEventBeforeOriginal(detector, motionEvent, module, logger, classLoader)
            chain.proceed()
        }
    }

    private fun handleMotionEventBeforeOriginal(
        detector: Any,
        motionEvent: MotionEvent?,
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        if (motionEvent == null || !NavigationBarHideHooks.isOfficialGestureBarHidden()) {
            cancelSession(
                detector = detector,
                module = module,
                logger = logger,
                classLoader = classLoader,
                triggerCancel = false
            )
            return
        }

        when (motionEvent.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelSession(
                    detector = detector,
                    module = module,
                    logger = logger,
                    classLoader = classLoader,
                    triggerCancel = false
                )
                if (canStartHiddenLongPress(detector, motionEvent, logger, classLoader)) {
                    startSession(detector, motionEvent, module, logger, classLoader)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val session = synchronized(sessions) { sessions[detector] } ?: return
                val slop = (HookSupport.getFieldValue(detector, "mTouchSlop") as? Float)
                    ?.takeIf { it > 0f }
                    ?: FALLBACK_TOUCH_SLOP_PX
                if (abs(motionEvent.x - session.downX) > slop || abs(motionEvent.y - session.downY) > slop) {
                    cancelSession(
                        detector = detector,
                        module = module,
                        logger = logger,
                        classLoader = classLoader,
                        triggerCancel = true
                    )
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelSession(
                    detector = detector,
                    module = module,
                    logger = logger,
                    classLoader = classLoader,
                    triggerCancel = true
                )
            }
        }
    }

    private fun startSession(
        detector: Any,
        motionEvent: MotionEvent,
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val handler = HookSupport.getFieldValue(detector, "mMainHandler") as? Handler
        if (handler == null) {
            logger.warnThrottled(
                "hidden_gesture_missing_handler",
                "隐藏手势指示条状态下无法取得 SideGestureDetector.mMainHandler"
            )
            return
        }

        val callbacks = resolveGestureHomeHandleCallbacks(module, classLoader, logger)
        if (callbacks == null) {
            logger.warnThrottled(
                "hidden_gesture_missing_controller",
                "隐藏手势指示条状态下无法取得 GestureHomeHandleEventController.INSTANCE"
            )
            return
        }

        val session = LongPressSession(motionEvent.x, motionEvent.y)

        session.showPressRunnable = Runnable {
            invokeController(callbacks, callbacks.onShowPress, METHOD_ON_SHOW_PRESS, logger)
        }
        session.preLongPressRunnable = Runnable {
            invokeController(callbacks, callbacks.onPreLongPress, METHOD_ON_PRE_LONG_PRESS, logger)
        }
        session.longPressRunnable = Runnable {
            session.longPressed = true
            invokeController(callbacks, callbacks.onLongClick, METHOD_ON_LONG_CLICK, logger)
            synchronized(sessions) {
                if (sessions[detector] === session) {
                    sessions.remove(detector)
                }
            }
        }

        synchronized(sessions) {
            sessions[detector] = session
        }
        invokeController(callbacks, callbacks.onDown, METHOD_ON_DOWN, logger)
        handler.postDelayed(session.showPressRunnable, SHOW_PRESS_DELAY_MS)
        handler.postDelayed(session.preLongPressRunnable, PRE_LONG_PRESS_DELAY_MS)
        handler.postDelayed(session.longPressRunnable, LONG_PRESS_DELAY_MS)
    }

    private fun cancelSession(
        detector: Any,
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader,
        triggerCancel: Boolean
    ) {
        val session = synchronized(sessions) { sessions.remove(detector) } ?: return
        val handler = HookSupport.getFieldValue(detector, "mMainHandler") as? Handler
        handler?.removeCallbacks(session.showPressRunnable)
        handler?.removeCallbacks(session.preLongPressRunnable)
        handler?.removeCallbacks(session.longPressRunnable)
        if (triggerCancel && !session.longPressed) {
            resolveGestureHomeHandleCallbacks(module, classLoader, logger)?.let { callbacks ->
                invokeController(
                    callbacks,
                    callbacks.onLongPressCancel,
                    METHOD_ON_LONG_PRESS_CANCEL,
                    logger
                )
            }
        }
    }

    private fun canStartHiddenLongPress(
        detector: Any,
        motionEvent: MotionEvent,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): Boolean {
        if (!isTouchInBottomGestureArea(detector, motionEvent, logger)) {
            return false
        }
        return isSystemUiStateAllowHandle(detector, logger, classLoader)
    }

    private fun isTouchInBottomGestureArea(
        detector: Any,
        motionEvent: MotionEvent,
        logger: ModuleLogger
    ): Boolean {
        val animation = HookSupport.getFieldValue(detector, "mSideGestureHomeHandleAnimation")
            ?: return warnMissingGestureField(logger, "mSideGestureHomeHandleAnimation")
        val displaySize = HookSupport.getFieldValue(animation, "mDisplaySize") as? Point
            ?: return warnMissingGestureField(logger, "mDisplaySize")
        val configuration = HookSupport.getFieldValue(animation, "mSideGestureConfiguration")
            ?: return warnMissingGestureField(logger, "mSideGestureConfiguration")
        val bottomGestureAreaHeight = HookSupport.invokeNoArgs(configuration, "getBottomGestureAreaHeight") as? Int
        if (bottomGestureAreaHeight == null) {
            logger.warnThrottled(
                "hidden_gesture_missing_bottom_area",
                "隐藏手势指示条状态下无法取得底部手势区域高度"
            )
            return false
        }
        return motionEvent.y.toInt() >= displaySize.y - bottomGestureAreaHeight
    }

    private fun isSystemUiStateAllowHandle(
        detector: Any,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ): Boolean {
        val flags = HookSupport.getFieldValue(detector, "mSysUiFlags") as? Long
            ?: return warnMissingGestureField(logger, "mSysUiFlags")
        if ((flags and 2L) != 0L) {
            return false
        }

        val notificationShadeVisible = (flags and 4L) != 0L
        val keyguardShowing = (flags and 64L) != 0L
        val bouncerShowing = (flags and 2048L) != 0L
        val homeOrRecentsEnabled = (flags and 256L) == 0L || (flags and 128L) == 0L
        if (bouncerShowing || !homeOrRecentsEnabled) {
            return false
        }

        return if (isSupportTaskbar(classLoader)) {
            !notificationShadeVisible
        } else {
            !notificationShadeVisible || keyguardShowing
        }
    }

    private fun warnMissingGestureField(logger: ModuleLogger, fieldName: String): Boolean {
        logger.warnThrottled(
            "hidden_gesture_missing_$fieldName",
            "隐藏手势指示条状态下无法取得 $fieldName"
        )
        return false
    }

    private fun isSupportTaskbar(classLoader: ClassLoader): Boolean =
        runCatching {
            val clazz = Class.forName(NAV_BAR_FEATURE_OPTION_CLASS, false, classLoader)
            HookSupport.findField(clazz, "sIsSupportTaskbar")?.getBoolean(null) == true
        }.getOrDefault(false)

    private fun resolveGestureHomeHandleCallbacks(
        module: XposedModule,
        classLoader: ClassLoader,
        logger: ModuleLogger
    ): GestureHomeHandleCallbacks? =
        cachedGestureHomeHandleCallbacks ?: resolveGestureHomeHandleCallbacksUncached(
            module,
            classLoader,
            logger
        )

    private fun resolveGestureHomeHandleCallbacksUncached(
        module: XposedModule,
        classLoader: ClassLoader,
        logger: ModuleLogger
    ): GestureHomeHandleCallbacks? =
        runCatching {
            val clazz = Class.forName(GESTURE_HOME_HANDLE_EVENT_CONTROLLER_CLASS, false, classLoader)
            HookSupport.findField(clazz, "INSTANCE")?.get(null)
        }.getOrNull()?.let { controller ->
            GestureHomeHandleCallbacks(
                controller = controller,
                onDown = controllerInvoker(module, controller, METHOD_ON_DOWN, logger),
                onShowPress = controllerInvoker(module, controller, METHOD_ON_SHOW_PRESS, logger),
                onPreLongPress = controllerInvoker(module, controller, METHOD_ON_PRE_LONG_PRESS, logger),
                onLongClick = controllerInvoker(module, controller, METHOD_ON_LONG_CLICK, logger),
                onLongPressCancel = controllerInvoker(
                    module,
                    controller,
                    METHOD_ON_LONG_PRESS_CANCEL,
                    logger
                )
            )
        }?.also { cachedGestureHomeHandleCallbacks = it }

    private fun controllerInvoker(
        module: XposedModule,
        controller: Any,
        methodName: String,
        logger: ModuleLogger
    ): XposedInterface.Invoker<*, Method>? {
        val method = HookSupport.findMethod(controller.javaClass, methodName)
        if (method == null) {
            return null
        }
        return runCatching { module.getInvoker(method) }
            .onFailure { throwable ->
                logger.errorThrottled(
                    "hidden_gesture_controller_${methodName}_invoker_failed",
                    "隐藏手势指示条状态下创建 $methodName 调用器失败",
                    throwable
                )
            }
            .getOrNull()
    }

    private fun invokeController(
        callbacks: GestureHomeHandleCallbacks,
        invoker: XposedInterface.Invoker<*, Method>?,
        methodName: String,
        logger: ModuleLogger
    ) {
        if (invoker == null) {
            logger.warnThrottled(
                "hidden_gesture_controller_$methodName",
                "隐藏手势指示条状态下无法调用 $methodName"
            )
            return
        }
        runCatching { invoker.invoke(callbacks.controller) }
            .onFailure { throwable ->
                logger.errorThrottled(
                    "hidden_gesture_controller_${methodName}_failed",
                    "隐藏手势指示条状态下调用 $methodName 失败",
                    throwable
                )
            }
    }

    private class GestureHomeHandleCallbacks(
        val controller: Any,
        val onDown: XposedInterface.Invoker<*, Method>?,
        val onShowPress: XposedInterface.Invoker<*, Method>?,
        val onPreLongPress: XposedInterface.Invoker<*, Method>?,
        val onLongClick: XposedInterface.Invoker<*, Method>?,
        val onLongPressCancel: XposedInterface.Invoker<*, Method>?
    )

    private class LongPressSession(
        val downX: Float,
        val downY: Float
    ) {
        lateinit var showPressRunnable: Runnable
        lateinit var preLongPressRunnable: Runnable
        lateinit var longPressRunnable: Runnable
        var longPressed = false
    }
}
