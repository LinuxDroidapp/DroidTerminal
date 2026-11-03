package com.droidterminal.runtime

import android.content.Context
import java.io.File

sealed class RuntimeResult {
    object Success : RuntimeResult()
    data class Error(val message: String, val throwable: Throwable? = null) : RuntimeResult()
}

/**
 * Clean architectural abstraction for Linux runtime configuration.
 * Enables DroidTerminal to operate in Standalone Mode or seamlessly embed into LinuxDroid.
 */
interface LinuxRuntimeProvider {
    fun getRuntimeName(): String
    fun getProotExecutable(): File
    fun getRootfsDirectory(): File
    fun getHomeDirectory(): File
    fun getEnvironment(): Map<String, String>
    fun getBindMounts(): List<Pair<String, String>>
    suspend fun prepareSession(): RuntimeResult
}

/**
 * Standalone Mode: Manages a private Ubuntu LTS rootfs inside the app's files directory.
 */
class StandaloneRuntimeProvider(private val context: Context) : LinuxRuntimeProvider {

    val linuxDir: File = File(context.filesDir, "linux")
    val rootfsDir: File = File(linuxDir, "rootfs")
    val binDir: File = File(linuxDir, "bin")
    val tmpDir: File = File(linuxDir, "tmp")
    val prootFile: File = File(binDir, "proot")
    val loaderFile: File = File(binDir, "loader")

    override fun getRuntimeName(): String = "Ubuntu ARM64 (PRoot)"

    override fun getProotExecutable(): File {
        return try {
            RuntimeAssetsManager(context, linuxDir).resolveProot()
        } catch (e: Throwable) {
            val nativeLibProot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            if (nativeLibProot.exists()) return nativeLibProot
            if (prootFile.exists()) return prootFile
            val legacy = File(context.filesDir, "bin/proot")
            if (legacy.exists()) return legacy
            prootFile
        }
    }

    override fun getRootfsDirectory(): File {
        if (rootfsDir.exists()) return rootfsDir
        val legacy = File(context.filesDir, "rootfs")
        if (legacy.exists()) return legacy
        return rootfsDir
    }

    override fun getHomeDirectory(): File = File(getRootfsDirectory(), "root")

    fun getGuestWorkingDirectory(): String = "/root"

    fun getTmpDirectory(): File = tmpDir

