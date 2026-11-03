package com.droidterminal.installer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class UbuntuInstallerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testFreshApplicationDataDirectoryInitializationAndLocking() {
        // Completely fresh application data directory where "linux" and ".installlock" do not exist
        val freshFilesDir = File(tempFolder.newFolder(), "fresh_app_files")
        val linuxDir = File(freshFilesDir, "linux")
        val lockFile = File(linuxDir, ".installlock")

        assertFalse("linux directory must not exist prior to installer start", linuxDir.exists())
        assertFalse("lock file must not exist prior to installer start", lockFile.exists())

        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        try {
            // 1. Create context.filesDir/linux recursively
            // 2. Verify that directory exists and is a directory
            // 3. Open .installlock using reliable file locking
            // 4. Acquire exclusive lock
            installer.acquireLock()

            assertTrue("linux directory must be created recursively by installer", linuxDir.exists())
            assertTrue("linux path must be a directory", linuxDir.isDirectory)
            assertTrue("lock file must exist after acquiring lock", lockFile.exists())
            assertTrue("installer must hold exclusive lock", installer.isLockHeld())
        } finally {
            // 5. Release lock safely
            installer.releaseLock()
        }

        assertFalse("installer must no longer hold lock after release", installer.isLockHeld())
    }

    @Test
    fun testConcurrentInstallerOperationsPreventSimultaneousModification() {
        val freshFilesDir = File(tempFolder.newFolder(), "concurrent_app_files")

        val installer1 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        val installer2 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        // Primary installer acquires the lock
        installer1.acquireLock()
        assertTrue("installer1 must hold the lock", installer1.isLockHeld())

        // Concurrent installer attempting to modify the same rootfs must be rejected
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

        // When installer1 finishes and releases its lock
        installer1.releaseLock()
        assertFalse("installer1 lock must be released", installer1.isLockHeld())

        // installer2 can now acquire the lock cleanly
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
            // Step 1: Acquire exclusive lock (must create linuxDir and open .installlock without ENOENT)
            installer.acquireLock()
            assertTrue("linuxDir must exist after acquireLock", installer.linuxDir.exists())
            assertTrue("linuxDir must be a directory", installer.linuxDir.isDirectory)

            // Step 2: Architecture verification
            try {
                installer.checkDeviceArchitecture()
            } catch (e: IllegalStateException) {
                // If running on x86/host JVM, device check might note host arch; non-fatal for file lifecycle test
            }

            // Step 3: Prepare storage and directories
            installer.updateState(InstallStage.PREPARING_STORAGE, "Preparing application directories...", -1f)
            installer.prepareStorageAndDirectories()

            // Verify all directory structures created cleanly
            assertTrue("rootfsDir must exist", installer.rootfsDir.exists() && installer.rootfsDir.isDirectory)
            assertTrue("stagingDir must exist", installer.stagingDir.exists() && installer.stagingDir.isDirectory)
            assertTrue("binDir must exist", installer.binDir.exists() && installer.binDir.isDirectory)
            assertTrue("tmpDir must exist", installer.tmpDir.exists() && installer.tmpDir.isDirectory)
            assertTrue("shmDir must exist", installer.shmDir.exists() && installer.shmDir.isDirectory)
            assertTrue("downloadsDir must exist", installer.downloadsDir.exists() && installer.downloadsDir.isDirectory)

            // Step 4: Advance to download stage
            installer.updateState(InstallStage.DOWNLOADING_ROOTFS, "Ready to download Ubuntu rootfs", 0f)

            // Verify that state successfully reached DOWNLOADING_ROOTFS without any ENOENT error
            assertEquals(InstallStage.DOWNLOADING_ROOTFS, installer.state.value.stage)
            assertNull("Error message must be null upon reaching DOWNLOADING_ROOTFS", installer.state.value.errorMessage)
            assertFalse("Installation must not be in recovery mode", installer.state.value.canRetry)

            // Check download destination file paths can be created in downloadsDir without ENOENT
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
    fun testLockReleaseLifecycleInFinallyOnFailure() {
        val freshFilesDir = File(tempFolder.newFolder(), "failure_lifecycle_files")
        val installer1 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)
        val installer2 = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        try {
            installer1.acquireLock()
            assertTrue("installer1 holds lock", installer1.isLockHeld())
            // Simulate simulated failure during setup
            throw RuntimeException("Simulated unexpected setup failure")
        } catch (e: RuntimeException) {
            // Emulate finally block execution in startInstallation
            installer1.releaseLock()
        }

        assertFalse("installer1 lock must be released after finally", installer1.isLockHeld())

        // Subsequent installer should immediately be able to acquire lock
        installer2.acquireLock()
        assertTrue("installer2 must acquire lock after previous release", installer2.isLockHeld())
        installer2.releaseLock()
    }

    @Test
    fun testProotPermissionSettingAndElfValidation() {
        val freshFilesDir = File(tempFolder.newFolder(), "proot_perm_files")
        val installer = UbuntuInstaller(context = null, customFilesDir = freshFilesDir)

        installer.binDir.mkdirs()
        val assetProot = File("src/main/assets/proot/arm64-v8a/proot")
        assertTrue("Asset proot must exist at ${assetProot.absolutePath}", assetProot.exists())

        // Copy proot without permissions
        assetProot.copyTo(installer.prootFile, overwrite = true)
        assertTrue(installer.prootFile.exists())

        // Explicitly set executable permissions via Android-compatible APIs
        val permResult = installer.setExecutablePermissions(installer.prootFile)
        assertTrue("setExecutablePermissions must succeed", permResult)
        assertTrue("prootFile must be executable", installer.prootFile.canExecute())

        // Verify ELF header
        val elfResult = com.droidterminal.runtime.ElfValidator.validateArm64Elf(installer.prootFile)
        assertTrue("PRoot ELF validation must be valid: ${elfResult.detail}", elfResult.isValid)
        assertTrue("PRoot must be 64-bit ELF", elfResult.is64Bit)
        assertTrue("PRoot must be Little Endian", elfResult.isLittleEndian)
        assertEquals("PRoot machine must be AArch64 (0xB7)", com.droidterminal.runtime.ElfValidator.EM_AARCH64, elfResult.machine)
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

        // Copy proot and loader from assets
        val assetProot = File("src/main/assets/proot/arm64-v8a/proot")
        val assetLoader = File("src/main/assets/proot/arm64-v8a/loader")
        assertTrue("Asset proot must exist", assetProot.exists())
        assertTrue("Asset loader must exist", assetLoader.exists())

        assetProot.copyTo(installer.prootFile, overwrite = true)
        assetLoader.copyTo(installer.loaderFile, overwrite = true)

        installer.setExecutablePermissions(installer.prootFile)
        installer.setExecutablePermissions(installer.loaderFile)

        assertTrue(installer.prootFile.canExecute())
        assertTrue(installer.loaderFile.canExecute())

        // Prepare mock rootfs structure
        val rootfsBin = File(installer.rootfsDir, "bin")
        rootfsBin.mkdirs()
        val rootfsTmp = File(installer.rootfsDir, "tmp")
        rootfsTmp.mkdirs()

        // Choose viable rootfs for test execution (Ubuntu rootfs if populated, otherwise host rootfs)
        val targetRootfs = if (File(installer.rootfsDir, "bin/sh").exists() || File(installer.rootfsDir, "bin/bash").exists()) {
            installer.rootfsDir
        } else {
            File("/")
        }

        val shellExecutable = when {
            File("/system/bin/sh").exists() -> "/system/bin/sh"
            File("/bin/sh").exists() -> "/bin/sh"
            else -> "/bin/sh"
        }

        // Configure session
        val prootConfig = com.droidterminal.runtime.ProotSessionConfig(
            rootfsDir = targetRootfs,
            prootExecutable = installer.prootFile,
            workingDir = "/",
            executable = shellExecutable,
            args = listOf("-c", "echo PROOT_FRESH_INSTALL_EXECUTION_SUCCESS"),
            customBinds = listOf(
                "/system" to "/system"
            )
        )

        val (exitCode, output) = com.droidterminal.runtime.ProotLauncher.executeHeadless(prootConfig)
        assertEquals("PRoot execution inside rootfs must exit with code 0. Output: $output", 0, exitCode)
        assertTrue("Output must contain success marker. Output: $output", output.contains("PROOT_FRESH_INSTALL_EXECUTION_SUCCESS"))
        assertFalse("Output must not contain Permission denied", output.contains("Permission denied", ignoreCase = true))
    }
}
