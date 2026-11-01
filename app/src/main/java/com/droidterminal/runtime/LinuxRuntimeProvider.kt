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

    override fun getRuntimeName(): String = "Standalone Ubuntu LTS"

    override fun getProotExecutable(): File {
        val binDir = File(context.filesDir, "bin")
        if (!binDir.exists()) binDir.mkdirs()
        return File(binDir, "proot")
    }

    override fun getRootfsDirectory(): File {
        val dir = File(context.filesDir, "rootfs")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    override fun getHomeDirectory(): File = File(getRootfsDirectory(), "root")

    override fun getEnvironment(): Map<String, String> {
        return mapOf(
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
    }

    override fun getBindMounts(): List<Pair<String, String>> {
        val tmpDir = File(getRootfsDirectory(), "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        return listOf(
            tmpDir.absolutePath to "/dev/shm"
        )
    }

    override suspend fun prepareSession(): RuntimeResult {
        return try {
            val rootfs = getRootfsDirectory()

            // 1. Setup /etc/resolv.conf for Google and Cloudflare DNS
            val etcDir = File(rootfs, "etc")
            if (!etcDir.exists()) etcDir.mkdirs()
            val resolv = File(etcDir, "resolv.conf")
            if (!resolv.exists()) {
                resolv.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }

            // 2. Setup /etc/hosts
            val hosts = File(etcDir, "hosts")
            if (!hosts.exists()) {
                hosts.writeText("127.0.0.1 localhost ubuntu\n")
            }

            // 3. Fix APT sandbox drop permissions (_apt error under PRoot)
            val aptConfDir = File(etcDir, "apt/apt.conf.d")
            if (!aptConfDir.exists()) aptConfDir.mkdirs()
            val aptSandbox = File(aptConfDir, "01sandbox")
            if (!aptSandbox.exists()) {
                aptSandbox.writeText("APT::Sandbox::User \"root\";\n")
            }

            // 4. Fix policy-rc.d to prevent systemd service starts during apt install
            val sbinDir = File(rootfs, "usr/sbin")
            if (!sbinDir.exists()) sbinDir.mkdirs()
            val policyRc = File(sbinDir, "policy-rc.d")
            if (!policyRc.exists()) {
                policyRc.writeText("#!/bin/sh\nexit 101\n")
                policyRc.setExecutable(true, false)
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

    override fun getEnvironment(): Map<String, String> {
        val defaultEnv = mutableMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to externalHomeDir.absolutePath,
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
