package fuck.andes.systemizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAppSystemizerInstallerTest {

    @Test
    fun detectRootManagerPrefersKernelSuWhenBothToolsRespond() {
        val manager = GoogleAppSystemizerInstaller.detectRootManager(
            ksudProbe = RootProbeResult(exitCode = 0, output = "KernelSU version 12345"),
            magiskProbe = RootProbeResult(exitCode = 0, output = "30700"),
        )

        assertEquals(RootManager.KERNEL_SU, manager)
    }

    @Test
    fun detectRootManagerFallsBackToMagiskWhenKsudIsUnavailable() {
        val manager = GoogleAppSystemizerInstaller.detectRootManager(
            ksudProbe = RootProbeResult(exitCode = 127, output = ""),
            magiskProbe = RootProbeResult(exitCode = 0, output = "30700"),
        )

        assertEquals(RootManager.MAGISK, manager)
    }

    @Test
    fun buildInstallCommandUsesOfficialCommandForEachSupportedRootManager() {
        val zipPath = "/data/user/0/fuck.andes/cache/googlequicksearchbox-systemizer.zip"

        assertEquals(
            "magisk --install-module '$zipPath'",
            GoogleAppSystemizerInstaller.buildInstallCommand(RootManager.MAGISK, zipPath),
        )
        val kernelSuCommand = GoogleAppSystemizerInstaller.buildInstallCommand(RootManager.KERNEL_SU, zipPath)
        assertTrue(kernelSuCommand.contains("ksud module install '$zipPath'"))
        assertTrue(kernelSuCommand.contains("/data/adb/ksu/bin/ksud' module install '$zipPath'"))
    }

    @Test
    fun buildKernelSuProbeCommandFallsBackToOfficialDataPath() {
        val command = GoogleAppSystemizerInstaller.buildKernelSuProbeCommand()

        assertTrue(command.contains("ksud -V"))
        assertTrue(command.contains("/data/adb/ksu/bin/ksud"))
    }

    @Test
    fun kernelSuOverlaySupportAcceptsMetamoduleOrKnownOverlayModule() {
        assertTrue(
            GoogleAppSystemizerInstaller.hasKernelSuOverlaySupport(
                existingPaths = setOf("/data/adb/metamodule"),
            )
        )
        assertTrue(
            GoogleAppSystemizerInstaller.hasKernelSuOverlaySupport(
                existingPaths = setOf("/data/adb/modules/meta-overlayfs/module.prop"),
            )
        )
        assertTrue(
            GoogleAppSystemizerInstaller.hasKernelSuOverlaySupport(
                existingPaths = setOf("/data/adb/modules/meta-overlay/module.prop"),
            )
        )
        assertFalse(
            GoogleAppSystemizerInstaller.hasKernelSuOverlaySupport(existingPaths = emptySet())
        )
    }

    @Test
    fun preflightBlocksKernelSuWithoutOverlaySupport() {
        assertEquals(
            InstallPreflight.KERNEL_SU_OVERLAY_MISSING,
            GoogleAppSystemizerInstaller.preflight(RootManager.KERNEL_SU, hasKernelSuOverlaySupport = false),
        )
        assertEquals(
            InstallPreflight.READY,
            GoogleAppSystemizerInstaller.preflight(RootManager.KERNEL_SU, hasKernelSuOverlaySupport = true),
        )
        assertEquals(
            InstallPreflight.READY,
            GoogleAppSystemizerInstaller.preflight(RootManager.MAGISK, hasKernelSuOverlaySupport = false),
        )
    }
}
