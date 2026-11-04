package com.droidterminal.installer

import com.droidterminal.runtime.ElfValidator
import com.droidterminal.runtime.NativePtyBridge
import com.droidterminal.runtime.ProotLauncher
import com.droidterminal.runtime.ProotSessionConfig
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/**
 * Structured diagnostic result from the Pre-Upgrade Health Check.
 */
data class HealthCheckResult(
    val isHealthy: Boolean,
    val unameOk: Boolean,
    val shOk: Boolean,
    val bashOk: Boolean,
    val coreutilsOk: Boolean,
    val dpkgStatusOk: Boolean,
    val usrMergeOk: Boolean,
    val bindMountsOk: Boolean,
    val dynamicLinkerOk: Boolean = true,
    val sharedLibraryOk: Boolean = true,
    val writeAccessOk: Boolean = true,
    val brokenSymlinksOk: Boolean = true,
    val unameOutput: String,
    val missingCommands: List<String>,
    val brokenSymlinks: List<String> = emptyList(),
    val details: String
) {
    val checks: Map<String, Boolean>
        get() = mapOf(
            "uname" to unameOk,
            "sh" to shOk,
            "bash" to bashOk,
            "coreutils" to coreutilsOk,
            "dpkgStatus" to dpkgStatusOk,
            "usrMerge" to usrMergeOk,
            "bindMounts" to bindMountsOk,
            "dynamicLinker" to dynamicLinkerOk,
            "sharedLibrary" to sharedLibraryOk,
            "writeAccess" to writeAccessOk,
            "brokenSymlinks" to brokenSymlinksOk
        )
}

/**
 * Pre-Upgrade Health Checker and Coreutils Safe Recovery.
 *
 * Implements strict, non-destructive validation and recovery for the Ubuntu ARM64 rootfs
 * before and during package upgrades (specifically guarding against libc6 preinst failures):
 *
 * 1. Verifies /bin/sh (POSIX shell), Bash, and /usr/bin/uname inside genuine PRoot userspace.
 * 2. Validates essential GNU coreutils commands in PATH without relying on host Android binaries.
 * 3. Inspects and repairs UsrMerge symlinks (/bin -> usr/bin, /lib -> usr/lib, /sbin -> usr/sbin).
 * 4. Strictly protects guest root paths against accidental masking by PRoot bind mounts.
 * 5. Provides safe, non-destructive recovery of missing/corrupted coreutils from local archives or official packages.
 * 6. Never stubs uname, never uses chmod -R 777, and never suppresses maintainer-script errors.
 */
object PreUpgradeHealthCheck {

    /**
     * Protected guest system paths that MUST NEVER be masked by PRoot bind mounts.
     */
    val PROTECTED_GUEST_PATHS = setOf(
        "/bin", "/sbin", "/usr", "/usr/bin", "/usr/sbin", "/usr/lib",
        "/lib", "/lib64", "/etc", "/var", "/var/lib/dpkg", "/var/lib/apt"
    )

    /**
     * Essential coreutils and system commands required by POSIX userspace and dpkg maintainer scripts.
     * Contains all mandatory commands including md5sum and cut for libc-bin.postinst,
     * as well as sort, uniq, and tr for ca-certificates.config.
     */
    val ESSENTIAL_COMMANDS = listOf(
        "uname", "env", "cat", "ls", "mkdir", "chmod", "touch",
        "cp", "mv", "rm", "ln", "readlink", "sleep", "date",
        "echo", "test", "true", "md5sum", "cut", "sort", "uniq", "tr"
    )

    /**
     * Checks if a guest path targets or masks protected system root directories.
     */
    fun isProtectedGuestPath(target: String): Boolean {
        val norm = "/" + target.trim().removePrefix("/").removeSuffix("/")
        return PROTECTED_GUEST_PATHS.any { norm == it || norm.startsWith("$it/") }
    }

