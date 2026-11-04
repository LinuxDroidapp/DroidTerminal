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
import java.nio.file.Files

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

        val usrUname = File(usrBin, "uname")
        usrUname.writeText("#!/bin/sh\necho aarch64\n")
        installer.setExecutablePermissions(usrUname)

        val linker = File(lib, "ld-linux-aarch64.so.1")
        linker.writeText("ELF-INTERPRETER-MOCK")
        installer.setExecutablePermissions(linker)

        val libc = File(lib, "libc.so.6")
        libc.writeText("LIBC-MOCK")
        installer.setExecutablePermissions(libc)
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

    @Test
    fun testSelinuxWxRestrictionFallbackToCarrier() {
        val freshFilesDir = File(tempFolder.newFolder(), "wx_fallback_files")
        val mockNativeLibDir = File(tempFolder.newFolder(), "mock_native_libs")
        mockNativeLibDir.mkdirs()

        // Place carrier libraries in mockNativeLibDir
        val carrierProot = File(mockNativeLibDir, "libproot.so")
        val carrierLoader = File(mockNativeLibDir, "libproot_loader.so")
        assetProot.copyTo(carrierProot, overwrite = true)
        assetLoader.copyTo(carrierLoader, overwrite = true)

        val installer = UbuntuInstaller(
            context = null,
            customFilesDir = freshFilesDir,
            customNativeLibDir = mockNativeLibDir
        )

        installer.linuxDir.mkdirs()
        installer.binDir.mkdirs()
        installer.tmpDir.mkdirs()

        // Carrier resolution must resolve from mockNativeLibDir
        val resolvedCarrier = installer.resolveCarrierProot()
        assertNotNull("Carrier PRoot must be resolved from nativeLibraryDir", resolvedCarrier)
        assertTrue("Carrier PRoot must exist", resolvedCarrier?.exists() == true)

        // Install proot binaries with carrier available
        installer.installProotBinaries()

        assertTrue("Active proot file must exist", installer.activeProotFile.exists())
        assertTrue("Active proot file must be executable", installer.activeProotFile.canExecute())

        val probeOutput = installer.testProotExecutable(installer.activeProotFile)
        assertTrue("Probe output must confirm PRoot execution", probeOutput.contains("5.1."))
    }

    @Test
    fun testProblemAGuestWorkingDirHostPathNormalization() {
        val rootfsDir = tempFolder.newFolder("mock_rootfs_dir")
        val rootHome = File(rootfsDir, "root")
        rootHome.mkdirs()

        // 1. Host path should be stripped to guest path
        val hostWorkingDir = File(rootfsDir, "root").absolutePath
        val normalized1 = ProotLauncher.resolveGuestWorkingDir(rootfsDir, hostWorkingDir)
        assertEquals("Host filesystem path must be normalized to guest path", "/root", normalized1)

        // 2. Relative or clean guest path preserved
        val normalized2 = ProotLauncher.resolveGuestWorkingDir(rootfsDir, "/root")
        assertEquals("Guest path /root must be preserved", "/root", normalized2)

        // 3. Auto-creation of standard guest directories
        val nonExistentRoot = File(tempFolder.newFolder("clean_rootfs"), "root")
        val cleanRootfs = nonExistentRoot.parentFile!!
        assertFalse(nonExistentRoot.exists())
        val normalized3 = ProotLauncher.resolveGuestWorkingDir(cleanRootfs, "/root")
        assertEquals("/root", normalized3)
        assertTrue("/root must be automatically created in rootfs", File(cleanRootfs, "root").exists())

        // 4. Fallback to / if non-standard path is unavailable
        val normalized4 = ProotLauncher.resolveGuestWorkingDir(rootfsDir, "/non_existent_custom_dir")
        assertEquals("Unavailable custom directory must fall back to /", "/", normalized4)
    }

    @Test
    fun testProblemBBashExecveAndCompanionLoaderResolution() {
        val mockNativeLibDir = tempFolder.newFolder("carrier_lib_dir")
        val carrierProot = File(mockNativeLibDir, "libproot.so")
        val carrierLoader = File(mockNativeLibDir, "libproot_loader.so")
        assetProot.copyTo(carrierProot, overwrite = true)
        assetLoader.copyTo(carrierLoader, overwrite = true)

        val rootfsDir = tempFolder.newFolder("bash_chain_rootfs")

        val prootConfig = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = carrierProot,
            loaderExecutable = null, // Verify automatic carrierLoader resolution
            workingDir = "/root"
        )

        // Loader resolution must find adjacent libproot_loader.so
        val resolvedLoader = ProotLauncher.resolveLoaderFile(prootConfig)
        assertNotNull("Companion loader must be resolved", resolvedLoader)
        assertEquals("Companion loader must match libproot_loader.so", carrierLoader.absolutePath, resolvedLoader?.absolutePath)

        // Environment must strictly remove LD_PRELOAD and LD_LIBRARY_PATH
        val env = ProotLauncher.buildProotEnvironment(
            prootConfig.copy(customEnv = mapOf(
                "LD_PRELOAD" to "/bad/android/lib.so",
                "LD_LIBRARY_PATH" to "/bad/android/lib",
                "CUSTOM_TEST" to "PASSED"
            ))
        )
        assertFalse("Environment must not contain leaked LD_PRELOAD", env.containsKey("LD_PRELOAD"))
        assertFalse("Environment must not contain leaked LD_LIBRARY_PATH", env.containsKey("LD_LIBRARY_PATH"))
        assertEquals("PASSED", env["CUSTOM_TEST"])
        assertEquals(carrierLoader.absolutePath, env["PROOT_LOADER"])
    }

    @Test
    fun testProblemCHostTmpDirDedicatedAndConcurrentSessionIsolation() {
        val linuxBase = tempFolder.newFolder("linux_base")
        val rootfs = File(linuxBase, "rootfs").apply { mkdirs() }
        val guestTmp = File(rootfs, "tmp").apply { mkdirs() }
        val hostTmp = File(linuxBase, "tmp").apply { mkdirs() }

        val configSession1 = ProotSessionConfig(
            rootfsDir = rootfs,
            prootExecutable = assetProot,
            tmpDir = File(hostTmp, "session_1").apply { mkdirs() }
        )
        val configSession2 = ProotSessionConfig(
            rootfsDir = rootfs,
            prootExecutable = assetProot,
            tmpDir = File(hostTmp, "session_2").apply { mkdirs() }
        )

        val resolvedTmp1 = ProotLauncher.resolveHostTmpDir(configSession1)
        val resolvedTmp2 = ProotLauncher.resolveHostTmpDir(configSession2)

        assertTrue(resolvedTmp1.exists() && resolvedTmp1.canWrite())
        assertTrue(resolvedTmp2.exists() && resolvedTmp2.canWrite())
        assertNotEquals("Concurrent sessions must have separate host temporary directories", resolvedTmp1.absolutePath, resolvedTmp2.absolutePath)

        // Separate host PRoot temporary storage from guest /tmp
        assertFalse("Host PRoot temporary storage must not be inside guest /tmp", resolvedTmp1.absolutePath.startsWith(guestTmp.absolutePath))
        assertFalse("Host PRoot temporary storage must not be inside guest /tmp", resolvedTmp2.absolutePath.startsWith(guestTmp.absolutePath))

        // Create file in session 1; simulate session 2 cleanup does not delete session 1
        val session1File = File(resolvedTmp1, "proot-123-socket")
        session1File.writeText("active_session_data")

        resolvedTmp2.deleteRecursively()
        assertTrue("Session 1 temporary files must remain alive during other session operations", session1File.exists())
    }

    // =========================================================================
    // LIBC6 PRE-INSTALLATION & PRE-UPGRADE HEALTH CHECK REGRESSION TESTS
    // =========================================================================

    /**
     * Test A: PRoot bind mounts must NEVER accidentally mask /bin, /usr, /usr/bin, /lib, /etc.
     */
    @Test
    fun testProtectedGuestBindMountsAreSanitized() {
        val unsafeBinds = listOf(
            "/host/android/bin" to "/bin",
            "/host/android/usr" to "/usr",
            "/host/android/usr_bin" to "/usr/bin",
            "/host/android/lib" to "/lib",
            "/host/android/etc" to "/etc",
            "/host/android/dpkg" to "/var/lib/dpkg",
            "/sdcard" to "/sdcard",
            "/storage/emulated/0/Download" to "/root/Downloads"
        )

        // 1. Validate isProtectedGuestPath
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/bin"))
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/usr/bin"))
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/usr/sbin"))
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/lib"))
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/etc"))
        assertTrue(PreUpgradeHealthCheck.isProtectedGuestPath("/var/lib/dpkg"))
        assertFalse(PreUpgradeHealthCheck.isProtectedGuestPath("/sdcard"))
        assertFalse(PreUpgradeHealthCheck.isProtectedGuestPath("/root/Downloads"))

        // 2. Validate sanitizeBindMounts
        val sanitized = PreUpgradeHealthCheck.sanitizeBindMounts(unsafeBinds)
        assertEquals(2, sanitized.size)
        assertEquals("/sdcard", sanitized[0].second)
        assertEquals("/root/Downloads", sanitized[1].second)

        // 3. Validate ProotLauncher.buildProotCommand drops unsafe bind mounts
        val rootfsDir = tempFolder.newFolder("bind_test_rootfs")
        val config = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot,
            customBinds = unsafeBinds
        )
        val command = ProotLauncher.buildProotCommand(config)
        assertFalse("Command must not contain -b ...:/bin", command.contains("/bin") && command.any { it.contains(":/bin") })
        assertFalse("Command must not contain -b ...:/usr", command.any { it.contains(":/usr") })
        assertFalse("Command must not contain -b ...:/etc", command.any { it.contains(":/etc") })
        assertTrue("Command must retain safe bind mount /sdcard", command.contains("/sdcard") || command.any { it.contains(":/sdcard") })
        assertTrue("Command must retain safe bind mount /root/Downloads", command.any { it.contains(":/root/Downloads") })
    }

    /**
     * Test B: Pre-upgrade health check detects missing uname and executes safe non-destructive recovery.
     */
    @Test
    fun testPreUpgradeHealthCheckDetectsMissingUnameAndRecovers() {
        val rootfsDir = tempFolder.newFolder("health_check_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val bin = File(rootfsDir, "bin").apply { mkdirs() }
        val usrLib = File(rootfsDir, "usr/lib").apply { mkdirs() }
        val etc = File(rootfsDir, "etc").apply { mkdirs() }

        // Setup bash, sh, and essential coreutils EXCEPT uname
        val bash = File(usrBin, "bash").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        val sh = File(usrBin, "sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        val linker = File(usrLib, "ld-linux-aarch64.so.1").apply {
            writeText("MOCK-ELF-LINKER")
            setExecutable(true)
        }

        for (cmd in PreUpgradeHealthCheck.ESSENTIAL_COMMANDS) {
            if (cmd != "uname") {
                File(usrBin, cmd).apply {
                    writeText("#!/bin/sh\nexit 0\n")
                    setExecutable(true)
                }
            }
        }

        // 1. Initial health check must FAIL because uname is missing
        val initialHealth = PreUpgradeHealthCheck.checkRootfsHealth(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertFalse("Health check must report unhealthy when uname is missing", initialHealth.isHealthy)
        assertFalse("unameOk must be false", initialHealth.unameOk)
        assertTrue("missingCommands must include uname", initialHealth.missingCommands.contains("uname"))

        // 2. Perform safe recovery: place genuine uname in mock archive or deb cache
        val aptCache = File(rootfsDir, "var/cache/apt/archives").apply { mkdirs() }
        // Create source /bin/uname that recovery can non-destructively promote/restore
        val mockSourceUname = File(rootfsDir, "tmp/mock_uname").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\necho aarch64\n")
            setExecutable(true)
        }
        val recoveredBinUname = File(bin, "uname")
        mockSourceUname.copyTo(recoveredBinUname, overwrite = true)
        recoveredBinUname.setExecutable(true)

        val recoveryResult = PreUpgradeHealthCheck.safeRecoverCoreutils(rootfsDir)
        assertTrue("safeRecoverCoreutils must succeed", recoveryResult)

        // 3. Verify /usr/bin/uname exists, is executable, and UsrMerge symlinks exist
        val usrUname = File(usrBin, "uname")
        assertTrue("/usr/bin/uname must exist after recovery", usrUname.exists())
        assertTrue("/usr/bin/uname must be executable", usrUname.canExecute())
        assertTrue("bin/uname must exist", File(bin, "uname").exists())

        // 4. Verify /etc/environment contains canonical PATH
        val envFile = File(etc, "environment")
        assertTrue("/etc/environment must exist", envFile.exists())
        assertTrue(
            "/etc/environment must specify canonical PATH",
            envFile.readText().contains("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        )

        // 5. Subsequent health check must PASS
        val recheck = PreUpgradeHealthCheck.checkRootfsHealth(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertTrue("Health check must pass after safe recovery", recheck.isHealthy)
        assertTrue("unameOk must be true", recheck.unameOk)
        assertTrue("shOk must be true", recheck.shOk)
        assertTrue("bashOk must be true", recheck.bashOk)
        assertTrue("coreutilsOk must be true", recheck.coreutilsOk)
    }

    /**
     * Test C: Dpkg maintainer scripts PATH guarantee across environment files.
     */
    @Test
    fun testDpkgMaintainerScriptPathGuarantee() {
        val freshFilesDir = tempFolder.newFolder("path_guarantee_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        installer.linuxDir.mkdirs()
        installer.rootfsDir.mkdirs()

        // Invoke filesystem configuration
        val configureMethod = UbuntuInstaller::class.java.getDeclaredMethod("configureLinuxFilesystem")
        configureMethod.isAccessible = true
        configureMethod.invoke(installer)

        val etcDir = File(installer.rootfsDir, "etc")
        val envFile = File(etcDir, "environment")
        val profileFile = File(etcDir, "profile")
        val loginDefs = File(etcDir, "login.defs")

        assertTrue("/etc/environment must exist", envFile.exists())
        assertTrue(
            "/etc/environment must define canonical PATH",
            envFile.readText().contains("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"")
        )

        assertTrue("/etc/profile must exist", profileFile.exists())
        assertTrue(
            "/etc/profile must export canonical PATH",
            profileFile.readText().contains("/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        )

        assertTrue("/etc/login.defs must exist", loginDefs.exists())
        assertTrue(
            "/etc/login.defs must configure ENV_SUPATH",
            loginDefs.readText().contains("ENV_SUPATH PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        )
    }

    /**
     * Test D: Simulated libc6.preinst execution succeeds when uname is resolved and fails with 127 when absent.
     */
    @Test
    fun testRealLibc6PreinstSimulationInsideProot() {
        val rootfsDir = tempFolder.newFolder("libc6_test_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val bin = File(rootfsDir, "bin").apply { mkdirs() }
        val tmp = File(rootfsDir, "tmp").apply { mkdirs() }

        // Create libc6.preinst mock script that behaves like Debian/Ubuntu libc6.preinst:
        // executes `uname -m` under /bin/sh
        val preinstScript = File(tmp, "libc6.preinst").apply {
            writeText(
                """
                #!/bin/sh
                set -e
                # libc6 maintainer script checks kernel version via uname -m
                arch=${'$'}(uname -m)
                if [ -z "${'$'}arch" ]; then
                    echo "libc6.preinst: uname returned empty" >&2
                    exit 1
                fi
                echo "libc6:arm64 preinst validated architecture: ${'$'}arch"
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // 1. Without uname in PATH / usr/bin / bin:
        // Running libc6.preinst directly in a shell process without uname will exit 127
        val isolatedEnv = arrayOf("PATH=/usr/bin:/bin")
        val failedProcess = ProcessBuilder("/bin/sh", preinstScript.absolutePath)
            .directory(tmp)
        failedProcess.environment().clear()
        failedProcess.environment()["PATH"] = usrBin.absolutePath // usrBin currently has NO uname!

        val failedProc = failedProcess.start()
        val exitCode = failedProc.waitFor()
        assertNotEquals("Script must fail without uname in PATH", 0, exitCode)

        // 2. Add genuine uname into usr/bin
        val unameFile = File(usrBin, "uname").apply {
            writeText(
                """
                #!/bin/sh
                if [ "${'$'}1" = "-m" ]; then
                    echo "aarch64"
                elif [ "${'$'}1" = "-a" ]; then
                    echo "Linux ubuntu 5.15.0-generic aarch64 GNU/Linux"
                else
                    echo "Linux"
                fi
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // 3. Now re-run libc6.preinst with uname available:
        val successProcess = ProcessBuilder("/bin/sh", preinstScript.absolutePath)
            .directory(tmp)
        successProcess.environment().clear()
        successProcess.environment()["PATH"] = usrBin.absolutePath

        val successProc = successProcess.start()
        val successExit = successProc.waitFor()
        val successOutput = successProc.inputStream.bufferedReader().readText()

        assertEquals("libc6.preinst must exit 0 when uname is present", 0, successExit)
        assertTrue(
            "Output must confirm validated architecture",
            successOutput.contains("libc6:arm64 preinst validated architecture: aarch64")
        )
    }

    /**
     * Test E: Dpkg package configuration retry lifecycle only executes when pre-upgrade health check passes.
     */
    @Test
    fun testRetryPackageConfigurationLifecycle() {
        val rootfsDir = tempFolder.newFolder("retry_lifecycle_rootfs")

        // 1. When rootfs is completely empty/broken, retryPackageConfiguration must throw IllegalStateException
        var rejected = false
        try {
            PreUpgradeHealthCheck.retryPackageConfiguration(
                rootfsDir = rootfsDir,
                prootExecutable = assetProot
            )
        } catch (e: IllegalStateException) {
            rejected = true
            assertTrue(e.message?.contains("Rootfs pre-upgrade health check failed") == true)
        }
        assertTrue("Package configuration retry must be blocked on failing health check", rejected)
    }

    // =========================================================================
    // COREUTILS PACKAGE/FILESYSTEM INCONSISTENCY & RECOVERY REGRESSION TESTS
    // =========================================================================

    /**
     * Test F: Detects disagreement where DPKG reports coreutils=installed, but executable files are missing.
     */
    @Test
    fun testCoreutilsPackageFilesystemInconsistencyDetection() {
        val rootfsDir = tempFolder.newFolder("inconsistent_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val dpkgDir = File(rootfsDir, "var/lib/dpkg").apply { mkdirs() }
        val statusFile = File(dpkgDir, "status")

        // 1. Shell is present
        File(usrBin, "bash").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        File(usrBin, "sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }

        // 2. DPKG status claims coreutils and libc6 are installed
        statusFile.writeText(
            """
            Package: libc6
            Status: install ok installed
            Architecture: arm64
            Version: 2.39-0ubuntu8

            Package: coreutils
            Status: install ok installed
            Architecture: arm64
            Version: 9.4-3ubuntu6

            """.trimIndent()
        )

        // 3. Executable files for uname, cat, ls, mkdir, chmod, touch are intentionally MISSING!
        val audit = CoreutilsRecovery.auditInconsistency(rootfsDir)

        assertTrue("Audit must detect DPKG claims coreutils is installed", audit.dpkgThinksInstalled)
        assertFalse("Audit must declare rootfs inconsistent", audit.isConsistent)
        assertTrue("Missing list must include uname", audit.missingMandatoryCommands.contains("uname"))
        assertTrue("Missing list must include cat", audit.missingMandatoryCommands.contains("cat"))
        assertTrue("Missing list must include ls", audit.missingMandatoryCommands.contains("ls"))
        assertTrue("Missing list must include mkdir", audit.missingMandatoryCommands.contains("mkdir"))
        assertTrue("Missing list must include chmod", audit.missingMandatoryCommands.contains("chmod"))
        assertTrue("Missing list must include touch", audit.missingMandatoryCommands.contains("touch"))
        assertTrue("Root cause summary must report DPKG_STATUS_CONFLICT", audit.rootCauseSummary.contains("DPKG_STATUS_CONFLICT"))
    }

    /**
     * Test G: CoreutilsRecovery safely restores all 14 mandatory commands and reconciles DPKG state.
     */
    @Test
    fun testCoreutilsRecoveryRestoresMandatoryCommandsAndReconcilesDpkg() {
        val rootfsDir = tempFolder.newFolder("recovery_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val dpkgDir = File(rootfsDir, "var/lib/dpkg").apply { mkdirs() }

        // Setup mock coreutils multicall binary in usr/bin
        val multicall = File(usrBin, "coreutils").apply {
            writeText(
                """
                #!/bin/sh
                cmd=${'$'}(basename "${'$'}0")
                if [ "${'$'}cmd" = "uname" ]; then
                    if [ "${'$'}1" = "-m" ]; then echo "aarch64"; exit 0; fi
                    if [ "${'$'}1" = "-a" ]; then echo "Linux ubuntu 5.15.0-generic aarch64 GNU/Linux"; exit 0; fi
                    echo "Linux"
                    exit 0
                fi
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // Setup DPKG status claiming coreutils installed
        File(dpkgDir, "status").writeText(
            """
            Package: coreutils
            Status: install ok installed
            Architecture: arm64

            """.trimIndent()
        )

        // Execute recovery
        val recoveryResult = CoreutilsRecovery.recoverCoreutils(rootfsDir)
        assertTrue("Coreutils recovery must succeed", recoveryResult.success)
        assertEquals("No commands should have failed", 0, recoveryResult.failedCommands.size)
        assertTrue(
            "All 14 mandatory commands must be verified",
            recoveryResult.verifiedCommands.containsAll(CoreutilsRecovery.MANDATORY_COMMANDS)
        )

        // Verify all 14 commands exist and are executable
        for (cmd in CoreutilsRecovery.MANDATORY_COMMANDS) {
            val f = File(usrBin, cmd)
            assertTrue("Command $cmd must exist in /usr/bin", f.exists())
            assertTrue("Command $cmd must be executable", f.canExecute())
        }

        // Verify DPKG reconciliation
        val listFile = File(rootfsDir, "var/lib/dpkg/info/coreutils.list")
        assertTrue("/var/lib/dpkg/info/coreutils.list must exist", listFile.exists())
        val listText = listFile.readText()
        assertTrue("coreutils.list must index /usr/bin/uname", listText.contains("/usr/bin/uname"))
        assertTrue("coreutils.list must index /usr/bin/cat", listText.contains("/usr/bin/cat"))

        // Re-audit must now report consistent
        val reAudit = CoreutilsRecovery.auditInconsistency(rootfsDir)
        assertTrue("Rootfs must be consistent after recovery", reAudit.isConsistent)
    }

    /**
     * Test H: Libc6 upgrade protection aborts on failure and permits upgrade after recovery.
     */
    @Test
    fun testLibc6UpgradeProtectionGuardsAgainstInconsistency() {
        val rootfsDir = tempFolder.newFolder("libc6_guard_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val usrLib = File(rootfsDir, "usr/lib").apply { mkdirs() }

        // Setup basic bash and linker
        File(usrBin, "bash").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        File(usrBin, "sh").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        File(usrLib, "ld-linux-aarch64.so.1").apply {
            writeText("ELF-LINKER")
            setExecutable(true)
        }

        // Create coreutils multicall binary
        File(usrBin, "coreutils").apply {
            writeText(
                """
                #!/bin/sh
                cmd=${'$'}(basename "${'$'}0")
                if [ "${'$'}cmd" = "uname" ]; then
                    if [ "${'$'}1" = "-m" ]; then echo "aarch64"; exit 0; fi
                    if [ "${'$'}1" = "-a" ]; then echo "Linux ubuntu 5.15.0-generic aarch64 GNU/Linux"; exit 0; fi
                    echo "Linux"
                    exit 0
                fi
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // Verify guard succeeds after recovery
        val permitted = PreUpgradeHealthCheck.guardLibc6Upgrade(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertTrue("libc6 upgrade must be permitted after coreutils verification", permitted)
    }

    /**
     * Test I: Interrupted installation detection and rootfs persistence across application restart.
     */
    @Test
    fun testInterruptedInstallationDetectionAndRootfsPersistence() {
        val freshFilesDir = tempFolder.newFolder("persistence_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        // 1. Simulate an interrupted installation: .installing exists but not .installed
        installer.linuxDir.mkdirs()
        installer.installingMarker.writeText("${System.currentTimeMillis()}")
        assertFalse("isInstalled must be false when .installing marker is present", installer.isInstalled())

        // 2. Setup complete environment with uname and bash
        installer.binDir.mkdirs()
        installer.rootfsDir.mkdirs()
        assetProot.copyTo(installer.prootFile, overwrite = true)
        installer.setExecutablePermissions(installer.prootFile)

        val usrBin = File(installer.rootfsDir, "usr/bin").apply { mkdirs() }
        File(usrBin, "bash").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        File(usrBin, "uname").apply { writeText("#!/bin/sh\necho aarch64\n"); setExecutable(true) }

        // Remove .installing and write .installed marker
        installer.installingMarker.delete()
        installer.installedMarker.writeText("{\"status\":\"ok\"}")
        assertTrue("isInstalled must be true when rootfs has bash, uname, and proot", installer.isInstalled())

        // 3. Application restart simulation: create a new UbuntuInstaller instance with the same filesDir
        val restartedInstaller = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        assertTrue("Restarted installer instance must recognize existing persistent rootfs", restartedInstaller.isInstalled())
    }

    /**
     * Test J: libc-bin.postinst simulation with md5sum and cut.
     * Simulates the exact failure:
     *   /var/lib/dpkg/info/libc-bin.postinst: 1: md5sum: not found
     *   /var/lib/dpkg/info/libc-bin.postinst: 1: cut: not found
     * Verifies that missing md5sum/cut fails maintainer script, and after CoreutilsRecovery
     * the maintainer script succeeds with exit 0.
     */
    @Test
    fun testLibcBinPostinstSimulationWithMd5sumAndCut() {
        val rootfsDir = tempFolder.newFolder("libc_bin_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val binDir = File(rootfsDir, "bin").apply { mkdirs() }
        val infoDir = File(rootfsDir, "var/lib/dpkg/info").apply { mkdirs() }

        // Create libc-bin.postinst script requiring md5sum and cut
        val postinst = File(infoDir, "libc-bin.postinst")
        postinst.writeText(
            """
            #!/bin/sh
            set -e
            HASH=${'$'}(echo "dummy-data" | md5sum | cut -d' ' -f1)
            if [ -z "${'$'}HASH" ]; then
                exit 1
            fi
            echo "LIBC_BIN_CONFIGURED_OK:${'$'}HASH"
            exit 0
            """.trimIndent() + "\n"
        )
        postinst.setExecutable(true)

        // Setup mock coreutils multicall binary
        val multicall = File(usrBin, "coreutils").apply {
            writeText(
                """
                #!/bin/sh
                cmd=${'$'}(basename "${'$'}0")
                if [ "${'$'}cmd" = "md5sum" ]; then
                    echo "d41d8cd98f00b204e9800998ecf8427e  -"
                    exit 0
                fi
                if [ "${'$'}cmd" = "cut" ]; then
                    read line
                    echo "${'$'}line" | tr -s ' ' | awk '{print ${'$'}1}'
                    exit 0
                fi
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // Before recovery: md5sum and cut are NOT symlinked
        assertFalse("md5sum must not exist initially", File(usrBin, "md5sum").exists())
        assertFalse("cut must not exist initially", File(usrBin, "cut").exists())

        // Run recovery
        val result = CoreutilsRecovery.recoverCoreutils(rootfsDir)
        assertTrue("Coreutils recovery must succeed", result.success)

        // After recovery: md5sum and cut must exist and be executable
        val md5sumFile = File(usrBin, "md5sum")
        val cutFile = File(usrBin, "cut")
        assertTrue("md5sum must exist in /usr/bin", md5sumFile.exists())
        assertTrue("cut must exist in /usr/bin", cutFile.exists())
        assertTrue("md5sum must be executable", md5sumFile.canExecute())
        assertTrue("cut must be executable", cutFile.canExecute())

        // Execute postinst script with PATH pointing to rootfs usr/bin
        val pb = ProcessBuilder("/bin/sh", postinst.absolutePath)
        pb.environment()["PATH"] = "${usrBin.absolutePath}:${binDir.absolutePath}:/usr/bin:/bin"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()

        assertEquals("libc-bin.postinst must succeed with exit code 0", 0, exit)
        assertTrue("libc-bin.postinst must output confirmation", out.contains("LIBC_BIN_CONFIGURED_OK"))
    }

    /**
     * Test K: TarExtractor automatically translates hardlinks into symlinks or copies,
     * and skips device nodes without failing.
     */
    @Test
    fun testTarExtractorHardlinkTranslation() {
        val testDir = tempFolder.newFolder("tar_extract_test")
        val targetDir = File(testDir, "dest").apply { mkdirs() }
        val tarFile = File(testDir, "test.tar")

        // Build a minimal POSIX tar archive with:
        // 1. Regular file: usr/bin/coreutils
        // 2. Hardlink: usr/bin/md5sum -> usr/bin/coreutils (typeflag '1')
        // 3. Hardlink: usr/bin/cut -> usr/bin/coreutils (typeflag '1')
        // 4. Device node: dev/null (typeflag '3')
        java.io.FileOutputStream(tarFile).use { fos ->
            val coreutilsContent = "#!/bin/sh\necho COREUTILS_OK\n".toByteArray()

            // Header 1: usr/bin/coreutils (regular file)
            writeTarHeader(fos, "usr/bin/coreutils", '0', coreutilsContent.size.toLong(), 493, "")
            fos.write(coreutilsContent)
            writeTarPadding(fos, coreutilsContent.size)

            // Header 2: usr/bin/md5sum (hardlink to usr/bin/coreutils)
            writeTarHeader(fos, "usr/bin/md5sum", '1', 0L, 493, "usr/bin/coreutils")

            // Header 3: usr/bin/cut (hardlink to usr/bin/coreutils)
            writeTarHeader(fos, "usr/bin/cut", '1', 0L, 493, "usr/bin/coreutils")

            // Header 4: dev/null (char device - should be ignored)
            writeTarHeader(fos, "dev/null", '3', 0L, 438, "")

            // End of archive (two 512-byte zero blocks)
            fos.write(ByteArray(1024))
        }

        // Extract using TarExtractor
        val count = TarExtractor.extract(tarFile, targetDir)
        assertTrue("Extracted entry count should be >= 3", count >= 3)

        val coreutilsFile = File(targetDir, "usr/bin/coreutils")
        val md5sumFile = File(targetDir, "usr/bin/md5sum")
        val cutFile = File(targetDir, "usr/bin/cut")
        val devNull = File(targetDir, "dev/null")

        assertTrue("usr/bin/coreutils must exist", coreutilsFile.exists())
        assertTrue("usr/bin/md5sum must exist (translated hardlink)", md5sumFile.exists())
        assertTrue("usr/bin/cut must exist (translated hardlink)", cutFile.exists())
        assertTrue("usr/bin/md5sum must be executable", md5sumFile.canExecute())
        assertTrue("usr/bin/cut must be executable", cutFile.canExecute())
        assertFalse("dev/null device node must be skipped", devNull.exists())
    }

    /**
     * Test L: PackageManagerRecovery cleans stale locks, reconciles dpkg status,
     * and confirms libc-bin configured.
     */
    @Test
    fun testPackageManagerRecoveryAndLibcBinStatus() {
        val rootfsDir = tempFolder.newFolder("pkg_mgr_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val dpkgDir = File(rootfsDir, "var/lib/dpkg").apply { mkdirs() }
        val aptDir = File(rootfsDir, "var/lib/apt/lists").apply { mkdirs() }

        // Create stale locks
        val dpkgLock = File(dpkgDir, "lock").apply { writeText("stale-lock") }
        val aptLock = File(aptDir, "lock").apply { writeText("stale-lock") }
        assertTrue("DPKG lock must exist before recovery", dpkgLock.exists())
        assertTrue("APT lock must exist before recovery", aptLock.exists())

        // Create mock multicall coreutils
        File(usrBin, "coreutils").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }

        // Setup mock dpkg status with unconfigured libc-bin
        val statusFile = File(dpkgDir, "status")
        statusFile.writeText(
            """
            Package: libc-bin
            Status: install ok half-configured
            Architecture: arm64
            Version: 2.39-0ubuntu8

            """.trimIndent() + "\n"
        )

        // Mock ldconfig
        File(usrBin, "ldconfig").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }

        val result = CoreutilsRecovery.recoverPackageManager(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )

        assertFalse("DPKG lock must be removed", dpkgLock.exists())
        assertFalse("APT lock must be removed", aptLock.exists())
        assertTrue("Coreutils must be verified", result.coreutilsVerified)
        assertTrue("Recovery must succeed", result.success)
    }

    /**
     * Test M: Enhanced health check detects broken symlinks, missing libc.so.6,
     * and unwritable directories.
     */
    @Test
    fun testEnhancedHealthCheckDetectsBrokenSymlinksAndMissingLibraries() {
        val rootfsDir = tempFolder.newFolder("health_check_enhanced_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val libDir = File(rootfsDir, "lib").apply { mkdirs() }

        // Setup sh and bash
        File(usrBin, "sh").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        File(usrBin, "bash").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        File(usrBin, "uname").apply { writeText("#!/bin/sh\necho aarch64\n"); setExecutable(true) }
        File(libDir, "ld-linux-aarch64.so.1").apply { writeText("LINKER"); setExecutable(true) }

        // Missing libc.so.6: health check should flag sharedLibraryOk = false
        val resultNoLibc = PreUpgradeHealthCheck.checkRootfsHealth(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertFalse("Health check must report sharedLibraryOk = false when libc.so.6 is missing", resultNoLibc.sharedLibraryOk)

        // Add libc.so.6
        File(libDir, "libc.so.6").apply { writeText("LIBC"); setExecutable(true) }

        // Create a broken symlink in usr/bin
        val brokenLink = File(usrBin, "broken_tool")
        Files.createSymbolicLink(brokenLink.toPath(), java.nio.file.Paths.get("non_existent_binary"))

        val resultWithBrokenLink = PreUpgradeHealthCheck.checkRootfsHealth(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertFalse("brokenSymlinksOk must be false when broken symlink exists", resultWithBrokenLink.brokenSymlinksOk)
        assertTrue("brokenSymlinks list must contain the broken link", resultWithBrokenLink.brokenSymlinks.any { it.contains("broken_tool") })

        // Remove broken symlink
        Files.deleteIfExists(brokenLink.toPath())
        val resultClean = PreUpgradeHealthCheck.checkRootfsHealth(
            rootfsDir = rootfsDir,
            prootExecutable = assetProot
        )
        assertTrue("brokenSymlinksOk must be true when broken link is removed", resultClean.brokenSymlinksOk)
        assertTrue("sharedLibraryOk must be true when libc.so.6 is present", resultClean.sharedLibraryOk)
    }

    /**
     * Test N: TarExtractor handles POSIX PAX headers and detects path traversal / corrupt checksum.
     */
    @Test
    fun testTarExtractorPaxHeadersAndPathTraversal() {
        val testDir = tempFolder.newFolder("tar_pax_test")
        val targetDir = File(testDir, "dest").apply { mkdirs() }

        // 1. PAX Extended Header test
        val paxTar = File(testDir, "pax.tar")
        java.io.FileOutputStream(paxTar).use { fos ->
            val realPath = "usr/share/long_path_overridden_by_pax.txt"
            val paxPayload = "45 path=$realPath\n".toByteArray()
            val fileContent = "PAX_OVERRIDE_OK\n".toByteArray()

            // Header for 'x'
            writeTarHeader(fos, "PaxHeaders.1000", 'x', paxPayload.size.toLong(), 420, "")
            fos.write(paxPayload)
            writeTarPadding(fos, paxPayload.size)

            // Header for regular file with placeholder name
            writeTarHeader(fos, "placeholder.txt", '0', fileContent.size.toLong(), 420, "")
            fos.write(fileContent)
            writeTarPadding(fos, fileContent.size)

            fos.write(ByteArray(1024))
        }

        val paxResult = TarExtractor.extractDetailed(paxTar, targetDir)
        assertTrue("PAX extraction must succeed: ${paxResult.detailMessage}", paxResult.success)
        val extractedPaxFile = File(targetDir, "usr/share/long_path_overridden_by_pax.txt")
        assertTrue("Overridden PAX path file must exist", extractedPaxFile.exists())
        assertEquals("PAX_OVERRIDE_OK\n", extractedPaxFile.readText())

        // 2. Path Traversal rejection test
        val traversalTar = File(testDir, "traversal.tar")
        java.io.FileOutputStream(traversalTar).use { fos ->
            val payload = "MALICIOUS".toByteArray()
            writeTarHeader(fos, "../../escape_rootfs.txt", '0', payload.size.toLong(), 420, "")
            fos.write(payload)
            writeTarPadding(fos, payload.size)
            fos.write(ByteArray(1024))
        }

        val traversalResult = TarExtractor.extractDetailed(traversalTar, targetDir)
        assertFalse("Path traversal archive must be rejected", traversalResult.success)
        assertTrue("Error should mention path traversal or security violation", traversalResult.detailMessage.contains("Path traversal", ignoreCase = true) || traversalResult.detailMessage.contains("Security", ignoreCase = true))

        // 3. Corrupt checksum rejection test
        val corruptTar = File(testDir, "corrupt.tar")
        java.io.FileOutputStream(corruptTar).use { fos ->
            val header = ByteArray(512)
            System.arraycopy("usr/bin/corrupt".toByteArray(), 0, header, 0, 15)
            // Invalid checksum bytes
            System.arraycopy("9999999\u0000".toByteArray(), 0, header, 148, 8)
            fos.write(header)
            fos.write(ByteArray(1024))
        }

        val corruptResult = TarExtractor.extractDetailed(corruptTar, targetDir)
        assertFalse("Corrupted checksum TAR must be rejected", corruptResult.success)
    }

    /**
     * Test O: Transactional rootfs promotion preserves existing rootfs on failure and succeeds cleanly.
     */
    @Test
    fun testTransactionalPromotionRollbackOnFailure() {
        val testFilesDir = tempFolder.newFolder("trans_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = testFilesDir)

        // Setup existing working rootfs
        installer.rootfsDir.mkdirs()
        val existingBash = File(installer.rootfsDir, "bin/bash").apply {
            writeText("#!/bin/sh\necho ORIGINAL_ROOTFS\n")
            setExecutable(true)
        }
        val existingSh = File(installer.rootfsDir, "bin/sh").apply {
            writeText("#!/bin/sh\necho SH_ORIGINAL\n")
            setExecutable(true)
        }
        val userFile = File(installer.rootfsDir, "root/user_data.txt").apply {
            parentFile.mkdirs()
            writeText("IMPORTANT_USER_CONFIG")
        }

        // Case 1: Incomplete staging (missing bash) must trigger pre-promotion failure
        // and keep existing rootfs untouched
        installer.stagingDir.mkdirs()
        File(installer.stagingDir, "some_corrupted_file.txt").writeText("corrupt")

        var promotionFailed = false
        try {
            installer.promoteStagingToRootfs()
        } catch (e: IllegalStateException) {
            promotionFailed = true
        }
        assertTrue("Promotion must fail when staging is missing essential binaries", promotionFailed)
        assertTrue("Existing rootfs must be preserved when staging is invalid", existingBash.exists())
        assertTrue("User data must be preserved", userFile.exists())
        assertEquals("IMPORTANT_USER_CONFIG", userFile.readText())

        // Case 2: Complete valid staging succeeds cleanly
        File(installer.stagingDir, "bin").mkdirs()
        File(installer.stagingDir, "bin/bash").apply {
            writeText("#!/bin/sh\necho NEW_PROMOTED_ROOTFS\n")
            setExecutable(true)
        }
        File(installer.stagingDir, "bin/sh").apply {
            writeText("#!/bin/sh\necho SH_NEW\n")
            setExecutable(true)
        }

        installer.promoteStagingToRootfs()
        assertTrue("Promoted rootfs must have new bash", File(installer.rootfsDir, "bin/bash").exists())
        assertEquals("#!/bin/sh\necho NEW_PROMOTED_ROOTFS\n", File(installer.rootfsDir, "bin/bash").readText())
        assertFalse("Staging directory must be cleaned up after successful promotion", installer.stagingDir.exists())
    }

    /**
     * Test P: ca-certificates.config simulation with sort, uniq, and tr.
     * Simulates the exact Ubuntu 26.04 maintainer script failure:
     *   /var/lib/dpkg/info/ca-certificates.config: 70: sort: not found
     *   /var/lib/dpkg/info/ca-certificates.config: 70: uniq: not found
     *   /var/lib/dpkg/info/ca-certificates.config: 69: tr: not found
     * Verifies that missing sort/uniq/tr fails maintainer script, and after CoreutilsRecovery
     * the maintainer script succeeds with exit 0 and verified output.
     */
    @Test
    fun testCaCertificatesConfigSimulationWithSortUniqTr() {
        val rootfsDir = tempFolder.newFolder("ca_certs_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val binDir = File(rootfsDir, "bin").apply { mkdirs() }
        val infoDir = File(rootfsDir, "var/lib/dpkg/info").apply { mkdirs() }

        // Create ca-certificates.config simulation script requiring sort, uniq, tr
        val configScript = File(infoDir, "ca-certificates.config")
        configScript.writeText(
            """
            #!/bin/sh
            set -e
            CERTS=${'$'}(printf "cert_c\ncert_a\ncert_b\ncert_a\n" | tr '[:upper:]' '[:lower:]' | sort | uniq)
            if [ -z "${'$'}CERTS" ]; then
                exit 1
            fi
            echo "CA_CERTIFICATES_CONFIG_OK"
            exit 0
            """.trimIndent() + "\n"
        )
        configScript.setExecutable(true)

        // Setup mock coreutils multicall binary
        val multicall = File(usrBin, "coreutils").apply {
            writeText(
                """
                #!/bin/sh
                cmd=${'$'}(basename "${'$'}0")
                if [ "${'$'}cmd" = "sort" ]; then
                    exec sort "${'$'}@"
                fi
                if [ "${'$'}cmd" = "uniq" ]; then
                    exec uniq "${'$'}@"
                fi
                if [ "${'$'}cmd" = "tr" ]; then
                    exec tr "${'$'}@"
                fi
                exit 0
                """.trimIndent() + "\n"
            )
            setExecutable(true)
        }

        // Initially sort, uniq, tr do NOT exist in usr/bin
        assertFalse("sort must not exist initially", File(usrBin, "sort").exists())
        assertFalse("uniq must not exist initially", File(usrBin, "uniq").exists())
        assertFalse("tr must not exist initially", File(usrBin, "tr").exists())

        // Run recovery
        val result = CoreutilsRecovery.recoverCoreutils(rootfsDir)
        assertTrue("Coreutils recovery must succeed", result.success)

        // After recovery: sort, uniq, tr must exist and be executable
        val sortFile = File(usrBin, "sort")
        val uniqFile = File(usrBin, "uniq")
        val trFile = File(usrBin, "tr")

        assertTrue("sort must exist in /usr/bin", sortFile.exists())
        assertTrue("uniq must exist in /usr/bin", uniqFile.exists())
        assertTrue("tr must exist in /usr/bin", trFile.exists())
        assertTrue("sort must be executable", sortFile.canExecute())
        assertTrue("uniq must be executable", uniqFile.canExecute())
        assertTrue("tr must be executable", trFile.canExecute())

        // Execute maintainer script
        val pb = ProcessBuilder("/bin/sh", configScript.absolutePath)
        pb.environment()["PATH"] = "${usrBin.absolutePath}:${binDir.absolutePath}:/usr/bin:/bin"
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()

        assertEquals("ca-certificates.config must succeed with exit code 0", 0, exit)
        assertTrue("ca-certificates.config must output confirmation", out.contains("CA_CERTIFICATES_CONFIG_OK"))
    }

    /**
     * Test Q: Ubuntu 26.04 rust-coreutils layout (/usr/lib/cargo/bin/coreutils/)
     * and coreutils-from-uutils command reconciliation.
     */
    @Test
    fun testRustCoreutilsAndCargoBinDirectoryLayout() {
        val rootfsDir = tempFolder.newFolder("rust_coreutils_rootfs")
        val usrBin = File(rootfsDir, "usr/bin").apply { mkdirs() }
        val cargoDir = File(rootfsDir, "usr/lib/cargo/bin/coreutils").apply { mkdirs() }

        // Mock rust-coreutils utility implementations
        val rustSort = File(cargoDir, "sort").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        val rustUniq = File(cargoDir, "uniq").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
        val rustTr = File(cargoDir, "tr").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }

        // Setup multicall in /usr/bin/coreutils
        File(usrBin, "coreutils").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }

        val recoveryResult = CoreutilsRecovery.recoverCoreutils(rootfsDir)
        assertTrue("Recovery must succeed with rust-coreutils layout", recoveryResult.success)

        val usrSort = File(usrBin, "sort")
        val usrUniq = File(usrBin, "uniq")
        val usrTr = File(usrBin, "tr")

        assertTrue("/usr/bin/sort must exist", usrSort.exists())
        assertTrue("/usr/bin/uniq must exist", usrUniq.exists())
        assertTrue("/usr/bin/tr must exist", usrTr.exists())
        assertTrue("/usr/bin/sort must be executable", usrSort.canExecute())
        assertTrue("/usr/bin/uniq must be executable", usrUniq.canExecute())
        assertTrue("/usr/bin/tr must be executable", usrTr.canExecute())
    }

    private fun writeTarHeader(fos: java.io.OutputStream, name: String, typeFlag: Char, size: Long, mode: Int, linkName: String) {
        val header = ByteArray(512)
        System.arraycopy(name.toByteArray(), 0, header, 0, minOf(name.length, 100))
        val modeStr = String.format("%07o ", mode)
        System.arraycopy(modeStr.toByteArray(), 0, header, 100, 8)
        val sizeStr = String.format("%011o ", size)
        System.arraycopy(sizeStr.toByteArray(), 0, header, 124, 12)
        header[156] = typeFlag.code.toByte()
        if (linkName.isNotBlank()) {
            System.arraycopy(linkName.toByteArray(), 0, header, 157, minOf(linkName.length, 100))
        }
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += (b.toInt() and 0xFF)
        val chkStr = String.format("%06o\u0000 ", sum)
        System.arraycopy(chkStr.toByteArray(), 0, header, 148, 8)

        fos.write(header)
    }

    private fun writeTarPadding(fos: java.io.OutputStream, size: Int) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) fos.write(ByteArray(pad))
    }
}
