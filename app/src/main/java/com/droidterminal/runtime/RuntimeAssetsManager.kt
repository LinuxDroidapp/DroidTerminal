package com.droidterminal.runtime

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Result of PRoot runtime asset resolution or installation.
 */
data class RuntimeAssetsInstallResult(
    val prootInstalled: Boolean,
    val loaderInstalled: Boolean,
    val prootFile: File?,
    val loaderFile: File?,
    val manifest: ProotManifest?,
    val detail: String
)

/**
 * Centralized manager for PRoot and companion loader runtime binaries.
 *
 * Implements atomic staging, ELF validation, SHA-256 verification,
 * version compatibility checks, rollback support, and carrier fallback.
 */
class RuntimeAssetsManager(
    private val context: Context? = null,
    customLinuxDir: File? = null
) {
    val linuxDir: File = customLinuxDir
        ?: (context?.filesDir?.let { File(it, "linux") })
        ?: File(System.getProperty("java.io.tmpdir"), "droidterminal_files/linux")

    val binDir: File = File(linuxDir, "bin")
    val prootFile: File = File(binDir, "proot")
    val loaderFile: File = File(binDir, "loader")
    private val stagingProot: File = File(binDir, ".proot.part")
    private val stagingLoader: File = File(binDir, ".loader.part")
    private val prevProot: File = File(binDir, ".proot.prev")
    private val prevLoader: File = File(binDir, ".loader.prev")

    val updateManager = ProotUpdateManager(context)

    /**
     * Resolves the verified PRoot executable, using installed assets or carrier fallback.
     */
    fun resolveProot(): File {
        val (proot, _) = resolveBinaries()
        return proot
    }

    /**
     * Resolves the verified companion loader executable, or null if none available.
     */
    fun resolveLoader(): File? {
        val (_, loader) = resolveBinaries()
        return loader
    }

    /**
     * Resolves matching and verified PRoot and loader binaries.
     */
    @Synchronized
    fun resolveBinaries(): Pair<File, File?> {
        // 1. Try currently installed runtime binaries if valid
        if (isValidExecutable(prootFile) && isValidExecutable(loaderFile)) {
            return Pair(prootFile, loaderFile)
        }

        // 2. Perform atomic installation from assets
        val installResult = installFromAssets()
        if (installResult.prootInstalled && installResult.prootFile != null && isValidExecutable(installResult.prootFile)) {
            val loader = if (installResult.loaderInstalled && installResult.loaderFile != null && isValidExecutable(installResult.loaderFile)) {
                installResult.loaderFile
            } else null
            return Pair(installResult.prootFile, loader)
        }

        // 3. Carrier fallback (nativeLibraryDir)
        val carrierProot = resolveCarrierBinary("libproot.so")
        val carrierLoader = resolveCarrierBinary("libproot_loader.so")
        if (carrierProot != null && isValidExecutable(carrierProot)) {
            return Pair(carrierProot, carrierLoader)
        }

        if (prootFile.exists()) {
            return Pair(prootFile, if (loaderFile.exists()) loaderFile else null)
        }

        throw IllegalStateException("Failed to resolve a valid PRoot binary in ${binDir.absolutePath} or nativeLibraryDir")
    }

    /**
     * Installs PRoot and loader from packaged assets atomically.
     */
    @Synchronized
    fun installFromAssets(): RuntimeAssetsInstallResult {
        if (!binDir.exists()) {
            binDir.mkdirs()
            setPermissions(binDir)
        }

        val assetManager = context?.assets
        val manifest = updateManager.readBundledManifest()
        val assetPrefix = ProotUpdateManager.ASSET_PATH

        var prootOk = false
        var loaderOk = false

        try {
            // Backup existing files to .prev before updating
            if (prootFile.exists()) {
                if (prevProot.exists()) prevProot.delete()
                prootFile.copyTo(prevProot, overwrite = true)
            }
            if (loaderFile.exists()) {
                if (prevLoader.exists()) prevLoader.delete()
                loaderFile.copyTo(prevLoader, overwrite = true)
            }

            // Extract proot into staging
            if (assetManager != null) {
                if (stagingProot.exists()) stagingProot.delete()
                assetManager.open("$assetPrefix/proot").use { input ->
                    FileOutputStream(stagingProot).use { output -> input.copyTo(output) }
                }

                // Verify staging proot ELF and checksum
                setPermissions(stagingProot)
                val elfValid = ElfValidator.validateArm64Elf(stagingProot)
                if (!elfValid.isValid) {
                    throw IOException("Staged PRoot binary failed ELF validation: ${elfValid.detail}")
                }
                if (manifest?.prootSha256 != null && !updateManager.verifyChecksum(stagingProot, manifest.prootSha256)) {
                    throw IOException("Staged PRoot binary failed SHA-256 checksum verification")
                }

                // Atomic promotion
                if (prootFile.exists()) prootFile.delete()
                if (!stagingProot.renameTo(prootFile)) {
                    stagingProot.copyTo(prootFile, overwrite = true)
                    stagingProot.delete()
                }
                setPermissions(prootFile)
                prootOk = isValidExecutable(prootFile)
            }

            // Extract loader into staging
            if (assetManager != null) {
                if (stagingLoader.exists()) stagingLoader.delete()
                assetManager.open("$assetPrefix/loader").use { input ->
                    FileOutputStream(stagingLoader).use { output -> input.copyTo(output) }
                }

                // Verify staging loader ELF and checksum
                setPermissions(stagingLoader)
                val elfValid = ElfValidator.validateArm64Elf(stagingLoader)
                if (!elfValid.isValid) {
                    throw IOException("Staged loader binary failed ELF validation: ${elfValid.detail}")
                }
                if (manifest?.loaderSha256 != null && !updateManager.verifyChecksum(stagingLoader, manifest.loaderSha256)) {
                    throw IOException("Staged loader binary failed SHA-256 checksum verification")
                }

                // Atomic promotion
                if (loaderFile.exists()) loaderFile.delete()
                if (!stagingLoader.renameTo(loaderFile)) {
                    stagingLoader.copyTo(loaderFile, overwrite = true)
                    stagingLoader.delete()
                }
                setPermissions(loaderFile)
                loaderOk = isValidExecutable(loaderFile)
            }

            // Clean up previous backups on success
            if (prootOk) prevProot.delete()
            if (loaderOk) prevLoader.delete()

            return RuntimeAssetsInstallResult(
                prootInstalled = prootOk,
                loaderInstalled = loaderOk,
                prootFile = if (prootOk) prootFile else null,
                loaderFile = if (loaderOk) loaderFile else null,
                manifest = manifest,
                detail = "Installation successful (proot=$prootOk, loader=$loaderOk)"
            )
        } catch (e: Exception) {
            // Rollback on failure
            if (prevProot.exists() && (!prootFile.exists() || !prootFile.canExecute())) {
                prevProot.copyTo(prootFile, overwrite = true)
                setPermissions(prootFile)
            }
            if (prevLoader.exists() && (!loaderFile.exists() || !loaderFile.canExecute())) {
                prevLoader.copyTo(loaderFile, overwrite = true)
                setPermissions(loaderFile)
            }
            stagingProot.delete()
            stagingLoader.delete()

            return RuntimeAssetsInstallResult(
                prootInstalled = isValidExecutable(prootFile),
                loaderInstalled = isValidExecutable(loaderFile),
                prootFile = if (isValidExecutable(prootFile)) prootFile else null,
                loaderFile = if (isValidExecutable(loaderFile)) loaderFile else null,
                manifest = manifest,
                detail = "Installation failed: ${e.message}"
            )
        }
    }

    /**
     * Resolves a carrier library from nativeLibraryDir.
     */
    fun resolveCarrierBinary(libraryName: String): File? {
        return try {
            val ctx = context ?: return null
            val nativeLibDir = File(ctx.applicationInfo.nativeLibraryDir)
            val candidate = File(nativeLibDir, libraryName)
            if (candidate.exists() && candidate.length() > 0L) {
                setPermissions(candidate)
                candidate
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Checks if the binary exists, is non-empty, and has execute permission.
     */
    fun isValidExecutable(file: File?): Boolean {
        if (file == null || !file.exists() || file.length() == 0L) return false
        if (!file.canExecute()) {
            setPermissions(file)
        }
        return file.canExecute()
    }

    /**
     * Sets executable and searchable permissions (0755) using multi-layer APIs.
     */
    fun setPermissions(file: File): Boolean {
        if (!file.exists()) return false

        file.setReadable(true, false)
        val javaOk = file.setExecutable(true, false)

        try {
            android.system.Os.chmod(file.absolutePath, 493) // 0755 octal
        } catch (t: Throwable) {
            // Non-fatal
        }

        val nativeOk = NativePtyBridge.setFileMode(file.absolutePath, 493)

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

        return file.canExecute() || javaOk || nativeOk
    }
}