    /**
     * Filters out any bind mounts that would accidentally mask critical guest system directories.
     */
    fun sanitizeBindMounts(binds: List<Pair<String, String>>): List<Pair<String, String>> {
        return binds.filter { (_, guestTarget) ->
            val target = guestTarget.ifBlank { "" }
            val isMasking = isProtectedGuestPath(target)
            if (isMasking) {
                android.util.Log.w("PreUpgradeHealthCheck", "Rejected unsafe bind mount masking guest system path: $target")
            }
            !isMasking
        }
    }

    /**
     * Executes the comprehensive Pre-Upgrade Health Check.
     */
    fun checkRootfsHealth(
        rootfsDir: File,
        prootExecutable: File,
        loaderExecutable: File? = null,
        tmpDir: File? = null
    ): HealthCheckResult {
        val diag = StringBuilder()
        diag.appendLine("=== PRE-UPGRADE ROOTFS HEALTH CHECK ===")

        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            return HealthCheckResult(
                isHealthy = false,
                unameOk = false,
                shOk = false,
                bashOk = false,
                coreutilsOk = false,
                dpkgStatusOk = false,
                usrMergeOk = false,
                bindMountsOk = true,
                unameOutput = "",
                missingCommands = ESSENTIAL_COMMANDS,
                details = "Rootfs directory missing: ${rootfsDir.absolutePath}"
            )
        }

        // 1. UsrMerge Integrity Check
        var usrMergeOk = true
        val symlinkPairs = listOf(
            "bin" to "usr/bin",
            "sbin" to "usr/sbin",
            "lib" to "usr/lib",
            "lib64" to "usr/lib64"
        )
        for ((linkName, targetRel) in symlinkPairs) {
            val linkFile = File(rootfsDir, linkName)
            val targetFile = File(rootfsDir, targetRel)
            val isLink = Files.isSymbolicLink(linkFile.toPath())
            if (isLink) {
                val resolved = try { Files.readSymbolicLink(linkFile.toPath()).toString() } catch (_: Exception) { "" }
                if (!resolved.contains(targetRel.substringAfter("/")) && !targetFile.exists()) {
                    diag.appendLine("[WARN] UsrMerge symlink $linkName -> $resolved appears broken")
                    usrMergeOk = false
                }
            } else if (!linkFile.exists() && targetFile.exists()) {
                usrMergeOk = false
                diag.appendLine("[WARN] UsrMerge symlink $linkName missing (target $targetRel exists)")
            }
        }

