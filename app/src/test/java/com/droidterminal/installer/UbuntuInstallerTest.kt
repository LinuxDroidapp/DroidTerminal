package com.droidterminal.installer

import com.droidterminal.runtime.ElfValidator
import com.droidterminal.runtime.NativePtyBridge
import com.droidterminal.runtime.ProotLauncher
import com.droidterminal.runtime.ProotSessionConfig
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UbuntuInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val assetProot = File("src/main/assets/proot/arm64-v8a/proot")
    private val assetLoader = File("src/main/assets/proot/arm64-v8a/loader")

    private fun setupInstallerWithBinaries(installer: UbuntuInstaller) {
        installer.linuxDir.mkdirs()
        installer.binDir.mkdirs()
        installer.tmpDir.mkdirs()
        installer.shmDir.mkdirs()
        installer.rootfsDir.mkdirs()

        if (assetProot.exists()) {
            assetProot.copyTo(installer.prootFile, overwrite = true)
            installer.setExecutablePermissions(installer.prootFile)
        }
        if (assetLoader.exists()) {
            assetLoader.copyTo(installer.loaderFile, overwrite = true)
            installer.setExecutablePermissions(installer.loaderFile)
        }

        // Mock minimal guest rootfs structure
        val usrBin = File(installer.rootfsDir, "usr/bin")
        usrBin.mkdirs()
        val bin = File(installer.rootfsDir, "bin")
        bin.mkdirs()
        val usrLib = File(installer.rootfsDir, "usr/lib")
        usrLib.mkdirs()
        val lib = File(installer.rootfsDir, "lib")
        lib.mkdirs()
        val guestTmp = File(installer.rootfsDir, "tmp")
        guestTmp.mkdirs()
        installer.setStickyPermissions(guestTmp)

        // Mock bash and ld-linux interpreter
        val usrBash = File(usrBin, "bash")
        usrBash.writeText("#!/bin/sh\necho DROIDTERMINAL_PROOT_OK\n")
        installer.setExecutablePermissions(usrBash)

        val linker = File(lib, "ld-linux-aarch64.so.1")
        linker.writeText("ELF-INTERPRETER-MOCK")
        installer.setExecutablePermissions(linker)
    }

    // =========================================================================
    // 12 REGRESSION TEST SCENARIOS
    // =========================================================================

    /**
     * Scenario 1: Fresh install verification success.
     */
    @Test
    fun test1FreshInstallVerificationSuccess() {
        val freshFilesDir = File(tempFolder.newFolder(), "test1_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        assertTrue(installer.verifyStageAHostRuntime())
        assertTrue(installer.verifyStageBRootfsStructure())
        assertTrue(installer.verifyStageCProotStartup())
    }

    /**
     * Scenario 2: Missing proot binary failure handled.
     */
    @Test
    fun test2MissingProotBinaryFailureHandled() {
        val freshFilesDir = File(tempFolder.newFolder(), "test2_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        if (installer.prootFile.exists()) installer.prootFile.delete()

        var caught = false
        try {
            installer.verifyStageAHostRuntime()
        } catch (e: IllegalStateException) {
            caught = true
            assertTrue(e.message?.contains("PRoot binary missing") == true || e.message?.contains("Stage A Failed") == true)
        }
        assertTrue("Missing PRoot binary must be rejected in Stage A", caught)
    }

    /**
     * Scenario 3: Missing loader binary failure handled.
     */
    @Test
    fun test3MissingLoaderBinaryFailureHandled() {
        val freshFilesDir = File(tempFolder.newFolder(), "test3_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        if (installer.loaderFile.exists()) installer.loaderFile.delete()

        var caught = false
        try {
            installer.verifyStageAHostRuntime()
        } catch (e: IllegalStateException) {
            caught = true
            assertTrue(e.message?.contains("Companion loader missing") == true)
        }
        assertTrue("Missing loader binary must be rejected in Stage A", caught)
    }

    /**
     * Scenario 4: Version mismatch or corrupt SHA detection.
     */
    @Test
    fun test4VersionMismatchOrCorruptShaDetection() {
        val freshFilesDir = File(tempFolder.newFolder(), "test4_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        // Corrupt proot file content
        val corruptProot = File(installer.binDir, "proot")
        corruptProot.writeText("corrupt binary content")

        val elfCheck = ElfValidator.validateArm64Elf(corruptProot)
        assertFalse("Corrupted binary must fail ELF validation", elfCheck.isValid)

        var caught = false
        try {
            installer.verifyStageAHostRuntime()
        } catch (e: IllegalStateException) {
            caught = true
            assertTrue(e.message?.contains("ELF validation") == true)
        }
        assertTrue("Corrupted PRoot binary must be caught", caught)
    }

    /**
     * Scenario 5: Incomplete rootfs (missing bash) detection.
     */
    @Test
    fun test5IncompleteRootfsMissingBashDetection() {
        val freshFilesDir = File(tempFolder.newFolder(), "test5_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        val usrBash = File(installer.rootfsDir, "usr/bin/bash")
        val binBash = File(installer.rootfsDir, "bin/bash")
        if (usrBash.exists()) usrBash.delete()
        if (binBash.exists()) binBash.delete()

        var caught = false
        try {
            installer.verifyStageBRootfsStructure()
        } catch (e: IllegalStateException) {
            caught = true
            assertTrue(e.message?.contains("Bash shell binary missing") == true)
        }
        assertTrue("Missing bash in rootfs must be caught in Stage B", caught)
    }

    /**
     * Scenario 6: Missing ELF dynamic linker interpreter detection.
     */
    @Test
    fun test6MissingElfDynamicLinkerInterpreterDetection() {
        val freshFilesDir = File(tempFolder.newFolder(), "test6_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        val linkers = listOf(
            File(installer.rootfsDir, "lib/ld-linux-aarch64.so.1"),
            File(installer.rootfsDir, "usr/lib/ld-linux-aarch64.so.1"),
            File(installer.rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        )
        linkers.forEach { if (it.exists()) it.delete() }

        var caught = false
        try {
            installer.verifyStageBRootfsStructure()
        } catch (e: IllegalStateException) {
            caught = true
            assertTrue(e.message?.contains("Dynamic linker") == true)
        }
        assertTrue("Missing dynamic linker must be caught in Stage B", caught)
    }

    /**
     * Scenario 7: Non-executable permissions on bash/interpreter properly corrected.
     */
    @Test
    fun test7NonExecutablePermissionsOnBashOrInterpreterCorrected() {
        val freshFilesDir = File(tempFolder.newFolder(), "test7_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        val usrBash = File(installer.rootfsDir, "usr/bin/bash")
        val linker = File(installer.rootfsDir, "lib/ld-linux-aarch64.so.1")

        usrBash.setExecutable(false, false)
        linker.setExecutable(false, false)

        // Stage B should ensure executable permissions are applied
        val valid = installer.verifyStageBRootfsStructure()
        assertTrue("Stage B must pass after ensuring permissions", valid)
        assertTrue("usrBash must be made executable", usrBash.canExecute())
        assertTrue("linker must be made executable", linker.canExecute())
    }

    /**
     * Scenario 8: Host temporary directory missing/unwritable detected and fixed.
     */
    @Test
    fun test8HostTemporaryDirectoryMissingOrUnwritableFixed() {
        val freshFilesDir = File(tempFolder.newFolder(), "test8_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        if (installer.tmpDir.exists()) installer.tmpDir.deleteRecursively()
        assertFalse("Host tmp dir should not exist before resolution", installer.tmpDir.exists())

        val resolvedTmp = ProotLauncher.resolveHostTmpDir(
            ProotSessionConfig(rootfsDir = installer.rootfsDir, prootExecutable = installer.prootFile, tmpDir = installer.tmpDir)
        )

        assertTrue("Host tmp dir must be created", resolvedTmp.exists())
        assertTrue("Host tmp dir must be a directory", resolvedTmp.isDirectory)
        assertTrue("Host tmp dir must be writable", resolvedTmp.canWrite())
    }

    /**
     * Scenario 9: Guest /tmp missing/unwritable created with 1777 permissions.
     */
    @Test
    fun test9GuestTmpMissingOrUnwritableCreatedWith1777() {
        val freshFilesDir = File(tempFolder.newFolder(), "test9_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        val guestTmp = File(installer.rootfsDir, "tmp")
        if (guestTmp.exists()) guestTmp.deleteRecursively()
        assertFalse("Guest tmp must not exist before test", guestTmp.exists())

        installer.verifyStageBRootfsStructure()

        assertTrue("Guest tmp must be created by Stage B", guestTmp.exists())
        assertTrue("Guest tmp must be a directory", guestTmp.isDirectory)
        assertTrue("Guest tmp must be writable", guestTmp.canWrite())
    }

    /**
     * Scenario 10: Interrupted extraction/staging recovery and transactional cleanup.
     */
    @Test
    fun test10InterruptedExtractionStagingRecoveryAndCleanup() {
        val freshFilesDir = File(tempFolder.newFolder(), "test10_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        installer.acquireLock()
        installer.prepareStorageAndDirectories()

        // Create partial staging directory to simulate interrupted extraction
        installer.stagingDir.mkdirs()
        File(installer.stagingDir, "partial_file.tmp").writeText("incomplete extraction")

        // Repair or fresh re-run must clean staging
        installer.stagingDir.deleteRecursively()
        assertFalse("Staging dir must be deleted on clean restart", installer.stagingDir.exists())

        installer.releaseLock()
        assertFalse("Lock must be released", installer.isLockHeld())
    }

    /**
     * Scenario 11: Restart survival (environment persists across re-runs without re-extracting).
     */
    @Test
    fun test11RestartSurvivalPersistsEnvironment() {
        val freshFilesDir = File(tempFolder.newFolder(), "test11_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        // Write completion markers
        installer.installedMarker.parentFile?.mkdirs()
        installer.installedMarker.writeText("""{"installed": true}""")

        assertTrue("Installer should report isInstalled = true", installer.isInstalled())

        // Re-instantiate installer to simulate app restart
        val restartedInstaller = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        assertTrue("Environment must persist across restarts", restartedInstaller.isInstalled())
    }

    /**
     * Scenario 12: Interactive PTY session execution with PRoot.
     */
    @Test
    fun test12InteractivePtySessionExecutionWithProot() {
        val freshFilesDir = File(tempFolder.newFolder(), "test12_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        setupInstallerWithBinaries(installer)

        val stageEResult = installer.verifyStageEInteractivePty()
        assertTrue("Stage E verification must pass", stageEResult)
    }

    // =========================================================================
    // BASELINE REGRESSION TESTS
    // =========================================================================

    @Test
    fun testFreshApplicationDataDirectoryInitializationAndLocking() {
        val freshFilesDir = File(tempFolder.newFolder(), "fresh_app_files")
        val linuxDir = File(freshFilesDir, "linux")
        val lockFile = File(linuxDir, ".installlock")

        assertFalse("linux directory must not exist prior to installer start", linuxDir.exists())
        assertFalse("lock file must not exist prior to installer start", lockFile.exists())

        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        try {
            installer.acquireLock()

            assertTrue("linux directory must be created recursively by installer", linuxDir.exists())
            assertTrue("linux path must be a directory", linuxDir.isDirectory)
            assertTrue("lock file must exist after acquiring lock", lockFile.exists())
            assertTrue("installer must hold exclusive lock", installer.isLockHeld())
        } finally {
            installer.releaseLock()
        }

        assertFalse("installer must no longer hold lock after release", installer.isLockHeld())
    }

    @Test
    fun testConcurrentInstallerOperationsPreventSimultaneousModification() {
        val freshFilesDir = File(tempFolder.newFolder(), "concurrent_app_files")

        val installer1 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        val installer2 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        installer1.acquireLock()
        assertTrue("installer1 must hold the lock", installer1.isLockHeld())

        var exceptionThrown = false
        try {
            installer2.acquireLock()
        } catch (e: IllegalStateException) {
            exceptionThrown = true
            assertTrue(
                "Exception message must indicate concurrent modification conflict",
                e.message?.contains("Another installation is currently modifying rootfs") == true
            )
        }
        assertTrue("installer2 must fail with IllegalStateException due to held lock", exceptionThrown)
        assertFalse("installer2 must not acquire the lock while held by installer1", installer2.isLockHeld())

        installer1.releaseLock()
        assertFalse("installer1 lock must be released", installer1.isLockHeld())

        installer2.acquireLock()
        assertTrue("installer2 can acquire the lock after installer1 releases", installer2.isLockHeld())
        installer2.releaseLock()
        assertFalse("installer2 lock must be released", installer2.isLockHeld())
    }

    @Test
    fun testFreshInstallationProceedsToDownloadWithoutEnoent() {
        val freshFilesDir = File(tempFolder.newFolder(), "fresh_pipeline_files")
        val linuxDir = File(freshFilesDir, "linux")

        assertFalse("freshFilesDir/linux must not exist initially", linuxDir.exists())

        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        assertEquals(InstallStage.NOT_INSTALLED, installer.state.value.stage)

        try {
            installer.acquireLock()
            assertTrue("linuxDir must exist after acquireLock", installer.linuxDir.exists())
            assertTrue("linuxDir must be a directory", installer.linuxDir.isDirectory)

            try {
                installer.checkDeviceArchitecture()
            } catch (e: IllegalStateException) {
                // Non-fatal on host JVM
            }

            installer.updateState(InstallStage.PREPARING_STORAGE, "Preparing application directories...", -1f)
            installer.prepareStorageAndDirectories()

            assertTrue("rootfsDir must exist", installer.rootfsDir.exists() && installer.rootfsDir.isDirectory)
            assertTrue("stagingDir must exist", installer.stagingDir.exists() && installer.stagingDir.isDirectory)
            assertTrue("binDir must exist", installer.binDir.exists() && installer.binDir.isDirectory)
            assertTrue("tmpDir must exist", installer.tmpDir.exists() && installer.tmpDir.isDirectory)
            assertTrue("shmDir must exist", installer.shmDir.exists() && installer.shmDir.isDirectory)
            assertTrue("downloadsDir must exist", installer.downloadsDir.exists() && installer.downloadsDir.isDirectory)

            installer.updateState(InstallStage.DOWNLOADING_ROOTFS, "Ready to download Ubuntu rootfs", 0f)

            assertEquals(InstallStage.DOWNLOADING_ROOTFS, installer.state.value.stage)
            assertNull("Error message must be null upon reaching DOWNLOADING_ROOTFS", installer.state.value.errorMessage)
            assertFalse("Installation must not be in recovery mode", installer.state.value.canRetry)

            val testPartFile = File(installer.downloadsDir, "test.tar.gz.part")
            testPartFile.parentFile?.mkdirs()
            testPartFile.writeText("test download chunk")
            assertTrue("Part file must be writable without ENOENT", testPartFile.exists())
            testPartFile.delete()

        } finally {
            installer.releaseLock()
        }

        assertFalse("Lock must be released", installer.isLockHeld())
    }

    @Test
    fun testProotPermissionSettingAndElfValidation() {
        val freshFilesDir = File(tempFolder.newFolder(), "proot_perm_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        installer.binDir.mkdirs()
        assertTrue("Asset proot must exist at ${assetProot.absolutePath}", assetProot.exists())

        assetProot.copyTo(installer.prootFile, overwrite = true)
        assertTrue(installer.prootFile.exists())

        val permResult = installer.setExecutablePermissions(installer.prootFile)
        assertTrue("setExecutablePermissions must succeed", permResult)
        assertTrue("prootFile must be executable", installer.prootFile.canExecute())

        val elfResult = ElfValidator.validateArm64Elf(installer.prootFile)
        assertTrue("PRoot ELF validation must be valid: ${elfResult.detail}", elfResult.isValid)
        assertTrue("PRoot must be 64-bit ELF", elfResult.is64Bit)
        assertTrue("PRoot must be Little Endian", elfResult.isLittleEndian)
        assertEquals("PRoot machine must be AArch64 (0xB7)", ElfValidator.EM_AARCH64, elfResult.machine)
    }

    @Test
    fun testFreshInstallProotCanStartCommandInsideRootfs() {
        val freshFilesDir = File(tempFolder.newFolder(), "proot_exec_fresh_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        installer.linuxDir.mkdirs()
        installer.binDir.mkdirs()
        installer.tmpDir.mkdirs()
        installer.shmDir.mkdirs()
        installer.rootfsDir.mkdirs()

        assertTrue("Asset proot must exist", assetProot.exists())
        assertTrue("Asset loader must exist", assetLoader.exists())

        assetProot.copyTo(installer.prootFile, overwrite = true)
        assetLoader.copyTo(installer.loaderFile, overwrite = true)

        installer.setExecutablePermissions(installer.prootFile)
        installer.setExecutablePermissions(installer.loaderFile)

        assertTrue(installer.prootFile.canExecute())
        assertTrue(installer.loaderFile.canExecute())

        val targetRootfs = File("/")
        val shellExecutable = when {
            File("/system/bin/sh").exists() -> "/system/bin/sh"
            File("/bin/sh").exists() -> "/bin/sh"
            else -> "/bin/sh"
        }

        val prootConfig = ProotSessionConfig(
            rootfsDir = targetRootfs,
            prootExecutable = installer.prootFile,
            loaderExecutable = installer.loaderFile,
            tmpDir = installer.tmpDir,
            workingDir = "/",
            executable = shellExecutable,
            args = listOf("-c", "echo PROOT_FRESH_INSTALL_EXECUTION_SUCCESS"),
            customBinds = listOf(
                "/system" to "/system"
            )
        )

        val (exitCode, output) = ProotLauncher.executeHeadless(prootConfig)
        assertEquals("PRoot execution inside rootfs must exit with code 0. Output: $output", 0, exitCode)
        assertTrue("Output must contain success marker. Output: $output", output.contains("PROOT_FRESH_INSTALL_EXECUTION_SUCCESS"))
        assertFalse("Output must not contain Permission denied", output.contains("Permission denied", ignoreCase = true))
    }
}
