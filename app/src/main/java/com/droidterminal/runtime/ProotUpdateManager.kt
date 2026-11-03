package com.droidterminal.runtime

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * Runtime asset identity and metadata from MANIFEST.txt.
 */
data class ProotManifest(
    val version: String,
    val commit: String,
    val abi: String,
    val arch: String,
    val prootSha256: String,
    val loaderSha256: String,
    val libprootSha256: String? = null,
    val libloaderSha256: String? = null
)

/**
 * Result of checking remote PRoot updates.
 */
data class ProotUpdateCheckResult(
    val updateAvailable: Boolean,
    val currentCommit: String,
    val latestCommit: String,
    val details: String
)

/**
 * Intelligent PRoot update and metadata manager.
 * Single source of truth for runtime asset verification, versioning, and update policy.
 */
class ProotUpdateManager(
    private val context: Context? = null,
    private val customAssetsDir: File? = null
) {
    companion object {
        const val DEFAULT_REPO_URL = "https://github.com/LinuxDroidapp/proot.git"
        const val ASSET_PATH = "proot/arm64-v8a"
    }

    /**
     * Reads and parses the bundled MANIFEST.txt.
     */
    fun readBundledManifest(): ProotManifest? {
        val content = try {
            if (customAssetsDir != null) {
                val manifestFile = File(customAssetsDir, "MANIFEST.txt")
                if (manifestFile.exists()) manifestFile.readText() else null
            } else if (context != null) {
                context.assets.open("$ASSET_PATH/MANIFEST.txt").bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        return parseManifestContent(content)
    }

    /**
     * Parses the string content of a MANIFEST.txt into [ProotManifest].
     */
    fun parseManifestContent(content: String): ProotManifest? {
        var version = "LinuxDroid-PRoot"
        var commit = ""
        var abi = "arm64-v8a"
        var arch = "aarch64"
        var prootSha = ""
        var loaderSha = ""
        var libprootSha: String? = null
        var libloaderSha: String? = null

        content.lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("LinuxDroid-PRoot") -> version = line
                line.startsWith("commit:") -> commit = line.removePrefix("commit:").trim()
                line.startsWith("ABI:") -> abi = line.removePrefix("ABI:").trim()
                line.startsWith("arch:") -> arch = line.removePrefix("arch:").trim()
                line.startsWith("proot:") -> prootSha = line.removePrefix("proot:").trim()
                line.startsWith("loader:") -> loaderSha = line.removePrefix("loader:").trim()
                line.startsWith("libproot.so:") -> libprootSha = line.removePrefix("libproot.so:").trim()
                line.startsWith("libproot_loader.so:") -> libloaderSha = line.removePrefix("libproot_loader.so:").trim()
            }
        }

        if (commit.isBlank() || prootSha.isBlank()) return null
        return ProotManifest(
            version = version,
            commit = commit,
            abi = abi,
            arch = arch,
            prootSha256 = prootSha,
            loaderSha256 = loaderSha,
            libprootSha256 = libprootSha,
            libloaderSha256 = libloaderSha
        )
    }

    /**
     * Verifies SHA-256 of the given file against the expected hash.
     */
    fun verifyChecksum(file: File, expectedHash: String): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(64 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return actual.equals(expectedHash.trim(), ignoreCase = true)
    }

    /**
     * Checks if an update is available upstream via git ls-remote.
     */
    fun checkRemoteUpdate(repoUrl: String = DEFAULT_REPO_URL): ProotUpdateCheckResult {
        val manifest = readBundledManifest()
        val currentCommit = manifest?.commit ?: "unknown"

        return try {
            val process = ProcessBuilder("git", "ls-remote", repoUrl, "HEAD").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()

            val remoteCommit = line?.split(Regex("\\s+"))?.firstOrNull() ?: ""
            if (remoteCommit.isNotBlank() && remoteCommit != currentCommit) {
                ProotUpdateCheckResult(
                    updateAvailable = true,
                    currentCommit = currentCommit,
                    latestCommit = remoteCommit,
                    details = "New PRoot commit available upstream ($currentCommit -> $remoteCommit)"
                )
            } else {
                ProotUpdateCheckResult(
                    updateAvailable = false,
                    currentCommit = currentCommit,
                    latestCommit = if (remoteCommit.isNotBlank()) remoteCommit else currentCommit,
                    details = "Bundled PRoot binary is up to date ($currentCommit)"
                )
            }
        } catch (e: Exception) {
            ProotUpdateCheckResult(
                updateAvailable = false,
                currentCommit = currentCommit,
                latestCommit = currentCommit,
                details = "Remote update check skipped: ${e.message}"
            )
        }
    }
}
