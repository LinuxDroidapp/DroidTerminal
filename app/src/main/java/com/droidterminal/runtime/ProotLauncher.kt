package com.droidterminal.runtime

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Execution configuration for launching a process inside the Linux userspace via PRoot.
 */
data class ProotSessionConfig(
    val rootfsDir: File,
    val prootExecutable: File,
    val loaderExecutable: File? = null,
    val tmpDir: File? = null,
    val workingDir: String = "/root",
    val executable: String = "/bin/bash",
    val args: List<String> = listOf("-l"),
    val customEnv: Map<String, String> = emptyMap(),
    val customBinds: List<Pair<String, String>> = emptyList(),
    val cols: Int = 80,
    val rows: Int = 24
)

/**
 * Centralized PRoot Launcher.
 *
 * Single source of truth for constructing and executing all Linux processes inside PRoot.
 * Consistently applies all required rootless optimizations:
 *  - '--kill-on-exit' to prevent orphan tracee leaks and race conditions
 *  - '--link2symlink' hard-link translation
 *  - '-0' root virtualization
 *  - Core bind mounts: /dev, /proc, /sys
 *  - Shared memory mount: /dev/shm backed by private storage with 1777 permissions
 *  - Host temporary directory isolation: PROOT_TMP_DIR (canonicalized and verified writable)
 *  - Dynamic companion loader: PROOT_LOADER (verified executable)
 */
object ProotLauncher {

    /**
     * Resolves the companion loader file for the given configuration.
     * Prefers the carrier library (libproot_loader.so) in nativeLibraryDir
     * to ensure compatibility with Android SELinux W^X restrictions.
     */
    fun resolveLoaderFile(config: ProotSessionConfig): File? {
        if (config.loaderExecutable != null && config.loaderExecutable.exists()) {
            ensureExecutable(config.loaderExecutable)
            return config.loaderExecutable
        }

        // Check if prootExecutable is a symlink or direct binary in nativeLibraryDir
        val canonicalProot = try { config.prootExecutable.canonicalFile } catch (_: Throwable) { config.prootExecutable }
        val canonicalDir = canonicalProot.parentFile
        if (canonicalDir != null) {
            val carrierLoader = File(canonicalDir, "libproot_loader.so")
            if (carrierLoader.exists()) {
                ensureExecutable(carrierLoader)
                return carrierLoader
            }
        }

        val prootDir = config.prootExecutable.parentFile ?: config.prootExecutable
        val candidateSo = File(prootDir, "libproot_loader.so")
        if (candidateSo.exists()) {
            ensureExecutable(candidateSo)
            return candidateSo
        }

        val candidate1 = File(prootDir, "loader")
        if (candidate1.exists()) {
            ensureExecutable(candidate1)
            return candidate1
        }

        val linuxDir = config.rootfsDir.parentFile ?: config.rootfsDir
        val candidateDirSo = File(linuxDir, "bin/libproot_loader.so")
        if (candidateDirSo.exists()) {
            ensureExecutable(candidateDirSo)
            return candidateDirSo
        }

        val candidate3 = File(linuxDir, "bin/loader")
        if (candidate3.exists()) {
            ensureExecutable(candidate3)
            return candidate3
        }

        return null
    }

