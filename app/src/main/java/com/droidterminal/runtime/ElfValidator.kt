package com.droidterminal.runtime

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ElfValidationResult(
    val isValid: Boolean,
    val is64Bit: Boolean,
    val isLittleEndian: Boolean,
    val machine: Int,
    val type: Int,
    val entryPoint: Long,
    val typeName: String,
    val interpreter: String? = null,
    val detail: String
)

object ElfValidator {

    private val ELF_MAGIC = byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
    const val EM_AARCH64 = 0xB7 // 183 decimal

    /**
     * Inspects and validates an ELF executable or shared library for ARM64 compatibility.
     */
    fun validateArm64Elf(file: File): ElfValidationResult {
        if (!file.exists() || !file.isFile || file.length() < 64) {
            return ElfValidationResult(
                isValid = false,
                is64Bit = false,
                isLittleEndian = false,
                machine = 0,
                type = 0,
                entryPoint = 0L,
                typeName = "INVALID",
                detail = "File does not exist or is smaller than ELF header (<64 bytes)"
            )
        }

        return try {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(64)
                raf.seek(0)
                val read = raf.read(header)
                if (read < 64) {
                    return ElfValidationResult(false, false, false, 0, 0, 0L, "INVALID", detail = "Incomplete ELF header")
                }

                // Check ELF Magic: 0x7F 'E' 'L' 'F'
                for (i in 0..3) {
                    if (header[i] != ELF_MAGIC[i]) {
                        val magicHex = header.take(4).joinToString("") { "%02x".format(it) }
                        return ElfValidationResult(false, false, false, 0, 0, 0L, "INVALID", detail = "Invalid ELF magic: 0x$magicHex")
                    }
                }

                val is64Bit = header[4].toInt() == 0x02
                if (!is64Bit) {
                    return ElfValidationResult(false, false, false, 0, 0, 0L, "ELF32", detail = "32-bit ELF binary not supported for ARM64 target")
                }

                val isLittleEndian = header[5].toInt() == 0x01
                val byteBuffer = ByteBuffer.wrap(header).order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)

                val elfType = byteBuffer.getShort(16).toInt() and 0xFFFF
                val eMachine = byteBuffer.getShort(18).toInt() and 0xFFFF
                val entryPoint = byteBuffer.getLong(24)
                val phOff = byteBuffer.getLong(32)
                val phEntSize = byteBuffer.getShort(54).toInt() and 0xFFFF
                val phNum = byteBuffer.getShort(56).toInt() and 0xFFFF

                if (eMachine != EM_AARCH64) {
                    val hexMachine = "0x" + "%02x".format(eMachine)
                    return ElfValidationResult(
                        isValid = false,
                        is64Bit = is64Bit,
                        isLittleEndian = isLittleEndian,
                        machine = eMachine,
                        type = elfType,
                        entryPoint = entryPoint,
                        typeName = "MACHINE_$eMachine",
                        detail = "ELF machine $hexMachine is not AArch64 (expected 0x${"%02x".format(EM_AARCH64)})"
                    )
                }

                // Parse Program Headers for PT_INTERP (type 3)
                var interpreterPath: String? = null
                if (phOff > 0 && phNum > 0 && phEntSize >= 56 && phOff < raf.length()) {
                    val phdrBuffer = ByteArray(phEntSize)
                    for (i in 0 until phNum) {
                        val currentPhOffset = phOff + (i * phEntSize)
                        if (currentPhOffset + phEntSize > raf.length()) break
                        raf.seek(currentPhOffset)
                        if (raf.read(phdrBuffer) != phEntSize) break

                        val phBb = ByteBuffer.wrap(phdrBuffer).order(if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
                        val pType = phBb.getInt(0)
                        if (pType == 3) { // PT_INTERP
                            val interpOffset = phBb.getLong(8)
                            val interpSize = phBb.getLong(32)
                            if (interpOffset > 0 && interpSize in 1..4096 && interpOffset + interpSize <= raf.length()) {
                                raf.seek(interpOffset)
                                val interpBytes = ByteArray(interpSize.toInt())
                                val readBytes = raf.read(interpBytes)
                                if (readBytes > 0) {
                                    interpreterPath = String(interpBytes, 0, readBytes, Charsets.UTF_8).trimEnd { it == '\u0000' }
                                }
                            }
                            break
                        }
                    }
                }

                val typeName = when (elfType) {
                    2 -> "EXEC (Executable)"
                    3 -> "DYN (Position-Independent Executable / Shared Object)"
                    else -> "TYPE_$elfType"
                }

                val interpInfo = if (interpreterPath != null) " [interpreter=$interpreterPath]" else ""
                ElfValidationResult(
                    isValid = true,
                    is64Bit = true,
                    isLittleEndian = isLittleEndian,
                    machine = eMachine,
                    type = elfType,
                    entryPoint = entryPoint,
                    typeName = typeName,
                    interpreter = interpreterPath,
                    detail = "Valid ARM64 ELF64 binary ($typeName)$interpInfo"
                )
            }
        } catch (e: Exception) {
            ElfValidationResult(
                isValid = false,
                is64Bit = false,
                isLittleEndian = false,
                machine = 0,
                type = 0,
                entryPoint = 0L,
                typeName = "ERROR",
                detail = "ELF parse error: ${e.message}"
            )
        }
    }
}
