package fuck.andes

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ModuleMain : XposedModule() {

    private val logger = ModuleLogger(this)
    private var systemServerInstalled = false
    private var systemUiInstalled = false
    private var googleInstalled = false

    override fun onModuleLoaded(param: ModuleLoadedParam) {
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
                if (!systemUiInstalled) {
                    systemUiInstalled = true
                    SystemUiHooks.install(this, logger, param.classLoader)
                }
            }

            ModuleConfig.GOOGLE_PACKAGE -> {
                if (!googleInstalled) {
                    googleInstalled = true
                    GoogleAppHooks.install(this, logger, param.classLoader)
                }
            }
        }
    }
}