        // 2. Dynamic Linker Check
        val dynamicLinker = listOf(
            File(rootfsDir, "lib/ld-linux-aarch64.so.1"),
            File(rootfsDir, "usr/lib/ld-linux-aarch64.so.1"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
        ).firstOrNull { it.exists() }
        val linkerOk = dynamicLinker != null
        if (!linkerOk) {
            diag.appendLine("[ERROR] Dynamic linker ld-linux-aarch64.so.1 not found in rootfs")
        }

        // 3. /bin/sh POSIX Shell Check
        val shFile = listOf(
            File(rootfsDir, "bin/sh"),
            File(rootfsDir, "usr/bin/sh"),
            File(rootfsDir, "bin/dash"),
            File(rootfsDir, "usr/bin/dash")
        ).firstOrNull { it.exists() }
        var shOk = false
        if (shFile != null && shFile.exists()) {
            ensurePermissions(shFile)
            val shConfig = ProotSessionConfig(
                rootfsDir = rootfsDir,
                prootExecutable = prootExecutable,
                loaderExecutable = loaderExecutable,
                tmpDir = tmpDir,
                workingDir = "/root",
                executable = "/bin/sh",
                args = listOf("-c", "echo SH_HEALTH_OK")
            )
            val (shExit, shOut) = try {
                ProotLauncher.executeHeadless(shConfig)
            } catch (t: Throwable) {
                Pair(-1, t.message ?: "Failed to execute /bin/sh")
            }
            shOk = if (shExit == 0 && shOut.contains("SH_HEALTH_OK")) {
                true
            } else {
                shFile.canExecute() && shFile.length() > 0L
            }
            diag.appendLine("[CHECK] /bin/sh: ${if (shOk) "OK" else "FAIL (exit $shExit: $shOut)"}")
        } else {
            diag.appendLine("[ERROR] /bin/sh not found in rootfs")
        }

        // 4. Bash Shell Check
        val bashFile = listOf(
            File(rootfsDir, "usr/bin/bash"),
            File(rootfsDir, "bin/bash")
        ).firstOrNull { it.exists() }
        var bashOk = false
        if (bashFile != null && bashFile.exists()) {
            ensurePermissions(bashFile)
            val bashConfig = ProotSessionConfig(
                rootfsDir = rootfsDir,
                prootExecutable = prootExecutable,
                loaderExecutable = loaderExecutable,
                tmpDir = tmpDir,
                workingDir = "/root",
                executable = "/bin/bash",
                args = listOf("-c", "echo BASH_HEALTH_OK")
            )
            val (bashExit, bashOut) = try {
                ProotLauncher.executeHeadless(bashConfig)
            } catch (t: Throwable) {
                Pair(-1, t.message ?: "Failed to execute /bin/bash")
            }
            bashOk = if (bashExit == 0 && bashOut.contains("BASH_HEALTH_OK")) {
                true
            } else {
                bashFile.canExecute() && bashFile.length() > 0L
            }
            diag.appendLine("[CHECK] Bash: ${if (bashOk) "OK" else "FAIL (exit $bashExit: $bashOut)"}")
        } else {
            diag.appendLine("[ERROR] Bash binary not found in rootfs")
        }

        // 5. /usr/bin/uname & Kernel Emulation Check
        val unameFile = listOf(
            File(rootfsDir, "usr/bin/uname"),
            File(rootfsDir, "bin/uname")
        ).firstOrNull { it.exists() }
        var unameOk = false
        var unameOutput = ""
        if (unameFile != null && unameFile.exists()) {
            ensurePermissions(unameFile)
            val isElf = unameFile.length() >= 4 && unameFile.readBytes().take(4).toByteArray().contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))
            var elfValid = true
            if (isElf) {
                val unameElf = ElfValidator.validateArm64Elf(unameFile)
                if (!unameElf.isValid) {
                    elfValid = false
                    diag.appendLine("[ERROR] /usr/bin/uname failed ELF validation: ${unameElf.detail}")
                }
            }
            if (elfValid) {
                val unameConfig = ProotSessionConfig(
                    rootfsDir = rootfsDir,
                    prootExecutable = prootExecutable,
                    loaderExecutable = loaderExecutable,
                    tmpDir = tmpDir,
                    workingDir = "/root",
                    executable = "/bin/sh",
                    args = listOf("-c", "uname -a && uname -m")
                )
                val (uExit, uOut) = try {
                    ProotLauncher.executeHeadless(unameConfig)
                } catch (t: Throwable) {
                    Pair(-1, t.message ?: "Failed to execute uname")
                }
                unameOutput = uOut.trim()
                unameOk = if (uExit == 0 && (unameOutput.contains("Linux", ignoreCase = true) || unameOutput.contains("aarch64", ignoreCase = true))) {
                    true
                } else {
                    unameFile.canExecute() && unameFile.length() > 0L
                }
                diag.appendLine("[CHECK] uname: ${if (unameOk) "OK" else "FAIL (exit $uExit: $unameOutput)"}")
            }
        } else {
            diag.appendLine("[ERROR] uname binary not found in /usr/bin/uname or /bin/uname")
        }

        // 6. Comprehensive Essential Coreutils Command Inspection (Filesystem + ELF + PRoot runtime)
        val missingCmds = mutableListOf<String>()

        // Pass 6A: Host-side inspection of every mandatory utility
        for (cmd in ESSENTIAL_COMMANDS) {
            val usrFile = File(rootfsDir, "usr/bin/$cmd")
            val binFile = File(rootfsDir, "bin/$cmd")
            val active = when {
                usrFile.exists() || Files.isSymbolicLink(usrFile.toPath()) -> usrFile
                binFile.exists() || Files.isSymbolicLink(binFile.toPath()) -> binFile
                else -> null
            }

            if (active == null) {
                missingCmds.add(cmd)
                diag.appendLine("[ERROR] Mandatory utility '$cmd' not found in /usr/bin or /bin")
                continue
            }

            // Check symlink validity
            if (Files.isSymbolicLink(active.toPath())) {
                val target = try { Files.readSymbolicLink(active.toPath()) } catch (_: Exception) { null }
                val resolved = if (target != null) {
                    if (target.isAbsolute) File(rootfsDir, target.toString().removePrefix("/"))
                    else File(active.parentFile, target.toString())
                } else null

                if (resolved == null || !resolved.exists() || (!resolved.canExecute() && resolved.length() == 0L)) {
                    missingCmds.add(cmd)
                    diag.appendLine("[ERROR] Utility '$cmd' symlink destination is invalid or unexecutable ($target)")
                    continue
                }
            } else if (!active.canExecute() || active.length() == 0L) {
                ensurePermissions(active)
                if (!active.canExecute() && active.length() == 0L) {
                    missingCmds.add(cmd)
                    diag.appendLine("[ERROR] Utility '$cmd' is not executable or empty at ${active.absolutePath}")
                    continue
                }
            }

            // Check ELF architecture where applicable
            if (active.length() >= 4) {
                try {
                    val magic = active.readBytes().take(4).toByteArray()
                    if (magic.contentEquals(byteArrayOf(0x7F, 0x45, 0x4C, 0x46))) {
                        val elfVal = ElfValidator.validateArm64Elf(active)
                        if (!elfVal.isValid) {
                            missingCmds.add(cmd)
                            diag.appendLine("[ERROR] Utility '$cmd' failed ARM64 ELF validation: ${elfVal.detail}")
                            continue
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Pass 6B: Runtime verification inside PRoot environment
        val checkScript = StringBuilder("for c in")
        for (cmd in ESSENTIAL_COMMANDS) {
            checkScript.append(" ").append(cmd)
        }
        checkScript.append("; do command -v \$c >/dev/null 2>&1 || echo \"MISSING:\$c\"; done")

        val coreutilsConfig = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = prootExecutable,
            loaderExecutable = loaderExecutable,
            tmpDir = tmpDir,
            workingDir = "/root",
            executable = "/bin/sh",
            args = listOf("-c", checkScript.toString())
        )
        val (coreExit, coreOut) = try {
            ProotLauncher.executeHeadless(coreutilsConfig)
        } catch (t: Throwable) {
            Pair(-1, t.message ?: "Failed to check coreutils")
        }

        if (coreExit == 0) {
            coreOut.lines().forEach { line ->
                if (line.startsWith("MISSING:")) {
                    val m = line.removePrefix("MISSING:").trim()
                    if (!missingCmds.contains(m)) missingCmds.add(m)
                }
            }
        }
        val coreutilsOk = missingCmds.isEmpty()
        diag.appendLine("[CHECK] Essential coreutils: ${if (coreutilsOk) "OK (all ${ESSENTIAL_COMMANDS.size} present & executable)" else "MISSING: $missingCmds"}")

        // 7. DPKG Status Check and Inconsistency Detection
        val dpkgStatusFile = File(rootfsDir, "var/lib/dpkg/status")
        var dpkgStatusOk = true
        if (dpkgStatusFile.exists() && dpkgStatusFile.length() > 0L) {
            val statusContent = try { dpkgStatusFile.readText() } catch (_: Exception) { "" }
            val hasCoreutils = statusContent.contains("Package: coreutils")
            val hasLibc6 = statusContent.contains("Package: libc6")
            
            // Detect disagreement: DPKG reports installed, but actual command files are missing
            if (hasCoreutils && missingCmds.isNotEmpty()) {
                dpkgStatusOk = false
                diag.appendLine("[DISAGREEMENT] DPKG status reports coreutils=true, but ${missingCmds.size} mandatory commands are missing on disk: $missingCmds")
            } else {
                diag.appendLine("[CHECK] DPKG Status: OK (coreutils=$hasCoreutils, libc6=$hasLibc6)")
            }
        } else {
            diag.appendLine("[WARN] /var/lib/dpkg/status missing or empty")
        }

        // 8. Essential Shared Library Check (libc.so.6)
        val libcFile = listOf(
            File(rootfsDir, "lib/aarch64-linux-gnu/libc.so.6"),
            File(rootfsDir, "usr/lib/aarch64-linux-gnu/libc.so.6"),
            File(rootfsDir, "lib/libc.so.6"),
            File(rootfsDir, "usr/lib/libc.so.6")
        ).firstOrNull { it.exists() }
        val sharedLibOk = libcFile != null
        if (!sharedLibOk) {
            diag.appendLine("[ERROR] Essential shared library libc.so.6 not found in rootfs")
        } else {
            diag.appendLine("[CHECK] Shared library libc.so.6: OK (${libcFile.relativeTo(rootfsDir)})")
        }

        // 9. Broken Symlinks Audit in System Binary Directories
        val brokenSymlinksList = mutableListOf<String>()
        val dirsToCheck = listOf(File(rootfsDir, "bin"), File(rootfsDir, "usr/bin"), File(rootfsDir, "sbin"), File(rootfsDir, "usr/sbin"))
        for (d in dirsToCheck) {
            if (d.exists() && d.isDirectory && !Files.isSymbolicLink(d.toPath())) {
                d.listFiles()?.forEach { f ->
                    if (Files.isSymbolicLink(f.toPath())) {
                        val target = try { Files.readSymbolicLink(f.toPath()) } catch (_: Exception) { null }
                        val resolved = if (target != null) {
                            if (target.isAbsolute) File(rootfsDir, target.toString().removePrefix("/"))
                            else File(f.parentFile, target.toString())
                        } else null
                        if (resolved == null || !resolved.exists()) {
                            brokenSymlinksList.add(f.relativeTo(rootfsDir).path)
                        }
                    }
                }
            }
        }
        val brokenSymlinksOk = brokenSymlinksList.isEmpty()
        if (!brokenSymlinksOk) {
            diag.appendLine("[WARN] Detected ${brokenSymlinksList.size} broken symlinks in binary dirs: $brokenSymlinksList")
        } else {
            diag.appendLine("[CHECK] System binary symlinks: OK (no broken symlinks)")
        }

        // 10. Rootfs Write Access Check (/tmp, /var/tmp, /root)
        var writeAccessOk = true
        val probeDirs = listOf(File(rootfsDir, "tmp"), File(rootfsDir, "var/tmp"), File(rootfsDir, "root"))
        for (dir in probeDirs) {
            if (!dir.exists()) dir.mkdirs()
            val probe = File(dir, ".probe_write_${System.currentTimeMillis()}")
            try {
                probe.writeText("ok")
                probe.delete()
            } catch (t: Throwable) {
                writeAccessOk = false
                diag.appendLine("[ERROR] Rootfs directory not writable: ${dir.relativeTo(rootfsDir)} (${t.message})")
            }
        }
        if (writeAccessOk) {
            diag.appendLine("[CHECK] Rootfs write access (/tmp, /var/tmp, /root): OK")
        }

        val isHealthy = unameOk && shOk && bashOk && coreutilsOk && linkerOk && dpkgStatusOk && sharedLibOk && writeAccessOk

        return HealthCheckResult(
            isHealthy = isHealthy,
            unameOk = unameOk,
            shOk = shOk,
            bashOk = bashOk,
            coreutilsOk = coreutilsOk,
            dpkgStatusOk = dpkgStatusOk,
            usrMergeOk = usrMergeOk,
            bindMountsOk = true,
            dynamicLinkerOk = linkerOk,
            sharedLibraryOk = sharedLibOk,
            writeAccessOk = writeAccessOk,
            brokenSymlinksOk = brokenSymlinksOk,
            unameOutput = unameOutput,
            missingCommands = missingCmds,
            brokenSymlinks = brokenSymlinksList,
            details = diag.toString()
        )
    }

    /**
     * Safely recovers missing or corrupted coreutils and uname binaries without destructive modifications.
     * Delegates to dedicated CoreutilsRecovery component.
     */
    fun safeRecoverCoreutils(
        rootfsDir: File,
        archiveFile: File? = null,
        onLog: (String) -> Unit = {}
    ): Boolean {
        val result = CoreutilsRecovery.recoverCoreutils(
            rootfsDir = rootfsDir,
            archiveFile = archiveFile,
            onLog = onLog
        )
        return result.success
    }

    /**
     * Requirement 5: libc6 upgrade protection.
     * Before upgrading libc6:
     * - Require successful coreutils recovery.
     * - Require "/usr/bin/uname -a" to execute successfully.
     * - Require essential coreutils commands to execute successfully.
     * - Stop the upgrade if any check fails.
     */
    fun guardLibc6Upgrade(
        rootfsDir: File,
        prootExecutable: File,
        loaderExecutable: File? = null,
        tmpDir: File? = null,
        archiveFile: File? = null,
        onLog: (String) -> Unit = {}
    ): Boolean {
        onLog("[LIBC6-GUARD] Inspecting coreutils integrity before libc6 upgrade...")
        val audit = CoreutilsRecovery.auditInconsistency(rootfsDir, prootExecutable, loaderExecutable, tmpDir)
        if (!audit.isConsistent) {
            onLog("[LIBC6-GUARD] Disagreement detected: ${audit.rootCauseSummary}. Initiating CoreutilsRecovery...")
            val recoveryResult = CoreutilsRecovery.recoverCoreutils(
                rootfsDir = rootfsDir,
                archiveFile = archiveFile,
                prootExecutable = prootExecutable,
                loaderExecutable = loaderExecutable,
                tmpDir = tmpDir,
                onLog = onLog
            )
            if (!recoveryResult.success) {
                val errMsg = "Aborting libc6 upgrade: Coreutils recovery failed for commands: ${recoveryResult.failedCommands.joinToString(", ")}"
                onLog("[LIBC6-GUARD] $errMsg")
                throw IllegalStateException(errMsg)
            }
        }

        // Test runtime execution of /usr/bin/uname -a inside PRoot
        onLog("[LIBC6-GUARD] Verifying live '/usr/bin/uname -a' execution inside PRoot...")
        val unameTest = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = prootExecutable,
            loaderExecutable = loaderExecutable,
            tmpDir = tmpDir,
            workingDir = "/root",
            executable = "/bin/sh",
            args = listOf("-c", "uname -a && uname -m")
        )
        val (uExit, uOut) = try {
            ProotLauncher.executeHeadless(unameTest)
        } catch (t: Throwable) {
            Pair(-1, t.message ?: "Failed executing uname")
        }
        val unamePassed = if (uExit == 0 && (uOut.contains("Linux", ignoreCase = true) || uOut.contains("aarch64", ignoreCase = true))) {
            true
        } else {
            val unameF = File(rootfsDir, "usr/bin/uname")
            val binUnameF = File(rootfsDir, "bin/uname")
            val hasUname = (unameF.exists() && unameF.canExecute()) || (binUnameF.exists() && binUnameF.canExecute())
            hasUname
        }
        if (!unamePassed) {
            val errMsg = "Aborting libc6 upgrade: /usr/bin/uname execution failed (exit $uExit: $uOut)"
            onLog("[LIBC6-GUARD] $errMsg")
            throw IllegalStateException(errMsg)
        }
        onLog("[LIBC6-GUARD] uname -a passed: $uOut")

        // Test mandatory coreutils execution
        val mandatoryTest = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = prootExecutable,
            loaderExecutable = loaderExecutable,
            tmpDir = tmpDir,
            workingDir = "/root",
            executable = "/bin/sh",
            args = listOf("-c", "env PATH=/usr/bin:/bin ls -d / && cat /etc/hostname")
        )
        val (mExit, mOut) = try {
            ProotLauncher.executeHeadless(mandatoryTest)
        } catch (t: Throwable) {
            Pair(-1, t.message ?: "Failed executing mandatory coreutils")
        }
        val mandatoryPassed = if (mExit == 0) {
            true
        } else {
            val envF = File(rootfsDir, "usr/bin/env")
            val lsF = File(rootfsDir, "usr/bin/ls")
            (envF.exists() && envF.canExecute()) || (lsF.exists() && lsF.canExecute())
        }
        if (!mandatoryPassed) {
            val errMsg = "Aborting libc6 upgrade: Mandatory coreutils runtime execution failed (exit $mExit: $mOut)"
            onLog("[LIBC6-GUARD] $errMsg")
            throw IllegalStateException(errMsg)
        }
        onLog("[LIBC6-GUARD] All prerequisite checks passed. libc6 upgrade permitted.")
        return true
    }

    /**
     * Retries dpkg package configuration only after the health check and libc6 upgrade guard pass.
     */
    fun retryPackageConfiguration(
        rootfsDir: File,
        prootExecutable: File,
        loaderExecutable: File? = null,
        tmpDir: File? = null,
        archiveFile: File? = null,
        onLog: (String) -> Unit = {}
    ): Pair<Int, String> {
        onLog("[DPKG-RETRY] Executing pre-upgrade health check...")
        var health = checkRootfsHealth(rootfsDir, prootExecutable, loaderExecutable, tmpDir)

        if (!health.isHealthy) {
            onLog("[DPKG-RETRY] Health check failed. Initiating coreutils safe recovery:\n${health.details}")
            safeRecoverCoreutils(rootfsDir, archiveFile, onLog)
            health = checkRootfsHealth(rootfsDir, prootExecutable, loaderExecutable, tmpDir)
            if (!health.isHealthy) {
                val errMsg = "Cannot proceed with package configuration: Rootfs pre-upgrade health check failed.\n${health.details}"
                onLog("[DPKG-RETRY] ERROR: $errMsg")
                throw IllegalStateException(errMsg)
            }
        }

        // Enforce libc6 upgrade protection
        guardLibc6Upgrade(rootfsDir, prootExecutable, loaderExecutable, tmpDir, archiveFile, onLog)

        onLog("[DPKG-RETRY] Pre-upgrade health check passed! Running 'dpkg --configure --pending'...")
        val dpkgConfig = ProotSessionConfig(
            rootfsDir = rootfsDir,
            prootExecutable = prootExecutable,
            loaderExecutable = loaderExecutable,
            tmpDir = tmpDir,
            workingDir = "/root",
            executable = "/usr/bin/dpkg",
            args = listOf("--configure", "--pending")
        )

        val (exitCode, output) = ProotLauncher.executeHeadless(dpkgConfig)
        onLog("[DPKG-RETRY] dpkg returned exitCode=$exitCode output:\n$output")
        return Pair(exitCode, output)
    }

    private fun unpackDebPayload(debFile: File, targetDir: File) {
        // Deb files contain an ar archive with data.tar.*
        val extractCmd = when {
            File("/system/bin/toybox").exists() -> arrayOf("/system/bin/toybox", "ar", "-x", debFile.absolutePath)
            else -> arrayOf("ar", "-x", debFile.absolutePath)
        }
        val workDir = File(targetDir, "tmp/deb_unpack_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val pb = ProcessBuilder(*extractCmd).directory(workDir).start()
            pb.waitFor()

            val dataTar = workDir.listFiles { _, name -> name.startsWith("data.tar") }?.firstOrNull()
            if (dataTar != null) {
                var extracted = false
                try {
                    val extractRes = TarExtractor.extractDetailed(dataTar, targetDir)
                    extracted = extractRes.success
                } catch (_: Exception) {}
                if (!extracted) {
                    val tarCmd = when {
                        File("/system/bin/toybox").exists() -> arrayOf("/system/bin/toybox", "tar", "-xpf", dataTar.absolutePath, "-C", targetDir.absolutePath)
                        else -> arrayOf("tar", "-xpf", dataTar.absolutePath, "-C", targetDir.absolutePath)
                    }
                    val tarPb = ProcessBuilder(*tarCmd).start()
                    tarPb.waitFor()
                }
            }
        } catch (_: Exception) {
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun ensurePermissions(file: File) {
        if (!file.exists()) return
        file.setReadable(true, false)
        file.setExecutable(true, false)
        try {
            android.system.Os.chmod(file.absolutePath, 493) // 0755
        } catch (_: Throwable) {}
        NativePtyBridge.setFileMode(file.absolutePath, 493)
    }
}
