package com.droidterminal.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droidterminal.runtime.LinuxRuntimeProvider
import com.droidterminal.runtime.NativePtyBridge
import kotlinx.coroutines.*
import java.util.UUID

/**
 * Manages an independent live Linux terminal session, binding a native PTY master FD,
 * a VT parser, a TerminalBuffer, and asynchronous byte streaming.
 */
class TerminalSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "bash",
    val runtimeProvider: LinuxRuntimeProvider,
    var cols: Int = 80,
    var rows: Int = 24
) {
    var nativeSessionId: Int = -1
    val buffer = TerminalBuffer(cols, rows)
    val selection = TerminalSelection()

    var isRunning by mutableStateOf(false)
    var isExited by mutableStateOf(false)
    var exitCode by mutableIntStateOf(-1)

    // Generation counter to trigger Canvas redraws in Compose
    var updateCounter by mutableIntStateOf(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null

    val parser = TerminalParser(
        buffer = buffer,
        onTitleChanged = { newTitle ->
            title = newTitle
        },
        onBell = {
            // Bell trigger
        }
    )

    fun start() {
        if (isRunning) return

        scope.launch {
            runtimeProvider.prepareSession()

            val env = runtimeProvider.getEnvironment()
            val envKeys = env.keys.toTypedArray()
            val envVals = env.values.toTypedArray()

            val binds = runtimeProvider.getBindMounts()
            val bindSrcs = binds.map { it.first }.toTypedArray()
            val bindTgts = binds.map { it.second }.toTypedArray()

            val rootfs = runtimeProvider.getRootfsDirectory().absolutePath
            val proot = runtimeProvider.getProotExecutable().absolutePath

            nativeSessionId = NativePtyBridge.nativeCreateSession(
                rootfsPath = rootfs,
                prootBinPath = proot,
                executable = "/bin/bash",
                args = arrayOf("-l"),
                workingDir = runtimeProvider.getHomeDirectory().absolutePath,
                envKeys = envKeys,
                envValues = envVals,
                bindSources = bindSrcs,
                bindTargets = bindTgts,
                isProot = true,
                cols = cols,
                rows = rows
            )

            if (nativeSessionId < 0) {
                // Standalone fallback: Try native /bin/sh if PRoot binary is not yet populated
                nativeSessionId = NativePtyBridge.nativeCreateSession(
                    rootfsPath = "",
                    prootBinPath = "",
                    executable = "/system/bin/sh",
                    args = arrayOf("-i"),
                    workingDir = "",
                    envKeys = null,
                    envValues = null,
                    bindSources = null,
                    bindTargets = null,
                    isProot = false,
                    cols = cols,
                    rows = rows
                )
            }

            if (nativeSessionId >= 0) {
                isRunning = true
                startReading()
            } else {
                val errMsg = "\r\n[DroidTerminal] Unable to allocate PTY or start Linux shell.\r\n"
                parser.processBytes(errMsg.toByteArray(Charsets.UTF_8))
                updateCounter++
            }
        }
    }

    private fun startReading() {
        readJob = scope.launch {
            val buf = ByteArray(4096)
            while (isRunning && nativeSessionId >= 0) {
                val bytesRead = NativePtyBridge.nativeReadSession(nativeSessionId, buf, 0, buf.size)
                if (bytesRead > 0) {
                    synchronized(buffer) {
                        parser.processBytes(buf, 0, bytesRead)
                    }
                    withContext(Dispatchers.Main) {
                        updateCounter++
                    }
                } else if (bytesRead < 0) {
                    // Process exited or PTY slave closed
                    val alive = NativePtyBridge.nativeIsSessionAlive(nativeSessionId)
                    if (!alive) {
                        val code = NativePtyBridge.nativeGetSessionExitCode(nativeSessionId)
                        withContext(Dispatchers.Main) {
                            isRunning = false
                            isExited = true
                            exitCode = code
                            val msg = "\r\n[Process completed with exit code $code]\r\n"
                            parser.processBytes(msg.toByteArray(Charsets.UTF_8))
                            updateCounter++
                        }
                        break
                    }
                } else {
                    delay(12) // Yield briefly when non-blocking read has no data
                }
            }
        }
    }

    fun write(bytes: ByteArray) {
        if (nativeSessionId >= 0 && isRunning) {
            scope.launch {
                NativePtyBridge.nativeWriteSession(nativeSessionId, bytes, 0, bytes.size)
            }
        }
    }

    fun resize(newCols: Int, newRows: Int) {
        if (newCols <= 0 || newRows <= 0) return
        cols = newCols
        rows = newRows
        synchronized(buffer) {
            buffer.resize(newCols, newRows)
        }
        if (nativeSessionId >= 0 && isRunning) {
            NativePtyBridge.nativeResizeSession(nativeSessionId, newCols, newRows)
        }
        updateCounter++
    }

    fun sendSignal(signal: Int) {
        if (nativeSessionId >= 0 && isRunning) {
            NativePtyBridge.nativeSendSignal(nativeSessionId, signal)
        }
    }

    fun close() {
        isRunning = false
        readJob?.cancel()
        scope.cancel()
        if (nativeSessionId >= 0) {
            NativePtyBridge.nativeCloseSession(nativeSessionId)
            nativeSessionId = -1
        }
    }
}