    override fun getEnvironment(): Map<String, String> {
        val env = mutableMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to "/root",
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "DEBIAN_FRONTEND" to "noninteractive"
        )
        val loader = try {
            RuntimeAssetsManager(context, linuxDir).resolveLoader()
        } catch (_: Throwable) {
            null
        }
        if (loader != null && loader.exists()) {
            env["PROOT_LOADER"] = loader.absolutePath
        } else if (loaderFile.exists()) {
            env["PROOT_LOADER"] = loaderFile.absolutePath
        }
        if (tmpDir.exists()) {
            env["PROOT_TMP_DIR"] = tmpDir.absolutePath
        }
        return env
    }

    override fun getBindMounts(): List<Pair<String, String>> {
        val shmBacking = File(linuxDir, "shm")
        if (!shmBacking.exists()) shmBacking.mkdirs()
        try {
            shmBacking.setReadable(true, false)
            shmBacking.setWritable(true, false)
            shmBacking.setExecutable(true, false)
            android.system.Os.chmod(shmBacking.absolutePath, 1007) // 1777 octal
        } catch (_: Throwable) {}
        return listOf(
            shmBacking.canonicalFile.absolutePath to "/dev/shm"
        )
    }

    override suspend fun prepareSession(): RuntimeResult {
        return try {
            val rootfs = getRootfsDirectory()

            // 1. Prepare host temporary directory (isolated from guest /tmp)
            if (!tmpDir.exists()) tmpDir.mkdirs()
            try {
                tmpDir.setReadable(true, false)
                tmpDir.setWritable(true, false)
                tmpDir.setExecutable(true, false)
                android.system.Os.chmod(tmpDir.absolutePath, 509) // 0777 octal
            } catch (_: Throwable) {}

            // 2. Ensure /root exists inside rootfs with proper permissions
            val rootDir = File(rootfs, "root")
            if (!rootDir.exists()) rootDir.mkdirs()
            try {
                rootDir.setReadable(true, false)
                rootDir.setWritable(true, false)
                rootDir.setExecutable(true, false)
                android.system.Os.chmod(rootDir.absolutePath, 448) // 0700 octal
            } catch (_: Throwable) {}

            // 3. Ensure guest /tmp has sticky bit 1777
            val guestTmp = File(rootfs, "tmp")
            if (!guestTmp.exists()) guestTmp.mkdirs()
            try {
                guestTmp.setReadable(true, false)
                guestTmp.setWritable(true, false)
                guestTmp.setExecutable(true, false)
                android.system.Os.chmod(guestTmp.absolutePath, 1007) // 1777 octal
            } catch (_: Throwable) {}

            // 4. Ensure bash and dynamic linkers have executable permissions
            listOf(
                File(rootfs, "usr/bin/bash"),
                File(rootfs, "bin/bash"),
                File(rootfs, "usr/bin/sh"),
                File(rootfs, "bin/sh"),
                File(rootfs, "usr/bin/true"),
                File(rootfs, "bin/true"),
                File(rootfs, "lib/ld-linux-aarch64.so.1"),
                File(rootfs, "usr/lib/ld-linux-aarch64.so.1"),
                File(rootfs, "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1")
            ).forEach { f ->
                if (f.exists()) {
                    f.setReadable(true, false)
                    f.setExecutable(true, false)
                    try { android.system.Os.chmod(f.absolutePath, 493) } catch (_: Throwable) {}
                }
            }

            // 5. Setup /etc/resolv.conf for Google and Cloudflare DNS
            val etcDir = File(rootfs, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()
            val resolv = File(etcDir, "resolv.conf")
            if (!resolv.exists() || resolv.length() == 0L) {
                resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            }

            // 6. Setup /etc/hosts
            val hosts = File(etcDir, "hosts")
            if (!hosts.exists() || hosts.length() == 0L) {
                hosts.writeText("127.0.0.1 localhost ubuntu\n::1 localhost ip6-localhost ip6-loopback\n")
            }

            // 7. Fix APT sandbox drop permissions (_apt error under PRoot)
            val aptConfDir = File(etcDir, "apt/apt.conf.d")
            if (!aptConfDir.exists()) aptConfDir.mkdirs()
            val aptSandbox = File(aptConfDir, "01sandbox")
            if (!aptSandbox.exists()) {
                aptSandbox.writeText("APT::Sandbox::User \"root\";\n")
            }

            // 8. Fix policy-rc.d to prevent systemd service starts during apt install
            val sbinDir = File(rootfs, "usr/sbin")
            if (!sbinDir.exists()) sbinDir.mkdirs()
            val policyRc = File(sbinDir, "policy-rc.d")
            if (!policyRc.exists()) {
                policyRc.writeText("#!/bin/sh\nexit 101\n")
                policyRc.setExecutable(true, false)
                try { android.system.Os.chmod(policyRc.absolutePath, 493) } catch (_: Throwable) {}
            }

            RuntimeResult.Success
        } catch (e: Exception) {
            RuntimeResult.Error("Failed to prepare standalone environment: ${e.message}", e)
        }
    }
}

/**
 * LinuxDroid Integration Mode: Reuses LinuxDroid's pre-existing Ubuntu rootfs,
 * PRoot binary, home folder, and custom bind mounts with ZERO storage duplication.
 */
class LinuxDroidRuntimeProvider(
    private val externalRootfsDir: File,
    private val externalProotBin: File,
    private val externalHomeDir: File = File(externalRootfsDir, "root"),
    private val customBinds: List<Pair<String, String>> = emptyList(),
    private val customEnv: Map<String, String> = emptyMap()
) : LinuxRuntimeProvider {

    override fun getRuntimeName(): String = "LinuxDroid Ubuntu Integration"

    override fun getProotExecutable(): File = externalProotBin

    override fun getRootfsDirectory(): File = externalRootfsDir

    override fun getHomeDirectory(): File = externalHomeDir

    fun getGuestWorkingDirectory(): String = "/root"

    override fun getEnvironment(): Map<String, String> {
        val defaultEnv = mutableMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to "/root",
            "USER" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LINUXDROID_EMBEDDED" to "1"
        )
        defaultEnv.putAll(customEnv)
        return defaultEnv
    }

    override fun getBindMounts(): List<Pair<String, String>> = customBinds

    override suspend fun prepareSession(): RuntimeResult {
        if (!externalRootfsDir.exists()) {
            return RuntimeResult.Error("LinuxDroid rootfs directory does not exist: ${externalRootfsDir.absolutePath}")
        }
        if (!externalProotBin.exists()) {
            return RuntimeResult.Error("LinuxDroid PRoot binary does not exist: ${externalProotBin.absolutePath}")
        }
        return RuntimeResult.Success
    }
}
