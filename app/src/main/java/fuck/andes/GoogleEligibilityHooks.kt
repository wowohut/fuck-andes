package fuck.andes

import io.github.libxposed.api.XposedModule

internal object GoogleEligibilityHooks {
    private const val PROP_OPA_ELIGIBLE_DEVICE = "ro.opa.eligible_device"

    private val googleFeatures = setOf(
        "com.google.android.feature.GOOGLE_BUILD",
        "com.google.android.feature.GOOGLE_EXPERIENCE"
    )

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        // 资格补齐与机型伪装同属"让 Google App 认为设备具备资格"的一件事，
        // 作为一圈即搜的底层依赖始终执行。
        hookSystemProperties(module, logger)
        hookPackageManagerFeatures(module, logger, classLoader)
        logger.debug("GoogleEligibility: 已安装 Google App 资格补齐")
    }

    private fun hookSystemProperties(module: XposedModule, logger: ModuleLogger) {
        val systemPropertiesClass = runCatching {
            Class.forName("android.os.SystemProperties")
        }.getOrElse { throwable ->
            logger.warn("GoogleEligibility: 未找到 SystemProperties: ${throwable.message}")
            return
        }

        HookSupport.findMethod(systemPropertiesClass, "get", String::class.java)?.let { method ->
            HookSupport.hookMethod(module, logger, method, "SystemProperties.get(String)") { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) "true" else chain.proceed()
            }
        }

        HookSupport.findMethod(
            systemPropertiesClass,
            "get",
            String::class.java,
            String::class.java
        )?.let { method ->
            HookSupport.hookMethod(module, logger, method, "SystemProperties.get(String,String)") { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) "true" else chain.proceed()
            }
        }

        HookSupport.findMethod(
            systemPropertiesClass,
            "getBoolean",
            String::class.java,
            Boolean::class.javaPrimitiveType!!
        )?.let { method ->
            HookSupport.hookMethod(module, logger, method, "SystemProperties.getBoolean(String,boolean)") { chain ->
                val key = chain.getArg(0) as? String
                if (key == PROP_OPA_ELIGIBLE_DEVICE) true else chain.proceed()
            }
        }
    }

    private fun hookPackageManagerFeatures(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        val packageManagerClass = HookSupport.findClassOrNull(
            classLoader,
            "android.app.ApplicationPackageManager"
        ) ?: run {
            logger.warn("GoogleEligibility: 未找到 ApplicationPackageManager")
            return
        }

        packageManagerClass.declaredMethods
            .filter { method ->
                method.name == "hasSystemFeature" &&
                    method.returnType == Boolean::class.javaPrimitiveType &&
                    method.parameterTypes.firstOrNull() == String::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                HookSupport.hookMethod(
                    module,
                    logger,
                    method,
                    "ApplicationPackageManager.${method.name}/${method.parameterTypes.size}"
                ) { chain ->
                    val feature = chain.getArg(0) as? String
                    if (feature in googleFeatures) true else chain.proceed()
                }
            }
    }
}
