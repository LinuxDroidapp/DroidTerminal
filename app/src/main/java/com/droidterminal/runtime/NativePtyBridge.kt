package com.droidterminal.runtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

object NativePtyBridge {
    init {
        try {
            System.loadLibrary("droidterm_native")
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("Native library droidterm_native not loaded: ${e.message}")
        }
    }

    external fun nativeCreateSession(
        rootfsPath: String,
        prootBinPath: String,
        executable: String,
        args: Array<String>?,
        workingDir: String,
        envKeys: Array<String>?,
        envValues: Array<String>?,
        bindSources: Array<String>?,
        bindTargets: Array<String>?,
        isProot: Boolean,
        cols: Int,
        rows: Int
    ): Int

    external fun nativeReadSession(
        sessionId: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int

    external fun nativeWriteSession(
        sessionId: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int
    ): Int

    external fun nativeResizeSession(
        sessionId: Int,
        cols: Int,
        rows: Int
    ): Boolean

    external fun nativeSendSignal(
        sessionId: Int,
        signal: Int
    ): Boolean

    external fun nativeCloseSession(sessionId: Int)

    external fun nativeIsSessionAlive(sessionId: Int): Boolean

    external fun nativeGetSessionExitCode(sessionId: Int): Int
}
