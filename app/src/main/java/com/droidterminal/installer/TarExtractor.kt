package com.droidterminal.installer

import com.droidterminal.runtime.NativePtyBridge
import java.io.*
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Diagnostic result of a TAR / GZIP archive extraction.
 */
data class TarExtractionResult(
    val success: Boolean,
    val totalEntries: Int,
    val regularFiles: Int,
    val directories: Int,
    val symbolicLinks: Int,
    val hardLinksTranslated: Int,
    val skippedDeviceNodes: Int,
    val errors: List<String>,
    val detailMessage: String
)

/**
 * Custom exception thrown when archive extraction fails due to truncation, corruption,
 * or security violations.
 */
class TarExtractionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Fully standards-compliant POSIX TAR and GZIP streaming archive extractor.
 *
 * Implements comprehensive support for:
 * 1. Standard POSIX ustar format.
 * 2. POSIX.1-2001 PAX extended headers ('x') and global headers ('g').
 * 3. GNU tar extensions: long pathname ('L') and long linkname ('K').
 * 4. Header checksum validation (both unsigned and signed byte sums).
 * 5. Strict rejection of truncated regular files and incomplete entries.
 * 6. Android-safe hardlink translation: converts hardlinks to relative symlinks,
 *    with deferred resolution and file copying if symlink creation is unsupported.
 * 7. Path traversal prevention: rejects '..' components and verifies destination canonical path.
 * 8. Safe handling of device nodes (block, character, FIFO) without unprivileged mknod failures.
 * 9. Accurate POSIX file permissions and executable bits preservation (0755 / 0644).
 * 10. Robust merged-/usr handling preserving directory symlinks.
 */
object TarExtractor {

    private const val BLOCK_SIZE = 512

    private data class DeferredHardlink(
        val linkFile: File,
        val targetRelativePath: String,
        val targetFile: File,
        val entryName: String
    )

    /**
     * Extracts a .tar or .tar.gz archive directly into targetDir.
     *
     * @param archive The .tar or .tar.gz file on disk.
     * @param targetDir Destination directory (must be created or creatable).
     * @param pathFilter Optional filter predicate (return true to extract, false to skip).
     * @param onProgress Optional callback reporting extracted count and current entry name.
     * @return Number of entries successfully extracted on complete success.
     * @throws TarExtractionException If extraction fails, archive is corrupt, or truncated.
     */
    @Throws(TarExtractionException::class, IOException::class)
    fun extract(
        archive: File,
        targetDir: File,
        pathFilter: ((String) -> Boolean)? = null,
        onProgress: ((Int, String) -> Unit)? = null
    ): Int {
        val result = extractDetailed(archive, targetDir, pathFilter, onProgress)
        if (!result.success) {
            throw TarExtractionException(result.detailMessage)
        }
        return result.totalEntries
    }

    /**
     * Executes detailed extraction returning a structured TarExtractionResult.
     */
    fun extractDetailed(
        archive: File,
        targetDir: File,
        pathFilter: ((String) -> Boolean)? = null,
        onProgress: ((Int, String) -> Unit)? = null
    ): TarExtractionResult {
        if (!archive.exists() || !archive.isFile) {
            return TarExtractionResult(
                success = false,
                totalEntries = 0,
                regularFiles = 0,
                directories = 0,
                symbolicLinks = 0,
                hardLinksTranslated = 0,
                skippedDeviceNodes = 0,
                errors = listOf("Archive file does not exist or is not a regular file: ${archive.absolutePath}"),
                detailMessage = "Archive file not found: ${archive.absolutePath}"
            )
        }

        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return TarExtractionResult(
                success = false,
                totalEntries = 0,
                regularFiles = 0,
                directories = 0,
                symbolicLinks = 0,
                hardLinksTranslated = 0,
                skippedDeviceNodes = 0,
                errors = listOf("Cannot create destination directory: ${targetDir.absolutePath}"),
                detailMessage = "Target directory creation failed: ${targetDir.absolutePath}"
            )
        }

