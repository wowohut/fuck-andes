package fuck.andes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable
import java.lang.reflect.Field
import java.lang.reflect.Method

internal object HookSupport {

    fun findClassOrNull(classLoader: ClassLoader, className: String): Class<*>? =
        runCatching { Class.forName(className, false, classLoader) }.getOrNull()

    fun findMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                current.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
            }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    fun findDeclaredMethod(
        clazz: Class<*>,
        name: String,
        vararg parameterTypes: Class<*>
    ): Method? =
        runCatching {
            clazz.getDeclaredMethod(name, *parameterTypes).apply { isAccessible = true }
        }.getOrNull()

    fun findField(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null) {
            runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }
            }.getOrNull()?.let { return it }
            current = current.superclass
        }
        return null
    }

    fun getFieldValue(target: Any, name: String): Any? =
        runCatching { findField(target.javaClass, name)?.get(target) }.getOrNull()

    fun invokeNoArgs(target: Any, name: String): Any? =
        runCatching { findMethod(target.javaClass, name)?.invoke(target) }.getOrNull()

    fun hookMethod(
        module: XposedModule,
        logger: ModuleLogger,
        executable: Executable,
        description: String,
        hooker: (XposedInterface.Chain) -> Any?
    ) {
        runCatching {
            module.hook(executable)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept { chain -> hooker(chain) }
        }.onSuccess {
            logger.debug("已安装 Hook: $description")
        }.onFailure { throwable ->
            logger.error("安装 Hook 失败: $description", throwable)
        }
    }

    fun deoptimize(
        module: XposedModule,
        logger: ModuleLogger,
        executable: Executable,
        description: String
    ) {
        runCatching { module.deoptimize(executable) }
            .onSuccess { success ->
                logger.debug("Deopt $description = $success")
            }
            .onFailure { throwable ->
                logger.warn("Deopt 失败: $description, ${throwable.message}")
            }
    }

    fun extractPackageName(componentOrPackage: String?): String? {
        if (componentOrPackage.isNullOrBlank()) return null
        return ComponentName.unflattenFromString(componentOrPackage)?.packageName
            ?: componentOrPackage.substringBefore('/', componentOrPackage)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean =
        runCatching {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    fun resolvesActivity(context: Context, intent: Intent): Boolean =
        context.packageManager.resolveActivity(intent, 0) != null
}
