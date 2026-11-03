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
 *  - '--link2symlink' hard-link translation
 *  - '-0' root virtualization
 *  - Core bind mounts: /dev, /proc, /sys
 *  - Shared memory mount: /dev/shm backed by private storage with 1777 permissions
 *  - Temporary directory isolation: PROOT_TMP_DIR
 *  - Dynamic companion loader: PROOT_LOADER
 */
object ProotLauncher {

    /**
     * Builds the complete argv list for invoking PRoot.
     */
    fun buildProotCommand(config: ProotSessionConfig): List<String> {
        val prootDir = config.prootExecutable.parentFile ?: config.prootExecutable
        val loaderCandidate = File(prootDir, "loader")

        return buildList {
            add(config.prootExecutable.absolutePath)
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
                try {
                    shmBacking.setReadable(true, false)
                    shmBacking.setWritable(true, false)
                    shmBacking.setExecutable(true, false)
                } catch (e: Exception) {
                    // Non-fatal
                }
            }
            add("-b")
            add("${shmBacking.absolutePath}:/dev/shm")

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
        val prootDir = config.prootExecutable.parentFile ?: config.prootExecutable
        val loaderCandidate = File(prootDir, "loader")
        val soLoaderCandidate = File(prootDir, "libproot_loader.so")
        val tmpCandidate = File(prootDir.parentFile ?: prootDir, "tmp")

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
            "DEBIAN_FRONTEND" to "noninteractive"
        )

        // Set loader path
        when {
            loaderCandidate.exists() -> env["PROOT_LOADER"] = loaderCandidate.absolutePath
            soLoaderCandidate.exists() -> env["PROOT_LOADER"] = soLoaderCandidate.absolutePath
        }

        // Set temporary directory for PRoot internal sockets
        if (!tmpCandidate.exists()) {
            tmpCandidate.mkdirs()
        }
        if (tmpCandidate.exists()) {
            env["PROOT_TMP_DIR"] = tmpCandidate.absolutePath
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
        allBinds.add(shmBacking.absolutePath to "/dev/shm")
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