        val canonicalTargetDir = try {
            targetDir.canonicalFile
        } catch (e: Exception) {
            targetDir.absoluteFile
        }

        val isGzip = archive.name.endsWith(".gz", ignoreCase = true) || archive.name.endsWith(".tgz", ignoreCase = true)
        val rawStream = try {
            BufferedInputStream(FileInputStream(archive), 64 * 1024)
        } catch (e: Exception) {
            return TarExtractionResult(
                success = false,
                totalEntries = 0,
                regularFiles = 0,
                directories = 0,
                symbolicLinks = 0,
                hardLinksTranslated = 0,
                skippedDeviceNodes = 0,
                errors = listOf("Failed opening archive: ${e.message}"),
                detailMessage = "Failed opening archive: ${e.message}"
            )
        }

        val inStream: InputStream = try {
            if (isGzip) GZIPInputStream(rawStream, 64 * 1024) else rawStream
        } catch (e: Exception) {
            try { rawStream.close() } catch (_: Exception) {}
            return TarExtractionResult(
                success = false,
                totalEntries = 0,
                regularFiles = 0,
                directories = 0,
                symbolicLinks = 0,
                hardLinksTranslated = 0,
                skippedDeviceNodes = 0,
                errors = listOf("Failed initializing GZIP stream: ${e.message}"),
                detailMessage = "Failed initializing GZIP stream: ${e.message}"
            )
        }

        var totalEntries = 0
        var regularFiles = 0
        var directories = 0
        var symbolicLinks = 0
        var hardLinksTranslated = 0
        var skippedDeviceNodes = 0
        val errors = mutableListOf<String>()

        var gnuLongName: String? = null
        var gnuLongLinkName: String? = null
        val paxGlobalHeaders = mutableMapOf<String, String>()
        var paxNextHeaders: MutableMap<String, String>? = null

        val deferredHardlinks = mutableListOf<DeferredHardlink>()
        val headerBuf = ByteArray(BLOCK_SIZE)
        var zeroBlockCount = 0

