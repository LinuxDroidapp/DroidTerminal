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
     */
    fun resolveLoaderFile(config: ProotSessionConfig): File? {
        if (config.loaderExecutable != null && config.loaderExecutable.exists()) {
            ensureExecutable(config.loaderExecutable)
            return config.loaderExecutable
        }

        val prootDir = config.prootExecutable.parentFile ?: config.prootExecutable
        val candidate1 = File(prootDir, "loader")
        if (candidate1.exists()) {
            ensureExecutable(candidate1)
            return candidate1
        }

        val candidate2 = File(prootDir, "libproot_loader.so")
        if (candidate2.exists()) {
            ensureExecutable(candidate2)
            return candidate2
        }

        val linuxDir = config.rootfsDir.parentFile ?: config.rootfsDir
        val candidate3 = File(linuxDir, "bin/loader")
        if (candidate3.exists()) {
            ensureExecutable(candidate3)
            return candidate3
        }

        return null
    }

    /**
     * Resolves and prepares the host temporary directory for PRoot internal sockets.
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

            // Ensure /dev/shm is backed by writable application storage
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

            // Working directory
            add("-w")
            add(config.workingDir.ifBlank { "/root" })

            // Shell executable and arguments
            add(config.executable.ifBlank { "/bin/bash" })
            addAll(config.args)
        }
    }

    /**
     * Builds the standard environment map required by PRoot.
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
            "SHELL" to config.executable,
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

        // Merge user custom environment variables
        env.putAll(config.customEnv)
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

        return NativePtyBridge.nativeCreateSession(
            rootfsPath = config.rootfsDir.absolutePath,
            prootBinPath = config.prootExecutable.absolutePath,
            executable = config.executable,
            args = config.args.toTypedArray(),
            workingDir = config.workingDir,
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
