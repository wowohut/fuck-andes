package fuck.andes

import android.content.Intent
import android.os.Build
import android.os.Bundle
import io.github.libxposed.api.XposedModule
import java.io.File
import java.lang.reflect.Field
import java.nio.charset.StandardCharsets

internal object GoogleAppHooks {
    private const val PRO_MODE_ID = "e6fa609c3fa255c0"
    private const val FULLSCREEN_ZERO_STATE_MODE_FILE = "FullscreenZeroStateModeDataStore.pb"
    private val proModePayload = byteArrayOf(0x0A, 0x10) + PRO_MODE_ID.toByteArray(StandardCharsets.US_ASCII)

    private val mainChatEntryActivityNames = listOf(
        "com.google.android.apps.search.assistant.surfaces.voice.deeplinks.handlers.gateway.impl.GeminiGatewayActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.launcher.RobinEntryPointActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.launcher.RobinShellAppEntryPointActivity",
        "com.google.android.apps.search.assistant.surfaces.voice.robin.main.MainActivity"
    )

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        // 保留 Google 进程内机型伪装，避免影响用户现有的一圈即搜能力。
        setBuildField(logger, Build::class.java, "MANUFACTURER", "samsung")
        setBuildField(logger, Build::class.java, "BRAND", "samsung")
        setBuildField(logger, Build::class.java, "MODEL", "SM-S928B")
        setBuildField(logger, Build::class.java, "PRODUCT", "e3s")
        setBuildField(logger, Build::class.java, "DEVICE", "e3s")
        installMainChatEntryHooks(module, logger, classLoader)
    }

    private fun installMainChatEntryHooks(
        module: XposedModule,
        logger: ModuleLogger,
        classLoader: ClassLoader
    ) {
        mainChatEntryActivityNames.forEach { className ->
            val activityClass = HookSupport.findClassOrNull(classLoader, className) ?: return@forEach
            hookDeclaredEntryMethod(
                module = module,
                logger = logger,
                activityClass = activityClass,
                className = className,
                methodName = "onCreate",
                parameterTypes = arrayOf(Bundle::class.java)
            )
            hookDeclaredEntryMethod(
                module = module,
                logger = logger,
                activityClass = activityClass,
                className = className,
                methodName = "onNewIntent",
                parameterTypes = arrayOf(Intent::class.java)
            )
        }
    }

    private fun hookDeclaredEntryMethod(
        module: XposedModule,
        logger: ModuleLogger,
        activityClass: Class<*>,
        className: String,
        methodName: String,
        parameterTypes: Array<Class<*>>
    ) {
        val method = HookSupport.findDeclaredMethod(activityClass, methodName, *parameterTypes) ?: return
        val description = "$className.$methodName(${parameterTypes.joinToString { it.simpleName }})"
        HookSupport.hookMethod(module, logger, method, description) { chain ->
            patchMainChatDefaultMode(logger)
            chain.proceed()
        }
    }

    private fun patchMainChatDefaultMode(logger: ModuleLogger) {
        resolveGoogleAccountsDirs().forEach { accountsDir ->
            accountsDir.listFiles()
                ?.filter(File::isDirectory)
                ?.sortedBy(File::getName)
                ?.forEach { accountDir ->
                    patchFullscreenZeroStateMode(logger, accountDir)
                }
        }
    }

    private fun resolveGoogleAccountsDirs(): List<File> =
        listOf(
            File("/data/user/0/${ModuleConfig.GOOGLE_PACKAGE}/files/accounts"),
            File("/data/data/${ModuleConfig.GOOGLE_PACKAGE}/files/accounts")
        ).filter { it.exists() }.distinctBy { it.absolutePath }

    private fun patchFullscreenZeroStateMode(logger: ModuleLogger, accountDir: File) {
        val target = File(accountDir, FULLSCREEN_ZERO_STATE_MODE_FILE)
        if (!target.isFile) return

        val currentBytes = runCatching { target.readBytes() }.getOrElse { throwable ->
            logger.warnThrottled(
                key = "gsa-read-mode-${target.absolutePath}",
                message = "GSA default Pro: 读取 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
            )
            return
        }
        if (currentBytes.contentEquals(proModePayload)) return

        runCatching { target.writeBytes(proModePayload) }
            .onFailure { throwable ->
                logger.warnThrottled(
                    key = "gsa-write-mode-${target.absolutePath}",
                    message = "GSA default Pro: 写入 ${target.absolutePath} 失败: ${throwable.javaClass.simpleName}"
                )
            }
    }

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
