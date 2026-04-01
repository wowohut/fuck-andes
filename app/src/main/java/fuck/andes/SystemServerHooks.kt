package fuck.andes

import io.github.libxposed.api.XposedModule

internal object SystemServerHooks {

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        ContextualSearchHooks.install(module, logger, classLoader)
        AssistantManager.install(module, logger, classLoader)
        PowerHooks.install(module, logger, classLoader)
    }
}