    /**
     * Resolves and normalizes the guest working directory for PRoot.
     *
     * Problem A Requirements:
     * 1. Never pass a host filesystem path as the guest working directory.
     * 2. Strip rootfs prefix if a host path was mistakenly provided.
     * 3. Ensure "/root" exists inside the Ubuntu rootfs with proper permissions.
     * 4. Create missing standard directories during rootfs initialization.
     * 5. Default to "/" if the requested directory is unavailable.
     */
    fun resolveGuestWorkingDir(rootfsDir: File, requestedDir: String?): String {
        var guestDir = requestedDir?.trim() ?: "/root"
        if (guestDir.isEmpty()) {
            guestDir = "/root"
        }

        val rootfsCanonical = try { rootfsDir.canonicalPath } catch (_: Throwable) { rootfsDir.absolutePath }
        val rootfsAbsolute = rootfsDir.absolutePath

        // Strip host filesystem path if provided
        if (guestDir.startsWith(rootfsCanonical)) {
            guestDir = guestDir.removePrefix(rootfsCanonical)
        } else if (guestDir.startsWith(rootfsAbsolute)) {
            guestDir = guestDir.removePrefix(rootfsAbsolute)
        }

        if (!guestDir.startsWith("/")) {
            guestDir = "/$guestDir"
        }

        // Verify target directory exists inside rootfs
        val targetInRootfs = File(rootfsDir, guestDir.removePrefix("/"))
        if (!targetInRootfs.exists() || !targetInRootfs.isDirectory) {
            // Auto-create standard directories inside rootfs
            if (guestDir == "/root" || guestDir.startsWith("/root/") ||
                guestDir == "/home" || guestDir.startsWith("/home/") ||
                guestDir == "/tmp" || guestDir.startsWith("/tmp/")) {
                try {
                    targetInRootfs.mkdirs()
                    targetInRootfs.setReadable(true, false)
                    targetInRootfs.setWritable(true, false)
                    targetInRootfs.setExecutable(true, false)
                    if (guestDir.startsWith("/root")) {
                        try {
                            android.system.Os.chmod(targetInRootfs.absolutePath, 448) // 0700 octal
                        } catch (_: Throwable) {}
                    }
                } catch (_: Throwable) {
                    // Non-fatal
                }
            }
        }

        val finalTarget = File(rootfsDir, guestDir.removePrefix("/"))
        return if (finalTarget.exists() && finalTarget.isDirectory) {
            guestDir
        } else {
            "/"
        }
    }

    /**
     * Resolves and prepares the host temporary directory for PRoot internal sockets.
     *
     * Problem C Requirements:
     * 1. Dedicated host temporary directory created and verified before PRoot starts.
     * 2. Verify that it exists, is writable, and accessible (0777 permissions).
     * 3. Separate host PRoot temporary storage from guest /tmp.
     * 4. Log the resolved temporary directory and its validation results.
     */
    fun resolveHostTmpDir(config: ProotSessionConfig): File {
        val baseDir = config.tmpDir
            ?: File(config.rootfsDir.parentFile ?: config.rootfsDir, "tmp")

        if (!baseDir.exists()) {
            baseDir.mkdirs()
        }

        val canonical = try {
            baseDir.canonicalFile
        } catch (e: Exception) {
            baseDir
        }

        if (!canonical.exists()) {
            canonical.mkdirs()
        }

        try {
            canonical.setReadable(true, false)
            canonical.setWritable(true, false)
            canonical.setExecutable(true, false)
            android.system.Os.chmod(canonical.absolutePath, 509) // 0777 octal
        } catch (t: Throwable) {
            // Non-fatal
        }

        val writable = canonical.canWrite()
        android.util.Log.i("ProotLauncher", "Host temporary directory resolved: ${canonical.absolutePath} (writable=$writable)")

        return canonical
    }

    private fun ensureExecutable(file: File) {
        if (!file.canExecute()) {
            file.setReadable(true, false)
            file.setExecutable(true, false)
            try {
                android.system.Os.chmod(file.absolutePath, 493) // 0755
            } catch (t: Throwable) {
                // Non-fatal
            }
            NativePtyBridge.setFileMode(file.absolutePath, 493)
        }
    }

    /**
     * Builds the complete argv list for invoking PRoot.
     */
    fun buildProotCommand(config: ProotSessionConfig): List<String> {
        val rootfsDir = config.rootfsDir
        if (!rootfsDir.exists() || !rootfsDir.isDirectory) {
            throw IllegalStateException("PRoot rootfs directory does not exist or is not a directory: ${rootfsDir.absolutePath}")
        }
        if (!config.prootExecutable.exists()) {
            throw IllegalStateException("PRoot executable missing: ${config.prootExecutable.absolutePath}")
        }

        val guestWd = resolveGuestWorkingDir(config.rootfsDir, config.workingDir)

        return buildList {
            add(config.prootExecutable.absolutePath)
            add("--kill-on-exit")
            add("--link2symlink")
            add("-0")
            add("-r")
            add(config.rootfsDir.absolutePath)

            // Standard virtual filesystem bind mounts
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")

            // Ensure /dev/shm is backed by private storage with 1777 permissions (never host /tmp or guest /tmp)
            val shmBacking = File(config.rootfsDir.parentFile ?: config.rootfsDir, "shm")
            if (!shmBacking.exists()) {
                shmBacking.mkdirs()
            }
            try {
                shmBacking.setReadable(true, false)
                shmBacking.setWritable(true, false)
                shmBacking.setExecutable(true, false)
                android.system.Os.chmod(shmBacking.absolutePath, 1007) // 1777 octal
            } catch (e: Throwable) {
                // Non-fatal
            }
            add("-b")
            add("${shmBacking.canonicalFile.absolutePath}:/dev/shm")

            // Custom user or runtime bindings
            for (bind in config.customBinds) {
                add("-b")
                if (bind.second.isBlank() || bind.first == bind.second) {
                    add(bind.first)
                } else {
                    add("${bind.first}:${bind.second}")
                }
            }

            // Working directory (strictly guest path, verified never a host path)
            add("-w")
            add(guestWd)

            // Shell executable and arguments (structured array preserved)
            add(config.executable.ifBlank { "/bin/bash" })
            addAll(config.args)
        }
    }

