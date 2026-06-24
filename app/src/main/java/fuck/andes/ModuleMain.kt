package fuck.andes

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import fuck.andes.config.Prefs

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var systemServerInstalled = false
    private var systemUiInstalled = false
    private var googleInstalled = false
    private var colorDirectInstalled = false
    private var currentProcessName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!shouldKeepLifecycleCallbacks(param)) {
            detach()
            return
        }
        // 缓存框架提供的只读 remote preferences，供所有 hook 拦截回调即时读取。
        // getRemotePreferences 是 XposedInterface 的方法，XposedModule 继承自其 Wrapper 可直接调用。
        // 调用失败（框架不支持 remote）时静默回退默认值（全开），不影响 hook 安装。
        Prefs.attachRemote(runCatching { getRemotePreferences(Prefs.GROUP) }.getOrNull())
        logger.debug(
            "模块已加载 process=${param.processName}, framework=$frameworkName($frameworkVersionCode), api=$apiVersion"
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        if (systemServerInstalled) return
        systemServerInstalled = true
        SystemServerHooks.install(this, logger, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        when (param.packageName) {
            ModuleConfig.SYSTEM_UI_PACKAGE -> {
                if (!systemUiInstalled && currentProcessName == ModuleConfig.SYSTEM_UI_PACKAGE) {
                    systemUiInstalled = true
                    SystemUiHooks.install(this, logger, param.classLoader)
                }
            }

            ModuleConfig.GOOGLE_PACKAGE -> {
                if (!googleInstalled && isCurrentPackageProcess(ModuleConfig.GOOGLE_PACKAGE)) {
                    googleInstalled = true
                    GoogleEligibilityHooks.install(this, logger, param.classLoader)
                    GoogleAppHooks.install(this, logger, param.classLoader)
                }
            }

            ModuleConfig.COLOR_DIRECT_PACKAGE -> {
                if (!colorDirectInstalled && isCurrentPackageProcess(ModuleConfig.COLOR_DIRECT_PACKAGE)) {
                    colorDirectInstalled = true
                    ColorDirectHooks.install(this, logger, param.classLoader)
                }
            }
        }
    }

    private fun isCurrentPackageProcess(packageName: String): Boolean {
        val processName = currentProcessName ?: return false
        return isPackageProcess(processName, packageName)
    }

    private fun shouldKeepLifecycleCallbacks(param: ModuleLoadedParam): Boolean {
        if (param.isSystemServer) return true
        val processName = param.processName
        return processName == ModuleConfig.SYSTEM_UI_PACKAGE ||
            isPackageProcess(processName, ModuleConfig.GOOGLE_PACKAGE) ||
            isPackageProcess(processName, ModuleConfig.COLOR_DIRECT_PACKAGE)
    }

    private fun isPackageProcess(processName: String, packageName: String): Boolean =
        processName == packageName || processName.startsWith("$packageName:")
}
