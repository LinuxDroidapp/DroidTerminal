package com.droidterminal.installer

import com.droidterminal.runtime.NativePtyBridge
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Pure Kotlin/Java streaming POSIX TAR / GZIP archive extractor.
 *
 * Solves the critical Android rootless extraction flaw:
 * 1. Automatically translates hardlinks (typeflag '1') into relative symbolic links or copies,
 *    overcoming Android unprivileged link(2) EPERM restrictions.
 * 2. Gracefully ignores device nodes (typeflags '3', '4', '6' and ./dev) without aborting.
 * 3. Preserves POSIX file modes, executable bits, and directory structures.
 * 4. Supports GNU long pathname ('L') and long linkname ('K') extensions.
 * 5. Requires ZERO external host binaries (no toybox, tar, md5sum, or cut needed).
 */
object TarExtractor {

    private const val BLOCK_SIZE = 512

    data class TarEntry(
        val name: String,
        val mode: Int,
        val size: Long,
        val typeFlag: Char,
        val linkName: String
    )

    /**
     * Extracts a .tar or .tar.gz archive directly to targetDir.
     *
     * @param archive The .tar or .tar.gz file on disk.
     * @param targetDir Destination directory.
     * @param pathFilter Optional filter predicate (return true to extract, false to skip).
     * @param onProgress Optional callback reporting extracted count and current entry name.
     * @return Number of entries successfully extracted.
     */
    fun extract(
        archive: File,
        targetDir: File,
        pathFilter: ((String) -> Boolean)? = null,
        onProgress: ((Int, String) -> Unit)? = null
    ): Int {
        if (!targetDir.exists()) targetDir.mkdirs()

        val isGzip = archive.name.endsWith(".gz") || archive.name.endsWith(".tgz")
        val rawInput = BufferedInputStream(FileInputStream(archive), 64 * 1024)
        val inStream: InputStream = if (isGzip) GZIPInputStream(rawInput, 64 * 1024) else rawInput

        var count = 0
        var gnuLongName: String? = null
        var gnuLongLinkName: String? = null
        val headerBuf = ByteArray(BLOCK_SIZE)

        inStream.use { stream ->
            val deferredHardlinks = mutableListOf<Pair<File, File>>() // linkFile to targetFile

            while (true) {
                val bytesRead = readFully(stream, headerBuf, BLOCK_SIZE)
                if (bytesRead < BLOCK_SIZE || isAllZeros(headerBuf)) {
                    // Two consecutive zero blocks or EOF mark end of archive
                    break
                }

                val typeFlag = headerBuf[156].toInt().toChar()
                val rawName = parseString(headerBuf, 0, 100)
                val rawPrefix = parseString(headerBuf, 345, 155)
                val fullName = gnuLongName ?: if (rawPrefix.isNotBlank()) "$rawPrefix/$rawName" else rawName
                val rawLink = gnuLongLinkName ?: parseString(headerBuf, 157, 100)
                val size = parseOctal(headerBuf, 124, 12)
                val mode = parseOctal(headerBuf, 100, 8).toInt()

                // Reset GNU long extensions
                gnuLongName = null
                gnuLongLinkName = null

                // Handle GNU long pathname extension
                if (typeFlag == 'L') {
                    gnuLongName = readDataString(stream, size)
                    continue
                }
                // Handle GNU long linkname extension
                if (typeFlag == 'K') {
                    gnuLongLinkName = readDataString(stream, size)
                    continue
                }

                val cleanPath = fullName.trim().removePrefix("./").removePrefix("/")
                if (cleanPath.isBlank()) {
                    skipData(stream, size)
                    continue
                }

                // Check path filter if provided
                if (pathFilter != null && !pathFilter(cleanPath)) {
                    skipData(stream, size)
                    continue
                }

                // Skip character/block devices and static dev nodes
                if (typeFlag == '3' || typeFlag == '4' || typeFlag == '6' ||
                    cleanPath.startsWith("dev/") || cleanPath == "dev"
                ) {
                    skipData(stream, size)
                    continue
                }

                val destFile = File(targetDir, cleanPath)

                when (typeFlag) {
                    '5' -> {
                        // Directory
                        if (!destFile.exists()) destFile.mkdirs()
                        applyPermissions(destFile, mode.takeIf { it > 0 } ?: 493) // default 0755
                    }
                    '2' -> {
                        // Symbolic link
                        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        createSymlink(destFile, rawLink)
                    }
                    '1' -> {
                        // Hard link: Translate hardlink into relative symbolic link or target copy!
                        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        val cleanTarget = rawLink.trim().removePrefix("./").removePrefix("/")
                        val targetFile = File(targetDir, cleanTarget)
                        translateHardlink(destFile, cleanTarget, targetFile)
                        deferredHardlinks.add(destFile to targetFile)
                    }
                    else -> {
                        // Regular file (typeflag '0' or '\0')
                        destFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                        extractFile(stream, destFile, size)
                        applyPermissions(destFile, mode.takeIf { it > 0 } ?: 420) // default 0644
                    }
                }

                count++
                if (count % 200 == 0) {
                    onProgress?.invoke(count, cleanPath)
                }
            }

            // Post-extraction: resolve any deferred hardlinks if target exists and link needs copy
            for ((linkFile, targetFile) in deferredHardlinks) {
                if (targetFile.exists() && targetFile.isFile) {
                    val linkPath = linkFile.toPath()
                    val isValidLink = Files.isSymbolicLink(linkPath) && Files.exists(linkPath)
                    val isRealFile = !Files.isSymbolicLink(linkPath) && linkFile.exists() && linkFile.length() > 0L
                    if (!isValidLink && !isRealFile) {
                        try {
                            Files.deleteIfExists(linkPath)
                            targetFile.copyTo(linkFile, overwrite = true)
                            if (targetFile.canExecute()) {
                                applyPermissions(linkFile, 493)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        }

        return count
    }

    private fun extractFile(stream: InputStream, destFile: File, size: Long) {
        val destPath = destFile.toPath()
        if (Files.isSymbolicLink(destPath)) {
            try { Files.deleteIfExists(destPath) } catch (_: Exception) {}
        }
        FileOutputStream(destFile).use { out ->
            var remaining = size
            val buffer = ByteArray(8192)
            while (remaining > 0) {
                val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                val r = stream.read(buffer, 0, toRead)
                if (r < 0) break
                out.write(buffer, 0, r)
                remaining -= r
            }
        }
        // Discard block padding
        val pad = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
        if (pad > 0) {
            val skipBuf = ByteArray(pad)
            readFully(stream, skipBuf, pad)
        }
    }

    private fun translateHardlink(linkFile: File, rawTarget: String, targetFile: File) {
        val linkPath = linkFile.toPath()
        if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
            try { Files.deleteIfExists(linkPath) } catch (_: Exception) {}
        }

        var success = false
        // Compute relative path from linkFile's parent to targetFile
        val relTarget = try {
            linkFile.parentFile?.toPath()?.relativize(targetFile.toPath())?.toString()
        } catch (_: Exception) {
            null
        } ?: rawTarget.trim().removePrefix("./").removePrefix("/")

        try {
            Files.createSymbolicLink(linkPath, Paths.get(relTarget))
            success = true
        } catch (_: Throwable) {
            try {
                android.system.Os.symlink(relTarget, linkFile.absolutePath)
                success = true
            } catch (_: Throwable) {}
        }

        // If symlink creation fails or target is already present, copy contents
        if (!success && targetFile.exists() && targetFile.isFile) {
            try {
                targetFile.copyTo(linkFile, overwrite = true)
                if (targetFile.canExecute()) {
                    linkFile.setExecutable(true, false)
                }
            } catch (_: Throwable) {}
        }
    }

    private fun createSymlink(linkFile: File, target: String) {
        val linkPath = linkFile.toPath()
        if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
            try { Files.deleteIfExists(linkPath) } catch (_: Exception) {}
        }
        try {
            Files.createSymbolicLink(linkPath, Paths.get(target))
        } catch (_: Throwable) {
            try {
                android.system.Os.symlink(target, linkFile.absolutePath)
            } catch (_: Throwable) {
                // If symlink fails, copy resolved target if file exists
                val resolvedTarget = if (target.startsWith("/")) File(target) else File(linkFile.parentFile, target)
                if (resolvedTarget.exists() && resolvedTarget.isFile) {
                    try { resolvedTarget.copyTo(linkFile, overwrite = true) } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun applyPermissions(file: File, mode: Int) {
        file.setReadable(true, false)
        if ((mode and 73) != 0) { // check any execute bit (0111)
            file.setExecutable(true, false)
        }
        try {
            android.system.Os.chmod(file.absolutePath, mode and 511)
        } catch (_: Throwable) {}
        NativePtyBridge.setFileMode(file.absolutePath, mode and 511)
    }

    private fun readDataString(stream: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        readFully(stream, bytes, size.toInt())
        val pad = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
        if (pad > 0) {
            val skipBuf = ByteArray(pad)
            readFully(stream, skipBuf, pad)
        }
        return bytes.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun skipData(stream: InputStream, size: Long) {
        val totalBytes = size + ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE)
        var remaining = totalBytes
        val skipBuf = ByteArray(minOf(remaining, 16384L).toInt())
        while (remaining > 0) {
            val r = stream.read(skipBuf, 0, minOf(remaining, skipBuf.size.toLong()).toInt())
            if (r < 0) break
            remaining -= r
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray, len: Int): Int {
        var total = 0
        while (total < len) {
            val r = stream.read(buf, total, len - total)
            if (r < 0) break
            total += r
        }
        return total
    }

    private fun isAllZeros(buf: ByteArray): Boolean {
        for (b in buf) {
            if (b.toInt() != 0) return false
        }
        return true
    }

    private fun parseString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && buf[end].toInt() != 0) {
            end++
        }
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
        var result = 0L
        var i = offset
        val end = offset + length
        while (i < end && (buf[i] == ' '.code.toByte() || buf[i] == 0.toByte())) {
            i++
        }
        while (i < end && buf[i] >= '0'.code.toByte() && buf[i] <= '7'.code.toByte()) {
            result = (result shl 3) + (buf[i] - '0'.code.toByte())
            i++
        }
        return result
    }
}
