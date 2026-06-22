package fuck.andes.systemizer

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class RootProbeResult(
    val exitCode: Int,
    val output: String,
) {
    val isAvailable: Boolean
        get() = exitCode == 0 && output.isNotBlank()
}

internal enum class RootManager {
    MAGISK,
    KERNEL_SU,
    UNSUPPORTED,
}

internal enum class InstallPreflight {
    READY,
    KERNEL_SU_OVERLAY_MISSING,
    UNSUPPORTED_ROOT_MANAGER,
}

internal sealed interface SystemizerInstallResult {
    data object AlreadySystemized : SystemizerInstallResult
    data object GoogleAppMissing : SystemizerInstallResult
    data object UnsupportedRootManager : SystemizerInstallResult
    data object KernelSuOverlayMissing : SystemizerInstallResult
    data class InstalledRebootRequired(val rootManager: RootManager) : SystemizerInstallResult
    data class Failed(val message: String, val commandOutput: String = "") : SystemizerInstallResult
}

internal class GoogleAppSystemizerInstaller(
    private val context: Context,
) {

    fun install(): SystemizerInstallResult {
        if (findGoogleAppInfo() == null) {
            return SystemizerInstallResult.GoogleAppMissing
        }
        if (isGoogleAppSystemPrivApp()) {
            return SystemizerInstallResult.AlreadySystemized
        }

        val rootManager = detectRootManager()
        val kernelSuOverlayReady = rootManager != RootManager.KERNEL_SU || hasKernelSuOverlaySupportOnDevice()
        return when (preflight(rootManager, kernelSuOverlayReady)) {
            InstallPreflight.UNSUPPORTED_ROOT_MANAGER -> SystemizerInstallResult.UnsupportedRootManager
            InstallPreflight.KERNEL_SU_OVERLAY_MISSING -> SystemizerInstallResult.KernelSuOverlayMissing
            InstallPreflight.READY -> installModule(rootManager)
        }
    }

    fun isGoogleAppSystemPrivApp(): Boolean {
        val appInfo = findGoogleAppInfo() ?: return false
        val systemApp = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 ||
            appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
        return systemApp && appInfo.hasPrivilegedPrivateFlag()
    }

    private fun installModule(rootManager: RootManager): SystemizerInstallResult {
        val moduleZip = runCatching { copyModuleAssetToCache() }
            .getOrElse {
                Log.w(TAG, "Prepare Google App systemizer module failed", it)
                return SystemizerInstallResult.Failed("模块资源准备失败")
            }

        val command = buildInstallCommand(rootManager, moduleZip.absolutePath)
        val result = runSu(command, timeoutSeconds = 120)
        Log.i(TAG, "Google App systemizer install exit=${result.exitCode}, output=${result.output.take(512)}")
        return if (result.exitCode == 0) {
            SystemizerInstallResult.InstalledRebootRequired(rootManager)
        } else {
            SystemizerInstallResult.Failed(
                message = "模块安装失败",
                commandOutput = result.output.takeLast(1200),
            )
        }
    }

    private fun detectRootManager(): RootManager =
        detectRootManager(
            ksudProbe = runSu(buildKernelSuProbeCommand(), timeoutSeconds = 8).toRootProbeResult(),
            magiskProbe = runSu("magisk -V", timeoutSeconds = 8).toRootProbeResult(),
        )

    private fun hasKernelSuOverlaySupportOnDevice(): Boolean {
        val condition = kernelSuOverlayPaths.joinToString(separator = " || ") {
            "[ -e '${it.escapeForSingleQuotedShell()}' ]"
        }
        val result = runSu("if $condition; then echo yes; fi", timeoutSeconds = 8)
        return result.exitCode == 0 && result.output.lineSequence().any { it.trim() == "yes" }
    }

    private fun copyModuleAssetToCache(): File {
        val target = File(context.cacheDir, MODULE_ASSET_NAME)
        context.assets.open(MODULE_ASSET_NAME).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        target.setReadable(true, false)
        return target
    }

    private fun findGoogleAppInfo(): ApplicationInfo? =
        runCatching {
            context.packageManager.getApplicationInfo(
                GOOGLE_PACKAGE,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        }.getOrNull()

    private fun runSu(command: String, timeoutSeconds: Long): RootCommandResult {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrElse {
            return RootCommandResult(exitCode = -1, output = it.message.orEmpty())
        }

        val output = StringBuilder()
        val reader = thread(name = "google-systemizer-shell-reader") {
            runCatching {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { output.appendLine(it) }
                }
            }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            reader.join(1000)
            return RootCommandResult(exitCode = -2, output = "命令执行超时")
        }

        reader.join(1000)
        return RootCommandResult(exitCode = process.exitValue(), output = output.toString().trim())
    }

    companion object {
        private const val TAG = "FuckAndesSystemizer"
        private const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"
        private const val MODULE_ASSET_NAME = "googlequicksearchbox-systemizer.zip"
        private const val KERNEL_SU_FALLBACK_BIN = "/data/adb/ksu/bin/ksud"

        private val KERNEL_SU_OVERLAY_PATHS = setOf(
            "/data/adb/metamodule",
            "/data/adb/modules/meta-overlayfs/module.prop",
            "/data/adb/modules/meta-overlay/module.prop",
        )

        fun detectRootManager(
            ksudProbe: RootProbeResult,
            magiskProbe: RootProbeResult,
        ): RootManager = when {
            ksudProbe.isAvailable -> RootManager.KERNEL_SU
            magiskProbe.isAvailable -> RootManager.MAGISK
            else -> RootManager.UNSUPPORTED
        }

        fun buildInstallCommand(rootManager: RootManager, zipPath: String): String =
            when (rootManager) {
                RootManager.MAGISK -> "magisk --install-module '${zipPath.escapeForSingleQuotedShell()}'"
                RootManager.KERNEL_SU -> buildKernelSuInstallCommand(zipPath)
                RootManager.UNSUPPORTED -> ""
            }

        fun buildKernelSuProbeCommand(): String =
            "if command -v ksud >/dev/null 2>&1; then ksud -V; " +
                "elif [ -x '$KERNEL_SU_FALLBACK_BIN' ]; then '$KERNEL_SU_FALLBACK_BIN' -V; fi"

        private fun buildKernelSuInstallCommand(zipPath: String): String {
            val escapedZipPath = zipPath.escapeForSingleQuotedShell()
            return "if command -v ksud >/dev/null 2>&1; then " +
                "ksud module install '$escapedZipPath'; " +
                "else '$KERNEL_SU_FALLBACK_BIN' module install '$escapedZipPath'; fi"
        }

        fun hasKernelSuOverlaySupport(existingPaths: Set<String>): Boolean =
            KERNEL_SU_OVERLAY_PATHS.any(existingPaths::contains)

        fun preflight(
            rootManager: RootManager,
            hasKernelSuOverlaySupport: Boolean,
        ): InstallPreflight =
            when {
                rootManager == RootManager.UNSUPPORTED -> InstallPreflight.UNSUPPORTED_ROOT_MANAGER
                rootManager == RootManager.KERNEL_SU && !hasKernelSuOverlaySupport ->
                    InstallPreflight.KERNEL_SU_OVERLAY_MISSING
                else -> InstallPreflight.READY
            }

        internal val kernelSuOverlayPaths: Set<String>
            get() = KERNEL_SU_OVERLAY_PATHS
    }
}

private data class RootCommandResult(
    val exitCode: Int,
    val output: String,
) {
    fun toRootProbeResult(): RootProbeResult =
        RootProbeResult(exitCode = exitCode, output = output)
}

private fun ApplicationInfo.hasPrivilegedPrivateFlag(): Boolean {
    val privateFlags = runCatching {
        ApplicationInfo::class.java
            .getDeclaredField("privateFlags")
            .also { it.isAccessible = true }
            .getInt(this)
    }.getOrNull()

    val privilegedFlag = runCatching {
        ApplicationInfo::class.java
            .getDeclaredField("PRIVATE_FLAG_PRIVILEGED")
            .getInt(null)
    }.getOrDefault(1 shl 3)

    if (privateFlags != null) {
        return privateFlags and privilegedFlag != 0
    }

    return sourceDir?.contains("/priv-app/") == true ||
        publicSourceDir?.contains("/priv-app/") == true
}

private fun String.escapeForSingleQuotedShell(): String =
    replace("'", "'\\''")
