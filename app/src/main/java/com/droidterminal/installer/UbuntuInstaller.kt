package com.droidterminal.installer

import android.content.Context
import android.os.Build
import android.os.StatFs
import com.droidterminal.runtime.ElfValidator
import com.droidterminal.runtime.NativePtyBridge
import com.droidterminal.runtime.ProotLauncher
import com.droidterminal.runtime.ProotSessionConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest

/**
 * Persistent transactional installation state machine as specified in Section 4.
 */
enum class InstallStage(val stepNumber: Int, val title: String) {
    NOT_INSTALLED(0, "Not Installed"),
    CHECKING_DEVICE(1, "Checking Device Architecture & OS"),
    PREPARING_STORAGE(2, "Preparing Storage & Directories"),
    DOWNLOADING_ROOTFS(3, "Downloading Ubuntu ARM64 Rootfs"),
    VERIFYING_ARCHIVE(4, "Verifying Archive Integrity (SHA-256)"),
    EXTRACTING_ROOTFS(5, "Extracting Ubuntu Root Filesystem"),
    PREPARING_PROOT(6, "Preparing PRoot ARM64 Runtime"),
    INITIALIZING_UBUNTU(7, "Initializing Ubuntu Rootless Userspace"),
    CONFIGURING_ENVIRONMENT(8, "Configuring APT, DNS & Shared Memory"),
    VERIFYING_INSTALLATION(9, "Verifying Shell & Package Management"),
    READY(10, "Linux Environment Ready"),
    RECOVERY_REQUIRED(-1, "Installation Failed / Recovery Required")
}

data class InstallState(
    val stage: InstallStage = InstallStage.NOT_INSTALLED,
    val detailMessage: String = "Initializing installer...",
    val progressFraction: Float = 0f, // 0.0 to 1.0, or -1f for indeterminate
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val downloadSpeedBytesPerSec: Long = 0L,
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val isComplete: Boolean = false,
    val detectedLinuxDroidPath: String? = null
)