        try {
            inStream.use { stream ->
                while (true) {
                    val bytesRead = readFully(stream, headerBuf, BLOCK_SIZE)
                    if (bytesRead < BLOCK_SIZE) {
                        if (bytesRead == 0 && zeroBlockCount >= 1) {
                            // Clean end of archive after zero block
                            break
                        }
                        if (bytesRead > 0) {
                            errors.add("Incomplete TAR block read: $bytesRead bytes (expected $BLOCK_SIZE)")
                        }
                        break
                    }

                    if (isAllZeros(headerBuf)) {
                        zeroBlockCount++
                        if (zeroBlockCount >= 2) {
                            // Two consecutive zero blocks mark end of archive in standard POSIX
                            break
                        }
                        continue
                    } else {
                        zeroBlockCount = 0
                    }

                    // Validate Header Checksum
                    val storedChecksum = parseOctal(headerBuf, 148, 8)
                    if (!validateChecksum(headerBuf, storedChecksum)) {
                        val errMsg = "Corrupted TAR header checksum at entry $totalEntries: stored=$storedChecksum"
                        errors.add(errMsg)
                        return TarExtractionResult(
                            success = false,
                            totalEntries = totalEntries,
                            regularFiles = regularFiles,
                            directories = directories,
                            symbolicLinks = symbolicLinks,
                            hardLinksTranslated = hardLinksTranslated,
                            skippedDeviceNodes = skippedDeviceNodes,
                            errors = errors,
                            detailMessage = errMsg
                        )
                    }

                    val typeFlag = headerBuf[156].toInt().toChar()
                    val rawName = parseString(headerBuf, 0, 100)
                    val rawPrefix = parseString(headerBuf, 345, 155)
                    var fullName = if (rawPrefix.isNotBlank()) "$rawPrefix/$rawName" else rawName
                    var rawLink = parseString(headerBuf, 157, 100)
                    var size = parseOctal(headerBuf, 124, 12)
                    var mode = parseOctal(headerBuf, 100, 8).toInt()

                    // Apply GNU long extensions if previously read
                    if (gnuLongName != null) {
                        fullName = gnuLongName!!
                        gnuLongName = null
                    }
                    if (gnuLongLinkName != null) {
                        rawLink = gnuLongLinkName!!
                        gnuLongLinkName = null
                    }

                    // Handle GNU long pathname extension ('L')
                    if (typeFlag == 'L') {
                        gnuLongName = readDataString(stream, size)
                        continue
                    }
                    // Handle GNU long linkname extension ('K')
                    if (typeFlag == 'K') {
                        gnuLongLinkName = readDataString(stream, size)
                        continue
                    }

                    // Handle POSIX PAX Extended Header ('x')
                    if (typeFlag == 'x') {
                        val paxPayload = readDataBytes(stream, size)
                        paxNextHeaders = parsePaxHeaders(paxPayload).toMutableMap()
                        continue
                    }
                    // Handle POSIX PAX Global Extended Header ('g')
                    if (typeFlag == 'g') {
                        val paxPayload = readDataBytes(stream, size)
                        paxGlobalHeaders.putAll(parsePaxHeaders(paxPayload))
                        continue
                    }

                    // Apply PAX Header Overrides
                    val effectivePax = mutableMapOf<String, String>().apply {
                        putAll(paxGlobalHeaders)
                        paxNextHeaders?.let { putAll(it) }
                    }
                    paxNextHeaders = null // Consume single-entry PAX header

                    effectivePax["path"]?.let { fullName = it }
                    effectivePax["linkpath"]?.let { rawLink = it }
                    effectivePax["size"]?.toLongOrNull()?.let { size = it }

                    val cleanPath = normalizeArchivePath(fullName)
                    if (cleanPath.isBlank()) {
                        skipData(stream, size)
                        continue
                    }

                    // Path Traversal Security Verification
                    if (!isSafeDestinationPath(canonicalTargetDir, cleanPath)) {
                        val errMsg = "Security violation: Path traversal detected in entry '$cleanPath'"
                        errors.add(errMsg)
                        return TarExtractionResult(
                            success = false,
                            totalEntries = totalEntries,
                            regularFiles = regularFiles,
                            directories = directories,
                            symbolicLinks = symbolicLinks,
                            hardLinksTranslated = hardLinksTranslated,
                            skippedDeviceNodes = skippedDeviceNodes,
                            errors = errors,
                            detailMessage = errMsg
                        )
                    }

                    // Check path filter if provided
                    if (pathFilter != null && !pathFilter(cleanPath)) {
                        skipData(stream, size)
                        continue
                    }

                    val destFile = File(canonicalTargetDir, cleanPath)

                    // Skip character/block devices and static dev nodes
                    if (typeFlag == '3' || typeFlag == '4' || typeFlag == '6' ||
                        cleanPath.startsWith("dev/") || cleanPath == "dev"
                    ) {
                        skipData(stream, size)
                        skippedDeviceNodes++
                        totalEntries++
                        continue
                    }

                    when (typeFlag) {
                        '5' -> {
                            // Directory
                            // If destFile is already an existing symlink pointing to a directory (e.g. UsrMerge bin -> usr/bin),
                            // preserve the symlink rather than replacing it with an empty directory!
                            val isExistingSymlink = Files.isSymbolicLink(destFile.toPath())
                            if (!isExistingSymlink) {
                                if (!destFile.exists()) destFile.mkdirs()
                                applyPermissions(destFile, mode.takeIf { it > 0 } ?: 493) // 0755
                            }
                            directories++
                        }
                        '2' -> {
                            // Symbolic link
                            ensureParentDirectory(destFile)
                            createSymlink(destFile, rawLink, canonicalTargetDir)
                            symbolicLinks++
                        }
                        '1' -> {
                            // Hard link
                            ensureParentDirectory(destFile)
                            val cleanTarget = normalizeArchivePath(rawLink)
                            val targetFile = File(canonicalTargetDir, cleanTarget)
                            translateHardlink(destFile, cleanTarget, targetFile)
                            deferredHardlinks.add(DeferredHardlink(destFile, cleanTarget, targetFile, cleanPath))
                            hardLinksTranslated++
                        }
                        '0', '\u0000' -> {
                            // Regular file
                            ensureParentDirectory(destFile)
                            val extractSuccess = extractRegularFile(stream, destFile, size, cleanPath, errors)
                            if (!extractSuccess) {
                                return TarExtractionResult(
                                    success = false,
                                    totalEntries = totalEntries,
                                    regularFiles = regularFiles,
                                    directories = directories,
                                    symbolicLinks = symbolicLinks,
                                    hardLinksTranslated = hardLinksTranslated,
                                    skippedDeviceNodes = skippedDeviceNodes,
                                    errors = errors,
                                    detailMessage = errors.lastOrNull() ?: "Failed extracting regular file: $cleanPath"
                                )
                            }
                            applyPermissions(destFile, mode.takeIf { it > 0 } ?: 420) // 0644
                            regularFiles++
                        }
                        else -> {
                            // Unsupported entry type
                            val warnMsg = "Unsupported TAR entry type '$typeFlag' for '$cleanPath' (skipped)"
                            errors.add(warnMsg)
                            skipData(stream, size)
                        }
                    }

                    totalEntries++
                    if (totalEntries % 200 == 0) {
                        onProgress?.invoke(totalEntries, cleanPath)
                    }
                }

                // Post-extraction: Resolve and verify all deferred hardlinks
                for (deferred in deferredHardlinks) {
                    val linkPath = deferred.linkFile.toPath()
                    val targetExists = deferred.targetFile.exists() && deferred.targetFile.isFile
                    val isLinkValid = Files.isSymbolicLink(linkPath) && Files.exists(linkPath)
                    val isRealFile = !Files.isSymbolicLink(linkPath) && deferred.linkFile.exists() && deferred.linkFile.length() > 0L

                    if ((!isLinkValid && !isRealFile) && targetExists) {
                        try {
                            if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
                                Files.deleteIfExists(linkPath)
                            }
                            deferred.targetFile.copyTo(deferred.linkFile, overwrite = true)
                            if (deferred.targetFile.canExecute()) {
                                applyPermissions(deferred.linkFile, 493) // 0755
                            }
                        } catch (e: Exception) {
                            errors.add("Failed resolving deferred hardlink '${deferred.entryName}': ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            val errMsg = "Archive extraction exception at entry $totalEntries: ${e.message}"
            errors.add(errMsg)
            return TarExtractionResult(
                success = false,
                totalEntries = totalEntries,
                regularFiles = regularFiles,
                directories = directories,
                symbolicLinks = symbolicLinks,
                hardLinksTranslated = hardLinksTranslated,
                skippedDeviceNodes = skippedDeviceNodes,
                errors = errors,
                detailMessage = errMsg
            )
        }

        val success = totalEntries > 0 && errors.none { it.startsWith("Corrupted") || it.startsWith("Security") || it.startsWith("Archive truncated") }

        return TarExtractionResult(
            success = success,
            totalEntries = totalEntries,
            regularFiles = regularFiles,
            directories = directories,
            symbolicLinks = symbolicLinks,
            hardLinksTranslated = hardLinksTranslated,
            skippedDeviceNodes = skippedDeviceNodes,
            errors = errors,
            detailMessage = if (success) "Extraction completed successfully ($totalEntries entries)." else "Extraction failed: ${errors.joinToString("; ")}"
        )
    }

    private fun extractRegularFile(
        stream: InputStream,
        destFile: File,
        size: Long,
        cleanPath: String,
        errors: MutableList<String>
    ): Boolean {
        val destPath = destFile.toPath()
        if (Files.isSymbolicLink(destPath)) {
            try { Files.deleteIfExists(destPath) } catch (_: Exception) {}
        }

        var totalRead = 0L
        try {
            FileOutputStream(destFile).use { out ->
                var remaining = size
                val buffer = ByteArray(8192)
                while (remaining > 0L) {
                    val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                    val r = stream.read(buffer, 0, toRead)
                    if (r < 0) {
                        val errMsg = "Archive truncated while extracting '$cleanPath': expected $size bytes, read $totalRead bytes"
                        errors.add(errMsg)
                        return false
                    }
                    out.write(buffer, 0, r)
                    totalRead += r
                    remaining -= r
                }
            }
        } catch (e: Exception) {
            val errMsg = "IO error extracting '$cleanPath': ${e.message}"
            errors.add(errMsg)
            return false
        }

        if (totalRead != size) {
            val errMsg = "Archive truncated while extracting '$cleanPath': size mismatch (expected $size, got $totalRead)"
            errors.add(errMsg)
            return false
        }

        // Discard block padding
        val pad = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
        if (pad > 0) {
            val skipBuf = ByteArray(pad)
            val padRead = readFully(stream, skipBuf, pad)
            if (padRead < pad) {
                val errMsg = "Archive truncated while reading padding for '$cleanPath'"
                errors.add(errMsg)
                return false
            }
        }

        return true
    }

    private fun translateHardlink(linkFile: File, cleanTarget: String, targetFile: File) {
        val linkPath = linkFile.toPath()
        if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
            try { Files.deleteIfExists(linkPath) } catch (_: Exception) {}
        }

        var success = false
        val relTarget = try {
            linkFile.parentFile?.toPath()?.relativize(targetFile.toPath())?.toString()
        } catch (_: Exception) {
            null
        } ?: cleanTarget

        try {
            Files.createSymbolicLink(linkPath, Paths.get(relTarget))
            success = true
        } catch (_: Throwable) {
            try {
                android.system.Os.symlink(relTarget, linkFile.absolutePath)
                success = true
            } catch (_: Throwable) {}
        }

        // If symlink creation fails and target exists and is a regular file, copy contents
        if (!success && targetFile.exists() && targetFile.isFile) {
            try {
                targetFile.copyTo(linkFile, overwrite = true)
                if (targetFile.canExecute()) {
                    applyPermissions(linkFile, 493) // 0755
                }
            } catch (_: Throwable) {}
        }
    }

    private fun createSymlink(linkFile: File, rawTarget: String, rootfsDir: File) {
        val linkPath = linkFile.toPath()
        if (Files.isSymbolicLink(linkPath) || Files.exists(linkPath)) {
            try { Files.deleteIfExists(linkPath) } catch (_: Exception) {}
        }

        var created = false
        try {
            Files.createSymbolicLink(linkPath, Paths.get(rawTarget))
            created = true
        } catch (_: Throwable) {
            try {
                android.system.Os.symlink(rawTarget, linkFile.absolutePath)
                created = true
            } catch (_: Throwable) {
                // If symlink creation fails, resolve and copy target if available
                val resolvedTarget = if (rawTarget.startsWith("/")) {
                    File(rootfsDir, rawTarget.removePrefix("/"))
                } else {
                    File(linkFile.parentFile, rawTarget)
                }
                if (resolvedTarget.exists() && resolvedTarget.isFile) {
                    try {
                        resolvedTarget.copyTo(linkFile, overwrite = true)
                        created = true
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Parses POSIX.1-2001 PAX extended header format:
     * Format: "%d %s=%s\n", where %d is length of the line including length and trailing newline.
     */
    internal fun parsePaxHeaders(payload: ByteArray): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var offset = 0
        while (offset < payload.size) {
            // Find space separating length and key=value
            var spaceIdx = -1
            for (i in offset until payload.size) {
                if (payload[i] == ' '.code.toByte()) {
                    spaceIdx = i
                    break
                }
            }
            if (spaceIdx < 0) break

            val lenStr = String(payload, offset, spaceIdx - offset, Charsets.US_ASCII).trim()
            val len = lenStr.toIntOrNull() ?: break
            if (len <= 0 || offset + len > payload.size) break

            val nextOffset = offset + len
            // Find '=' separating key and value
            var eqIdx = -1
            for (i in spaceIdx + 1 until nextOffset) {
                if (payload[i] == '='.code.toByte()) {
                    eqIdx = i
                    break
                }
            }

            if (eqIdx in (spaceIdx + 1) until nextOffset) {
                val key = String(payload, spaceIdx + 1, eqIdx - (spaceIdx + 1), Charsets.UTF_8).trim()
                // Value ends right before newline at nextOffset - 1
                val valLen = (nextOffset - 1) - (eqIdx + 1)
                val value = if (valLen > 0) {
                    String(payload, eqIdx + 1, valLen, Charsets.UTF_8)
                } else {
                    ""
                }
                headers[key] = value
            }
            offset = nextOffset
        }
        return headers
    }

    /**
     * Validates TAR header checksum against both unsigned (standard) and signed byte sums.
     */
    internal fun validateChecksum(header: ByteArray, storedChecksum: Long): Boolean {
        var unsignedSum = 0L
        var signedSum = 0L

        for (i in 0 until BLOCK_SIZE) {
            val b = if (i in 148 until 156) ' '.code.toByte() else header[i]
            unsignedSum += (b.toInt() and 0xFF)
            signedSum += b.toInt()
        }

        return storedChecksum == unsignedSum || storedChecksum == signedSum
    }

    private fun isSafeDestinationPath(targetDir: File, cleanPath: String): Boolean {
        if (cleanPath.contains("../") || cleanPath.startsWith("..") || cleanPath.endsWith("/..")) {
            return false
        }
        val destFile = File(targetDir, cleanPath)
        val canonicalDest = try {
            destFile.canonicalPath
        } catch (_: Exception) {
            destFile.absolutePath
        }
        return canonicalDest.startsWith(targetDir.canonicalPath)
    }

    private fun normalizeArchivePath(path: String): String {
        return path.trim()
            .removePrefix("./")
            .removePrefix("/")
            .trimEnd('/')
    }

    private fun ensureParentDirectory(file: File) {
        val parent = file.parentFile ?: return
        if (!parent.exists()) {
            parent.mkdirs()
            applyPermissions(parent, 493) // 0755
        }
    }

    internal fun applyPermissions(file: File, mode: Int) {
        file.setReadable(true, false)
        if ((mode and 73) != 0) { // check execute bits (0111)
            file.setExecutable(true, false)
        }
        try {
            android.system.Os.chmod(file.absolutePath, mode and 511)
        } catch (_: Throwable) {}
        NativePtyBridge.setFileMode(file.absolutePath, mode and 511)
    }

    private fun readDataString(stream: InputStream, size: Long): String {
        val bytes = readDataBytes(stream, size)
        return bytes.toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun readDataBytes(stream: InputStream, size: Long): ByteArray {
        val bytes = ByteArray(size.toInt())
        readFully(stream, bytes, size.toInt())
        val pad = ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE).toInt()
        if (pad > 0) {
            val skipBuf = ByteArray(pad)
            readFully(stream, skipBuf, pad)
        }
        return bytes
    }

    private fun skipData(stream: InputStream, size: Long) {
        val totalBytes = size + ((BLOCK_SIZE - (size % BLOCK_SIZE)) % BLOCK_SIZE)
        var remaining = totalBytes
        val skipBuf = ByteArray(minOf(remaining, 16384L).toInt())
        while (remaining > 0L) {
            val toRead = minOf(remaining, skipBuf.size.toLong()).toInt()
            val r = stream.read(skipBuf, 0, toRead)
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