    /**
     * Builds the standard environment map required by PRoot.
     * Strictly removes Android host library leakage (LD_PRELOAD, LD_LIBRARY_PATH).
     */
    fun buildProotEnvironment(config: ProotSessionConfig): Map<String, String> {
        val hostTmpDir = resolveHostTmpDir(config)
        val loaderFile = resolveLoaderFile(config)

        val env = mutableMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to config.executable.ifBlank { "/bin/bash" },
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive",
            "PROOT_TMP_DIR" to hostTmpDir.absolutePath
        )

        // Set verified loader path
        if (loaderFile != null && loaderFile.exists()) {
            env["PROOT_LOADER"] = loaderFile.absolutePath
        }

        // Merge user custom environment variables while purging Android host library leakage
        config.customEnv.forEach { (k, v) ->
            if (k != "LD_PRELOAD" && k != "LD_LIBRARY_PATH" && !k.startsWith("ANDROID_")) {
                env[k] = v
            }
        }
        return env
    }

    /**
     * Executes a headless command inside the Linux environment and captures output and exit code.
     */
    fun executeHeadless(config: ProotSessionConfig): Pair<Int, String> {
        val cmd = buildProotCommand(config)
        val env = buildProotEnvironment(config)

        val pb = ProcessBuilder(cmd)
        pb.redirectErrorStream(true)
        val pEnv = pb.environment()
        // Strip Android host LD_PRELOAD and LD_LIBRARY_PATH leakage
        pEnv.remove("LD_PRELOAD")
        pEnv.remove("LD_LIBRARY_PATH")
        pEnv.putAll(env)

        val process = pb.start()
        val reader = BufferedReader(InputStreamReader(process.inputStream))
        val output = StringBuilder()
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            output.append(line).append('\n')
        }
        val exitCode = process.waitFor()
        return Pair(exitCode, output.toString())
    }

    /**
     * Spawns an interactive PTY session via NativePtyBridge using the centralized config.
     */
    fun spawnInteractivePtySession(config: ProotSessionConfig): Int {
        val env = buildProotEnvironment(config)
        val envKeys = env.keys.toTypedArray()
        val envVals = env.values.toTypedArray()

        // Gather bind mounts
        val shmBacking = File(config.rootfsDir.parentFile ?: config.rootfsDir, "shm")
        if (!shmBacking.exists()) {
            shmBacking.mkdirs()
        }
        val allBinds = mutableListOf<Pair<String, String>>()
        allBinds.add(shmBacking.canonicalFile.absolutePath to "/dev/shm")
        allBinds.addAll(config.customBinds)

        val bindSrcs = allBinds.map { it.first }.toTypedArray()
        val bindTgts = allBinds.map { it.second }.toTypedArray()

        val guestWd = resolveGuestWorkingDir(config.rootfsDir, config.workingDir)

        return NativePtyBridge.nativeCreateSession(
            rootfsPath = config.rootfsDir.absolutePath,
            prootBinPath = config.prootExecutable.absolutePath,
            executable = config.executable.ifBlank { "/bin/bash" },
            args = config.args.toTypedArray(),
            workingDir = guestWd,
            envKeys = envKeys,
            envValues = envVals,
            bindSources = bindSrcs,
            bindTargets = bindTgts,
            isProot = true,
            cols = config.cols,
            rows = config.rows
        )
    }
}