class UbuntuInstaller(
    private val context: Context? = null,
    customFilesDir: File? = null
) {
    val filesDir: File = customFilesDir
        ?: context?.filesDir
        ?: File(System.getProperty("java.io.tmpdir"), "droidterminal_files")

    val linuxDir: File = File(filesDir, "linux")
    val rootfsDir: File = File(linuxDir, "rootfs")
    val stagingDir: File = File(linuxDir, "staging")
    val binDir: File = File(linuxDir, "bin")
    val tmpDir: File = File(linuxDir, "tmp")
    val shmDir: File = File(linuxDir, "shm")
    val downloadsDir: File = File(linuxDir, "downloads")
    val installedMarker: File = File(linuxDir, ".installed")
    val lockFile: File = File(linuxDir, ".installlock")
    val manifestFile: File = File(linuxDir, "manifest.json")
    val prootFile: File = File(binDir, "proot")
    val loaderFile: File = File(binDir, "loader")

    var activeProotFile: File = prootFile
        internal set

    private var lockRandomAccessFile: RandomAccessFile? = null
    private var lockFileChannel: FileChannel? = null
    private var fileLock: FileLock? = null

    private val _state = MutableStateFlow(InstallState())
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private var installJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val UBUNTU_ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/ubuntu-base-26.04.1-base-arm64.tar.gz"
        const val UBUNTU_ROOTFS_SHA256 = "5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd"
        const val PREFS_NAME = "droidterminal_install"
        const val KEY_INSTALLED = "ubuntu_installed"
        const val MIN_STORAGE_BYTES = 400L * 1024 * 1024 // 400 MB minimum free storage
    }

    /**
     * Checks if the Linux environment is already completely installed and healthy.
     */
    fun isInstalled(): Boolean {
        if (!installedMarker.exists()) return false
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) return false
        val bashFile = File(rootfsDir, "bin/bash")
        val usrBashFile = File(rootfsDir, "usr/bin/bash")
        if (!bashFile.exists() && !usrBashFile.exists()) return false
        if (!prootFile.exists() || !prootFile.canExecute()) return false
        return true
    }

    /**
     * Detects if an existing LinuxDroid Ubuntu environment is present on the device.
     */
    fun detectLinuxDroidInstallation(): File? {
        val candidates = listOf(
            File("/data/data/com.linuxdroid/files/rootfs"),
            File("/data/data/com.linuxdroid.app/files/rootfs"),
            File(filesDir.parentFile?.parentFile ?: File("/"), "com.linuxdroid/files/rootfs"),
            File(filesDir.parentFile?.parentFile ?: File("/"), "com.linuxdroid.app/files/rootfs")
        )
        for (candidate in candidates) {
            if (candidate.exists() && File(candidate, "bin/bash").exists()) {
                return candidate
            }
        }
        return null
    }

    /**
     * Acquires an exclusive file lock on .installlock before modifying the Linux environment.
     * Guarantees parent directory initialization and prevents concurrent installations.
     */
    @Synchronized
    internal fun acquireLock() {
        // 1. Create "context.filesDir/linux" recursively using "mkdirs()".
        if (!linuxDir.exists()) {
            val created = linuxDir.mkdirs()
            if (!created && !linuxDir.exists()) {
                throw IOException("Failed to create Linux environment directory: ${linuxDir.absolutePath}")
            }
        }

        // 2. Verify that the directory exists and is actually a directory.
        if (!linuxDir.exists() || !linuxDir.isDirectory) {
            throw IOException("Linux environment path exists but is not a directory: ${linuxDir.absolutePath}")
        }

        // 3. Open ".installlock" using a reliable file-locking mechanism.
        val raf = RandomAccessFile(lockFile, "rw")
        val channel = raf.channel

        // 4. Acquire an exclusive lock before modifying the Linux environment.
        val lock = try {
            channel.tryLock()
        } catch (e: OverlappingFileLockException) {
            null
        }

        if (lock == null) {
            try { channel.close() } catch (ignored: Exception) {}
            try { raf.close() } catch (ignored: Exception) {}
            throw IllegalStateException("Another installation is currently modifying rootfs: ${linuxDir.absolutePath}")
        }

        lockRandomAccessFile = raf
        lockFileChannel = channel
        fileLock = lock
    }

    /**
     * Safely releases the exclusive file lock and closes channel and stream.
     */
    @Synchronized
    internal fun releaseLock() {
        try {
            fileLock?.let {
                if (it.isValid) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            // Non-fatal
        } finally {
            fileLock = null
        }

        try {
            lockFileChannel?.close()
        } catch (e: Exception) {
            // Non-fatal
        } finally {
            lockFileChannel = null
        }

        try {
            lockRandomAccessFile?.close()
        } catch (e: Exception) {
            // Non-fatal
        } finally {
            lockRandomAccessFile = null
        }

        try {
            if (lockFile.exists()) {
                lockFile.delete()
            }
        } catch (e: Exception) {
            // Non-fatal
        }
    }

    /**
     * Returns true if this installer instance currently holds the installation lock.
     */
    internal fun isLockHeld(): Boolean {
        return fileLock != null && fileLock?.isValid == true
    }

    /**
     * Validates device CPU architecture for ARM64-v8a compatibility.
     */
    internal fun checkDeviceArchitecture() {
        val arch = System.getProperty("os.arch") ?: ""
        val isArm64 = arch.contains("aarch64", ignoreCase = true) ||
                arch.contains("arm64", ignoreCase = true) ||
                try {
                    Build.SUPPORTED_ABIS.any { it.contains("arm64") }
                } catch (e: Throwable) {
                    false
                }
        if (!isArm64) {
            throw IllegalStateException("DroidTerminal requires an ARM64-v8a processor. Detected architecture: $arch")
        }
    }

    /**
     * Prepares all required directories and verifies storage availability.
     */
    internal fun prepareStorageAndDirectories() {
        val availableStorage = try {
            val stat = StatFs(filesDir.absolutePath)
            val bytes = stat.availableBlocksLong * stat.blockSizeLong
            if (bytes > 0L) bytes else (filesDir.usableSpace.takeIf { it > 0L } ?: (1024L * 1024 * 1024))
        } catch (e: Throwable) {
            filesDir.usableSpace.takeIf { it > 0L } ?: (1024L * 1024 * 1024)
        }
        if (availableStorage < MIN_STORAGE_BYTES) {
            val availMb = availableStorage / (1024 * 1024)
            throw IllegalStateException("Insufficient storage. At least 400 MB required, but only $availMb MB available.")
        }

        val directories = listOf(linuxDir, rootfsDir, stagingDir, binDir, tmpDir, shmDir, downloadsDir)
        for (dir in directories) {
            if (!dir.exists()) {
                val created = dir.mkdirs()
                if (!created && !dir.exists()) {
                    throw IOException("Failed to create required directory: ${dir.absolutePath}")
                }
            }
            if (!dir.exists() || !dir.isDirectory) {
                throw IOException("Required path exists but is not a directory: ${dir.absolutePath}")
            }
        }

        // Set shm permissions to 1777
        try {
            shmDir.setReadable(true, false)
            shmDir.setWritable(true, false)
            shmDir.setExecutable(true, false)
            Runtime.getRuntime().exec(arrayOf("chmod", "1777", shmDir.absolutePath)).waitFor()
        } catch (e: Exception) {
            // Non-fatal
        }
    }

    /**
     * Starts or resumes the automated transactional installation workflow.
     */
    fun startInstallation(onComplete: () -> Unit) {
        if (installJob?.isActive == true) return

        installJob = scope.launch {
            try {
                // 1. Acquire exclusive lock before modifying environment
                acquireLock()

                // Stage 1: Check device & CPU architecture
                updateState(InstallStage.CHECKING_DEVICE, "Verifying ARM64 CPU and Android compatibility...", -1f)
                checkDeviceArchitecture()

                // Stage 2: Storage check & Directory creation
                updateState(InstallStage.PREPARING_STORAGE, "Checking storage and preparing application directories...", -1f)
                prepareStorageAndDirectories()

                // Check for existing LinuxDroid installation
                val existingLinuxDroid = detectLinuxDroidInstallation()
                if (existingLinuxDroid != null) {
                    _state.value = _state.value.copy(detectedLinuxDroidPath = existingLinuxDroid.absolutePath)
                }

                // Stage 3: Download Ubuntu rootfs
                val archiveFile = File(downloadsDir, "ubuntu-base-26.04.1-base-arm64.tar.gz")
                val partFile = File(downloadsDir, "ubuntu-base-26.04.1-base-arm64.tar.gz.part")

                val isArchiveValid = archiveFile.exists() && verifySha256(archiveFile, UBUNTU_ROOTFS_SHA256)
                if (!isArchiveValid) {
                    updateState(InstallStage.DOWNLOADING_ROOTFS, "Downloading official Ubuntu ARM64 root filesystem...", 0f)
                    downloadWithResume(UBUNTU_ROOTFS_URL, partFile, archiveFile)
                }

                // Stage 4: Verify archive integrity
                updateState(InstallStage.VERIFYING_ARCHIVE, "Verifying Ubuntu archive checksum (SHA-256)...", -1f)
                val valid = verifySha256(archiveFile, UBUNTU_ROOTFS_SHA256)
                if (!valid) {
                    archiveFile.delete()
                    partFile.delete()
                    throw IllegalStateException("Downloaded archive failed SHA-256 verification. Deleted corrupted file. Please retry.")
                }

                // Stage 5: Extract rootfs into staging directory
                updateState(InstallStage.EXTRACTING_ROOTFS, "Extracting Ubuntu filesystem into staging directory...", -1f)
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                extractArchive(archiveFile, stagingDir)

                // Validate extracted staging directory before promotion
                val stagingBash = File(stagingDir, "bin/bash")
                val stagingUsrBash = File(stagingDir, "usr/bin/bash")
                if (!stagingBash.exists() && !stagingUsrBash.exists()) {
                    throw IllegalStateException("Root filesystem extraction failed: /bin/bash not found in extracted staging directory.")
                }

                // Atomic promotion: promote staging to active rootfs
                promoteStagingToRootfs()

                // Stage 6: Prepare PRoot runtime
                updateState(InstallStage.PREPARING_PROOT, "Preparing ARM64 PRoot executable and loader...", -1f)
                installProotBinaries()

                // Stage 7: Initialize Ubuntu rootless userspace
                updateState(InstallStage.INITIALIZING_UBUNTU, "Configuring rootless APT sandboxing and policy-rc.d...", -1f)
                configureLinuxFilesystem()

                // Stage 8: Configure Environment, DNS & Shared Memory
                updateState(InstallStage.CONFIGURING_ENVIRONMENT, "Configuring DNS resolvers (1.1.1.1, 8.8.8.8) and shared memory...", -1f)
                configureDnsAndNetwork()

                // Stage 9: Verification of Linux shell & package management
                updateState(InstallStage.VERIFYING_INSTALLATION, "Verifying PRoot execution, shell, and package management...", -1f)
                verifyLinuxInstallation()

                // Stage 10: Complete and persist manifest
                writeEnvironmentManifest()
                writeCompletionMarker()

                updateState(InstallStage.READY, "Ubuntu Linux environment is ready!", 1f)
                _state.value = _state.value.copy(isComplete = true)

                withContext(Dispatchers.Main) {
                    onComplete()
                }
            } catch (e: CancellationException) {
                // Job cancelled
            } catch (e: Exception) {
                updateState(
                    stage = InstallStage.RECOVERY_REQUIRED,
                    detail = e.message ?: "Unknown installation error",
                    progress = 0f,
                    error = e.message ?: "Installation failed",
                    canRetry = true
                )
            } finally {
                // Safely release lock and close channels
                releaseLock()
            }
        }
    }

    internal fun updateState(
        stage: InstallStage,
        detail: String,
        progress: Float,
        error: String? = null,
        canRetry: Boolean = false
    ) {
        _state.value = _state.value.copy(
            stage = stage,
            detailMessage = detail,
            progressFraction = progress,
            errorMessage = error,
            canRetry = canRetry
        )
    }

    private fun promoteStagingToRootfs() {
        if (stagingDir.exists() && stagingDir.list()?.isNotEmpty() == true) {
            rootfsDir.parentFile?.let { p ->
                if (!p.exists()) p.mkdirs()
            }
            rootfsDir.deleteRecursively()
            if (!stagingDir.renameTo(rootfsDir)) {
                rootfsDir.mkdirs()
                stagingDir.copyRecursively(rootfsDir, overwrite = true)
                stagingDir.deleteRecursively()
            }
        }
    }

    /**
     * Downloads the rootfs archive with HTTP Range resumption and speed calculation.
     */
    private suspend fun downloadWithResume(sourceUrl: String, partFile: File, destFile: File) = withContext(Dispatchers.IO) {
        partFile.parentFile?.let { p ->
            if (!p.exists()) p.mkdirs()
            if (!p.exists() || !p.isDirectory) {
                throw IOException("Cannot create parent directory for download: ${p.absolutePath}")
            }
        }

        var downloaded = if (partFile.exists()) partFile.length() else 0L
        val url = URL(sourceUrl)
        var connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            if (downloaded > 0) {
                setRequestProperty("Range", "bytes=$downloaded-")
            }
        }
        connection.connect()

        var responseCode = connection.responseCode
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
            responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
            responseCode == 307 || responseCode == 308) {
            val redirectUrl = connection.getHeaderField("Location")
            connection.disconnect()
            connection = (URL(redirectUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                if (downloaded > 0) {
                    setRequestProperty("Range", "bytes=$downloaded-")
                }
            }
            connection.connect()
            responseCode = connection.responseCode
        }

        val totalLength = if (responseCode == HttpURLConnection.HTTP_PARTIAL) {
            downloaded + connection.contentLengthLong
        } else {
            downloaded = 0L
            partFile.delete()
            connection.contentLengthLong
        }

        val output = FileOutputStream(partFile, downloaded > 0)
        val input = connection.inputStream
        val buffer = ByteArray(64 * 1024)

        var lastTime = System.currentTimeMillis()
        var bytesSinceLastTime = 0L

        try {
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (!coroutineContext.isActive) {
                    output.close()
                    input.close()
                    connection.disconnect()
                    throw CancellationException("Download cancelled")
                }
                output.write(buffer, 0, bytesRead)
                downloaded += bytesRead
                bytesSinceLastTime += bytesRead

                val now = System.currentTimeMillis()
                val elapsed = now - lastTime
                if (elapsed >= 500) {
                    val speed = (bytesSinceLastTime * 1000) / elapsed
                    val fraction = if (totalLength > 0) downloaded.toFloat() / totalLength.toFloat() else -1f

                    val mbDown = downloaded / (1024 * 1024)
                    val mbTotal = totalLength / (1024 * 1024)
                    val speedKb = speed / 1024

                    _state.value = _state.value.copy(
                        downloadedBytes = downloaded,
                        totalBytes = totalLength,
                        downloadSpeedBytesPerSec = speed,
                        progressFraction = fraction,
                        detailMessage = "Downloading Ubuntu ARM64: $mbDown MB of $mbTotal MB (${speedKb} KB/s)"
                    )
                    lastTime = now
                    bytesSinceLastTime = 0L
                }
            }
        } finally {
            output.flush()
            output.close()
            input.close()
            connection.disconnect()
        }

        // Rename .part to final archive atomically
        destFile.parentFile?.let { p ->
            if (!p.exists()) p.mkdirs()
        }
        if (destFile.exists()) destFile.delete()
        if (!partFile.renameTo(destFile)) {
            partFile.copyTo(destFile, overwrite = true)
            partFile.delete()
        }
    }

    private fun verifySha256(file: File, expectedHash: String): Boolean {
        if (!file.exists() || file.length() < 10L * 1024 * 1024) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return hash.equals(expectedHash, ignoreCase = true)
    }

    /**
     * Extracts rootfs tarball using toybox tar with fallback to system tar.
     */
    private fun extractArchive(archive: File, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        if (!targetDir.exists() || !targetDir.isDirectory) {
            throw IOException("Extraction target directory does not exist or is not a directory: ${targetDir.absolutePath}")
        }

        val tarCmd = when {
            File("/system/bin/toybox").exists() -> arrayOf("/system/bin/toybox", "tar", "-xpf", archive.absolutePath, "-C", targetDir.absolutePath)
            File("/system/bin/tar").exists() -> arrayOf("/system/bin/tar", "-xpf", archive.absolutePath, "-C", targetDir.absolutePath)
            else -> arrayOf("tar", "-xpf", archive.absolutePath, "-C", targetDir.absolutePath)
        }

        val process = ProcessBuilder(*tarCmd)
            .redirectErrorStream(true)
            .start()

        val reader = BufferedReader(InputStreamReader(process.inputStream))
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            // Drain output to prevent buffer blocking
        }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val bash = File(targetDir, "bin/bash")
            val usrBash = File(targetDir, "usr/bin/bash")
            if (!bash.exists() && !usrBash.exists()) {
                throw IllegalStateException("Tar extraction returned code $exitCode and /bin/bash is missing.")
            }
        }
    }

    /**
     * Explicitly sets Unix executable permissions using Android-compatible APIs.
     * Combines POSIX Bionic Os.chmod, JNI native chmod, and Java File.setExecutable.
     */
    fun setExecutablePermissions(file: File): Boolean {
        if (!file.exists()) return false

        // Ensure parent directory is searchable and executable (0755)
        file.parentFile?.let { dir ->
            dir.setReadable(true, false)
            dir.setExecutable(true, false)
            try {
                android.system.Os.chmod(dir.absolutePath, 493) // 0755 octal
            } catch (t: Throwable) {
                // Non-fatal
            }
            NativePtyBridge.setFileMode(dir.absolutePath, 493)
        }

        // 1. Android Java API
        file.setReadable(true, false)
        val javaOk = file.setExecutable(true, false)

        // 2. Android Bionic Os.chmod (API 21+)
        var osOk = false
        try {
            android.system.Os.chmod(file.absolutePath, 493) // 0755 octal
            osOk = true
        } catch (t: Throwable) {
            // Non-fatal
        }

        // 3. Native JNI direct libc syscall
        val nativeOk = NativePtyBridge.setFileMode(file.absolutePath, 493)

        // 4. Shell chmod fallback
        try {
            val chmodBin = when {
                File("/system/bin/chmod").exists() -> "/system/bin/chmod"
                File("/system/bin/toybox").exists() -> "/system/bin/toybox"
                else -> "chmod"
            }
            val args = if (chmodBin.endsWith("toybox")) arrayOf(chmodBin, "chmod", "0755", file.absolutePath)
                       else arrayOf(chmodBin, "0755", file.absolutePath)
            ProcessBuilder(*args).start().waitFor()
        } catch (t: Throwable) {
            // Non-fatal
        }

        return file.canExecute() || javaOk || osOk || nativeOk
    }

    /**
     * Resolves the LinuxDroid W^X Carrier Library (libproot.so) extracted by Android into nativeLibraryDir.
     */
    fun resolveCarrierProot(): File? {
        return try {
            val ctx = context ?: return null
            val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir)
            val candidate = File(nativeLibDir, "libproot.so")
            if (candidate.exists() && candidate.length() > 0L && candidate.canExecute()) {
                candidate
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Executes a lightweight probe command with PRoot to verify operating system execution capability.
     */
    fun testProotExecutable(prootBin: File): String {
        val pb = ProcessBuilder(prootBin.absolutePath, "--version")
        pb.redirectErrorStream(true)
        val env = pb.environment()
        if (tmpDir.exists()) {
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        }
        if (loaderFile.exists()) {
            env["PROOT_LOADER"] = loaderFile.absolutePath
        }
        val process = pb.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0 && !output.contains("proot", ignoreCase = true) && !output.contains("5.1.")) {
            throw IOException("PRoot probe returned exit code $exitCode: $output")
        }
        return output
    }

    /**
     * Installs, sets executable permissions, and validates the PRoot runtime binaries.
     */
    internal fun installProotBinaries() {
        // 1. Ensure binary destination is stored in app's private internal files directory, not external/shared storage
        val path = prootFile.absolutePath
        if (path.startsWith("/sdcard") || path.startsWith("/storage/emulated") || path.contains("emulated/0")) {
            throw IllegalStateException("PRoot binary must reside in private internal files directory, not external storage: $path")
        }

        val assetManager = context?.assets
        val assetPrefix = "proot/arm64-v8a"

        binDir.let { dir ->
            if (!dir.exists()) dir.mkdirs()
            if (!dir.exists() || !dir.isDirectory) {
                throw IOException("PRoot binary directory does not exist or is not a directory: ${dir.absolutePath}")
            }
            setExecutablePermissions(dir)
        }

        // 2. Extract proot and loader from assets if assetManager is provided
        if (assetManager != null) {
            assetManager.open("$assetPrefix/proot").use { input ->
                FileOutputStream(prootFile).use { output -> input.copyTo(output) }
            }
            assetManager.open("$assetPrefix/loader").use { input ->
                FileOutputStream(loaderFile).use { output -> input.copyTo(output) }
            }
        }

        // 3. Explicitly set executable permissions using Android-compatible APIs
        setExecutablePermissions(prootFile)
        setExecutablePermissions(loaderFile)

        // 4. Verify file exists, is non-empty, and has correct ELF architecture before execution
        if (!prootFile.exists() || prootFile.length() == 0L) {
            throw IllegalStateException("PRoot binary extraction failed: file does not exist or is empty (${prootFile.absolutePath})")
        }

        val elfResult = ElfValidator.validateArm64Elf(prootFile)
        if (!elfResult.isValid) {
            throw IllegalStateException("PRoot binary failed ELF architecture validation: ${elfResult.detail}")
        }

        if (!prootFile.canExecute()) {
            throw IllegalStateException("PRoot binary permissions error: file is not marked executable after chmod (${prootFile.absolutePath})")
        }

        // 5. Test execution capability & handle Android SELinux / W^X filesystem restrictions
        activeProotFile = prootFile
        try {
            testProotExecutable(prootFile)
        } catch (e: IOException) {
            val errorMsg = e.message ?: ""
            if (errorMsg.contains("error=13") || errorMsg.contains("Permission denied", ignoreCase = true)) {
                // SELinux W^X restriction detected on writable app_data_file directory
                val carrier = resolveCarrierProot()
                var bypassSuccess = false
                if (carrier != null) {
                    try {
                        // Attempt W^X bypass via symlink to nativeLibraryDir carrier library
                        if (prootFile.exists()) prootFile.delete()
                        android.system.Os.symlink(carrier.absolutePath, prootFile.absolutePath)
                        testProotExecutable(prootFile)
                        activeProotFile = prootFile
                        bypassSuccess = true
                    } catch (symEx: Throwable) {
                        try {
                            // Direct carrier fallback
                            testProotExecutable(carrier)
                            activeProotFile = carrier
                            bypassSuccess = true
                        } catch (directEx: Throwable) {
                            // Carrier also failed
                        }
                    }
                }

                if (!bypassSuccess) {
                    // Do not suppress error, silently reinstall, or continue
                    val diagMsg = "Cannot run program \"${prootFile.absolutePath}\": error=13, Permission denied (SELinux W^X restriction detected on app data directory)"
                    throw IllegalStateException("PRoot execution failed in Stage 6 (PREPARING_PROOT): $diagMsg", e)
                }
            } else {
                throw IllegalStateException("PRoot execution failed in Stage 6 (PREPARING_PROOT): $errorMsg", e)
            }
        }
    }

    private fun configureLinuxFilesystem() {
        // Fix APT sandboxing for PRoot
        val aptConfDir = File(rootfsDir, "etc/apt/apt.conf.d")
        if (!aptConfDir.exists()) aptConfDir.mkdirs()
        File(aptConfDir, "01sandbox").writeText("APT::Sandbox::User \"root\";\n")

        // Prevent daemon execution during package installs
        val sbinDir = File(rootfsDir, "usr/sbin")
        if (!sbinDir.exists()) sbinDir.mkdirs()
        val policyRc = File(sbinDir, "policy-rc.d")
        policyRc.writeText("#!/bin/sh\nexit 101\n")
        policyRc.setExecutable(true, false)

        // Ensure /root and .bashrc exist
        val rootHome = File(rootfsDir, "root")
        if (!rootHome.exists()) rootHome.mkdirs()
        val bashrc = File(rootHome, ".bashrc")
        if (!bashrc.exists()) {
            bashrc.writeText(
                """
                # ~/.bashrc: executed by bash(1) for non-login shells.
                export PS1='\[\033[01;32m\]root@ubuntu\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                export TERM=xterm-256color
                export COLORTERM=truecolor
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                alias ls='ls --color=auto'
                alias ll='ls -la'
                alias grep='grep --color=auto'
                """.trimIndent()
            )
        }

        // Ensure required mount targets exist
        listOf("dev", "proc", "sys", "tmp", "dev/shm", "dev/pts").forEach {
            val d = File(rootfsDir, it)
            if (!d.exists()) d.mkdirs()
        }
    }

    private fun configureDnsAndNetwork() {
        val etcDir = File(rootfsDir, "etc")
        if (!etcDir.exists()) etcDir.mkdirs()

        // Write DNS resolvers (Cloudflare and Google)
        File(etcDir, "resolv.conf").writeText(
            """
            nameserver 1.1.1.1
            nameserver 8.8.8.8
            """.trimIndent() + "\n"
        )

        // Write /etc/hosts & /etc/hostname
        File(etcDir, "hosts").writeText(
            """
            127.0.0.1 localhost ubuntu
            ::1 localhost ip6-localhost ip6-loopback
            """.trimIndent() + "\n"
        )
        File(etcDir, "hostname").writeText("ubuntu\n")
    }

    private fun verifyLinuxInstallation() {
        val prootConfig = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = activeProotFile,
            workingDir = "/root",
            executable = "/bin/bash",
            args = listOf("-c", "uname -m && cat /etc/os-release | grep PRETTY_NAME && dpkg --version | head -n 1")
        )

        val (exitCode, output) = ProotLauncher.executeHeadless(prootConfig)
        if (exitCode != 0 || !output.contains("Ubuntu")) {
            throw IllegalStateException("Linux verification failed (exit code $exitCode, output: $output)")
        }
    }

    private fun writeEnvironmentManifest() {
        manifestFile.parentFile?.let { p ->
            if (!p.exists()) p.mkdirs()
        }
        manifestFile.writeText(
            """
            {
                "distro": "Ubuntu 26.04.1 LTS (Resolute)",
                "architecture": "aarch64",
                "rootfsSource": "$UBUNTU_ROOTFS_URL",
                "rootfsSha256": "$UBUNTU_ROOTFS_SHA256",
                "prootVersion": "LinuxDroid-PRoot v5.1.107.92",
                "prootCommit": "caadcae0e7697ec29f02e231a3a88866561aacd0",
                "schemaVersion": 1,
                "installedTimestamp": ${System.currentTimeMillis()}
            }
            """.trimIndent()
        )
    }

    private fun writeCompletionMarker() {
        installedMarker.parentFile?.let { p ->
            if (!p.exists()) p.mkdirs()
        }
        installedMarker.writeText(
            """
            {
                "installedAt": "${System.currentTimeMillis()}",
                "distro": "Ubuntu ARM64",
                "rootfs": "${rootfsDir.absolutePath}",
                "version": 1
            }
            """.trimIndent()
        )
        try {
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_INSTALLED, true).apply()
            }
        } catch (e: Throwable) {
            // Non-fatal
        }
    }

    fun repairOrReinstall() {
        if (installedMarker.exists()) installedMarker.delete()
        if (manifestFile.exists()) manifestFile.delete()
        try {
            context?.let { ctx ->
                val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_INSTALLED, false).apply()
            }
        } catch (e: Throwable) {
            // Non-fatal
        }
        _state.value = InstallState()
    }
}
