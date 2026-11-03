package com.droidterminal.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droidterminal.runtime.LinuxRuntimeProvider
import com.droidterminal.runtime.NativePtyBridge
import com.droidterminal.runtime.StandaloneRuntimeProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

data class TerminalSearchMatch(
    val lineIndex: Int,
    val isScrollback: Boolean,
    val colStart: Int,
    val length: Int
)

class TerminalSearchState {
    var query by mutableStateOf("")
    var isSearchActive by mutableStateOf(false)
    var currentMatchIndex by mutableIntStateOf(-1)
    var matches by mutableStateOf<List<TerminalSearchMatch>>(emptyList())

    fun clear() {
        query = ""
        isSearchActive = false
        currentMatchIndex = -1
        matches = emptyList()
    }

    fun nextMatch() {
        if (matches.isNotEmpty()) {
            currentMatchIndex = (currentMatchIndex + 1) % matches.size
        }
    }

    fun prevMatch() {
        if (matches.isNotEmpty()) {
            currentMatchIndex = if (currentMatchIndex <= 0) matches.size - 1 else currentMatchIndex - 1
        }
    }
}

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
    val searchState = TerminalSearchState()

    var isRunning by mutableStateOf(false)
    var isExited by mutableStateOf(false)
    var exitCode by mutableIntStateOf(-1)

    // Generation counter to trigger Canvas redraws in Compose
    var updateCounter by mutableIntStateOf(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var readJob: Job? = null
    private var sessionTmpDir: File? = null

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

            // Dedicated, collision-free host temporary directory for this session
            val hostTmpBase = (runtimeProvider as? StandaloneRuntimeProvider)?.getTmpDirectory()
                ?: File(runtimeProvider.getRootfsDirectory().parentFile ?: runtimeProvider.getRootfsDirectory(), "tmp")
            val hostTmp = File(hostTmpBase, "session_$id").apply {
                if (!exists()) mkdirs()
                setReadable(true, false)
                setWritable(true, false)
                setExecutable(true, false)
                try {
                    android.system.Os.chmod(absolutePath, 509) // 0777 octal
                } catch (_: Throwable) {}
            }
            sessionTmpDir = hostTmp

            val loaderExecutable = (runtimeProvider as? StandaloneRuntimeProvider)?.let {
                try {
                    com.droidterminal.runtime.RuntimeAssetsManager(null, it.linuxDir).resolveLoader()
                } catch (_: Throwable) {
                    null
                }
            }

            // Problem A: Guest working directory must always be a guest path, never a host path
            val guestWorkingDir = (runtimeProvider as? StandaloneRuntimeProvider)?.getGuestWorkingDirectory() ?: "/root"

            val prootConfig = com.droidterminal.runtime.ProotSessionConfig(
                rootfsDir = runtimeProvider.getRootfsDirectory(),
                prootExecutable = runtimeProvider.getProotExecutable(),
                loaderExecutable = loaderExecutable,
                tmpDir = hostTmp,
                workingDir = guestWorkingDir,
                executable = "/bin/bash",
                args = listOf("-l"),
                customEnv = runtimeProvider.getEnvironment(),
                customBinds = runtimeProvider.getBindMounts(),
                cols = cols,
                rows = rows
            )

            nativeSessionId = com.droidterminal.runtime.ProotLauncher.spawnInteractivePtySession(prootConfig)

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

    fun performSearch(query: String) {
        if (query.isBlank()) {
            searchState.clear()
            updateCounter++
            return
        }
        searchState.query = query
        searchState.isSearchActive = true
        val found = mutableListOf<TerminalSearchMatch>()

        synchronized(buffer) {
            // 1. Search in scrollback buffer
            val scrollbackSize = buffer.scrollback.size()
            for (i in 0 until scrollbackSize) {
                val line = buffer.scrollback.getLine(i) ?: continue
                val sb = StringBuilder()
                for (cell in line) {
                    if (cell.codePoint != 0) {
                        sb.append(Character.toChars(cell.codePoint))
                    } else {
                        sb.append(' ')
                    }
                }
                val text = sb.toString()
                var idx = text.indexOf(query, ignoreCase = true)
                while (idx >= 0) {
                    found.add(TerminalSearchMatch(lineIndex = i, isScrollback = true, colStart = idx, length = query.length))
                    idx = text.indexOf(query, idx + 1, ignoreCase = true)
                }
            }

            // 2. Search in active screen
            val screen = buffer.activeScreen
            for (r in 0 until rows) {
                val sb = StringBuilder()
                for (c in 0 until cols) {
                    val cell = screen[r][c]
                    if (cell.codePoint != 0) {
                        sb.append(Character.toChars(cell.codePoint))
                    } else {
                        sb.append(' ')
                    }
                }
                val text = sb.toString()
                var idx = text.indexOf(query, ignoreCase = true)
                while (idx >= 0) {
                    found.add(TerminalSearchMatch(lineIndex = r, isScrollback = false, colStart = idx, length = query.length))
                    idx = text.indexOf(query, idx + 1, ignoreCase = true)
                }
            }
        }

        searchState.matches = found
        searchState.currentMatchIndex = if (found.isNotEmpty()) 0 else -1
        updateCounter++
    }

    fun close() {
        isRunning = false
        readJob?.cancel()
        scope.cancel()
        if (nativeSessionId >= 0) {
            NativePtyBridge.nativeCloseSession(nativeSessionId)
            nativeSessionId = -1
        }
        // Safely delete session-specific host temporary files only after PRoot process exits
        sessionTmpDir?.let { dir ->
            try {
                if (dir.exists()) {
                    dir.deleteRecursively()
                }
            } catch (_: Throwable) {}
        }
    }
}
