# LinuxDroid Embedding & Integration Guide

## 1. Overview
DroidTerminal is architected from the ground up to operate both as a standalone application and as the high-performance native terminal engine embedded inside the **LinuxDroid** application ecosystem (`https://github.com/LinuxDroidapp/LinuxDroid.git`).

When embedded inside LinuxDroid, DroidTerminal **does not download a second rootfs or install redundant PRoot binaries**. Instead, it consumes LinuxDroid's pre-configured Ubuntu/Kali rootfs, PRoot binary, and bind mounts directly via the `LinuxRuntimeProvider` contract.

---

## 2. Integration Architecture

```
+---------------------------------------------------------------------------------+
|                               LINUXDROID APP                                    |
|                                                                                 |
|  - Manages Distribution Images (Ubuntu, Kali, Debian, Parrot)                   |
|  - Downloads & Extracts Rootfs to internal storage                              |
|  - Maintains PRoot static binaries and X11/VNC desktop servers                  |
+---------------------------------------------------------------------------------+
                                       |
                                       | implements LinuxRuntimeProvider
                                       v
+---------------------------------------------------------------------------------+
|                       DROIDTERMINAL EMBEDDING API                               |
|                                                                                 |
|  - LinuxDroidRuntimeProvider                                                    |
|  - Native PTY Engine (Shared forkpty & non-blocking I/O)                        |
|  - VT100 / xterm Emulation Engine (Alternate screen, 24-bit TrueColor)          |
|  - Touch-Optimized Neomorphic Terminal Keyboard                                 |
|  - macOS iTerm2-Inspired UI (Tabs, Split Panes, Floating Windows)               |
+---------------------------------------------------------------------------------+
```

---

## 3. Step-by-Step Integration in LinuxDroid

### Step 1: Add DroidTerminal Module Dependency
In LinuxDroid's `settings.gradle.kts`:
```kotlin
include(":app")
include(":droidterminal")
project(":droidterminal").projectDir = file("path/to/DroidTerminal/app")
```

In LinuxDroid's `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation(project(":droidterminal"))
}
```

### Step 2: Implement `LinuxRuntimeProvider`
Create an adapter inside LinuxDroid that maps LinuxDroid's internal container configuration:

```kotlin
package com.linuxdroid.adapter

import com.droidterminal.runtime.LinuxRuntimeProvider
import com.droidterminal.runtime.RuntimeResult
import java.io.File

class LinuxDroidContainerAdapter(
    private val containerRootfsDir: File,
    private val prootBinaryFile: File,
    private val userHomeDir: File = File(containerRootfsDir, "root"),
    private val customEnvironment: Map<String, String> = emptyMap(),
    private val customMounts: List<Pair<String, String>> = emptyList()
) : LinuxRuntimeProvider {

    override fun getRuntimeName(): String = "LinuxDroid Container"

    override fun getProotExecutable(): File = prootBinaryFile

    override fun getRootfsDirectory(): File = containerRootfsDir

    override fun getHomeDirectory(): File = userHomeDir

    override fun getEnvironment(): Map<String, String> {
        val env = mutableMapOf(
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "HOME" to userHomeDir.absolutePath,
            "USER" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "LINUXDROID_CONTAINER_ID" to containerRootfsDir.name
        )
        env.putAll(customEnvironment)
        return env
    }

    override fun getBindMounts(): List<Pair<String, String>> {
        val mounts = mutableListOf<Pair<String, String>>()
        // Ensure shared memory is mounted
        val tmpDir = File(containerRootfsDir, "tmp")
        if (!tmpDir.exists()) tmpDir.mkdirs()
        mounts.add(tmpDir.absolutePath to "/dev/shm")
        mounts.addAll(customMounts)
        return mounts
    }

    override suspend fun prepareSession(): RuntimeResult {
        if (!containerRootfsDir.exists()) {
            return RuntimeResult.Error("Rootfs directory missing: ${containerRootfsDir.absolutePath}")
        }
        if (!prootBinaryFile.exists()) {
            return RuntimeResult.Error("PRoot binary missing: ${prootBinaryFile.absolutePath}")
        }
        return RuntimeResult.Success
    }
}
```

### Step 3: Embed DroidTerminal Composable in LinuxDroid UI
Inside any LinuxDroid Compose Activity or Fragment:

```kotlin
import androidx.compose.runtime.*
import com.droidterminal.terminal.TerminalSession
import com.droidterminal.terminal.TerminalRenderer
import com.droidterminal.input.LinuxKeyboard

@Composable
fun LinuxDroidTerminalScreen(
    runtimeProvider: LinuxRuntimeProvider
) {
    val session = remember {
        TerminalSession(
            title = "LinuxDroid Terminal",
            runtimeProvider = runtimeProvider,
            cols = 80,
            rows = 24
        ).apply { start() }
    }

    var isKeyboardVisible by remember { mutableStateOf(true) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Terminal Canvas Viewport
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            TerminalRenderer(
                session = session,
                onTap = { isKeyboardVisible = true }
            )
        }

        // Dedicated Neomorphic Linux Keyboard
        LinuxKeyboard(
            visible = isKeyboardVisible,
            isAppCursorMode = session.buffer.applicationCursorMode,
            onHideRequested = { isKeyboardVisible = false },
            onToggleImeRequested = { /* Toggle Android IME */ },
            onSendBytes = { bytes -> session.write(bytes) }
        )
    }
}
```

---

## 4. Key Advantages of DroidTerminal for LinuxDroid
1. **Zero Storage Duplication**: Reuses the rootfs already extracted by LinuxDroid.
2. **True Interactive PTY**: Full support for interactive shells, `sudo`, `vim`, `nano`, `htop`, and `tmux`.
3. **No Root Requirement**: Runs completely in user space using PRoot's `ptrace` virtualization.
4. **iTerm2 Mobile Workflow**: Multiple tabs, arbitrary split panes, and floating terminal overlays.
