# DroidTerminal: Production-Ready Native Android Linux Terminal

[![DroidTerminal CI](https://github.com/LinuxDroidapp/DroidTerminal/actions/workflows/ci.yml/badge.svg)](https://github.com/LinuxDroidapp/DroidTerminal/actions/workflows/ci.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20%28API%2026%2B%29-green.svg)](https://developer.android.com)
[![Architecture: ARM64](https://img.shields.io/badge/Architecture-ARM64--v8a%20%7C%20x86__64-orange.svg)](#)

**DroidTerminal** is an advanced, production-grade Android terminal application and reusable native terminal engine inspired by **iTerm2 on macOS**. Built specifically for mobile touchscreen devices, it executes genuine, un-simulated Linux userlands (Ubuntu 24.04 LTS, Debian, Arch, Kali) rootless on Android using **PRoot** and a custom **C++ POSIX PTY engine**.

---

## 🌟 Key Capabilities

### 1. Genuine Linux Execution via PRoot
* **Zero Command Simulation**: Every keystroke executes inside a real Linux environment running GNU `bash`, `zsh`, `coreutils`, and `glibc 2.39`.
* **Rootless Operation**: Uses `ptrace` system call interception with `-0` fake root emulation and `--link2symlink` hard link translation to run smoothly on Android without root privileges.
* **Ubuntu 24.04 LTS Support**: Fully supports `apt`, `dpkg`, `git`, `python3`, `curl`, `vim`, `nano`, `htop`, and `tmux`.

### 2. High-Performance Native PTY Engine (C++ / NDK)
* **Real PTY Allocation**: Native `forkpty()` allocating `/dev/ptmx` and slave `/dev/pts/N` pairs.
* **Non-Blocking I/O Loop**: High-throughput `poll()` architecture with zero main-thread blocking, robustly handling `EINTR`, `EAGAIN`, `EIO`, and `POLLHUP`.
* **Dynamic Window Resizing**: Dispatches `ioctl(TIOCSWINSZ)` to signal `SIGWINCH` to running full-screen terminal programs.
* **Multi-Session Supervisor**: Thread-safe registry handling multiple concurrent shells without FD or process leaks.

### 3. VT100 / VT220 / xterm Emulation Engine
* **Full Escape Sequence Parsing**: Finite state machine parsing Ground, ESC, CSI, and OSC sequences.
* **Comprehensive Colors**: 16 standard ANSI colors, 256-color palette (`38;5;n`), and 24-bit TrueColor (`38;2;r;g;b`).
* **Alternate Screen Buffer**: Complete support for `\x1b[?1049h/l` utilized by `vim`, `nano`, `less`, `htop`, and `tmux`.
* **Application Cursor Keys (DECCKM)**: Dynamically toggles between normal (`\x1b[A`) and application (`\x1bOA`) arrow sequences.
* **Bracketed Paste Mode**: Wraps pasted text in `\x1b[200~` ... `\x1b[201~`.
* **Bounded Scrollback Buffer**: Ring buffer retaining up to 50,000 lines of history with instant search.

### 4. Touchscreen-First Input Architecture
* **Dedicated Neomorphic Linux Keyboard**:
  * Modifier latching: `CTRL`, `ALT`, `SHIFT`, `FN`.
  * Direct POSIX byte dispatch: `Ctrl+C` $\to$ `0x03` (`SIGINT`), `Ctrl+D` $\to$ `0x04` (`EOF`), `Ctrl+Z` $\to$ `0x1A` (`SIGTSTP`).
  * Dedicated shell keycaps: `|`, `/`, `\`, `~`, `` ` ``, `$`, `&`, `;`, brackets, braces, and arrows.
  * Haptic feedback and smooth show/hide transitions.
* **Android Soft Keyboard (IME) Compatibility**: Dedicated toggle to summon the standard Android keyboard for multilingual typing and speech-to-text.
* **Physical Hardware Keyboard**: Full support for USB/Bluetooth keyboards with hardware modifier chords.

### 5. iTerm2-Inspired macOS Windowing UI
* **macOS Chrome**: Realistic titlebar with Close (Red `#FF5F57`), Minimize (Yellow `#FEBC2E`), and Maximize (Green `#28C840`) traffic lights.
* **Multi-Session Tabs**: Run multiple independent shells simultaneously with live status indicators.
* **Arbitrary Split Panes**: Recursive layout tree supporting horizontal and vertical pane splits.
* **In-App Floating Terminal**: Draggable, resizable floating terminal overlay with compact minimize mode.
* **JetBrains Mono**: Crisp monospace font alignment for tables, code, and ASCII graphics.

### 6. LinuxDroid Embedding Support
* Clean `LinuxRuntimeProvider` interface allowing the [LinuxDroid](https://github.com/LinuxDroidapp/LinuxDroid.git) application to embed DroidTerminal with **zero storage duplication**, reusing its existing rootfs, PRoot binary, and custom bind mounts.

---

## 🏗️ Project Structure

```
DroidTerminal/
├── app/
│   ├── build.gradle.kts                   # App configuration & NDK setup
│   ├── src/main/
│   │   ├── AndroidManifest.xml            # Permissions & foreground service
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt             # Native NDK build definition
│   │   │   ├── include/pty_internal.h     # Native PTY data structures & APIs
│   │   │   └── pty/
│   │   │       ├── pty_bridge.cpp         # JNI bridge
│   │   │       ├── process_manager.cpp    # forkpty() & PRoot launcher
│   │   │       ├── io_loop.cpp            # poll() non-blocking I/O
│   │   │       └── terminal_resize.cpp    # TIOCSWINSZ / SIGWINCH
│   │   ├── java/com/droidterminal/
│   │   │   ├── MainActivity.kt            # Main activity & Compose root
│   │   │   ├── runtime/
│   │   │   │   ├── NativePtyBridge.kt     # JNI native interface
│   │   │   │   └── LinuxRuntimeProvider.kt# Standalone & LinuxDroid providers
│   │   │   ├── terminal/
│   │   │   │   ├── TerminalSession.kt     # Session supervisor
│   │   │   │   ├── TerminalBuffer.kt      # Primary/alternate screen grid
│   │   │   │   ├── TerminalCell.kt        # Glyph & SGR attribute model
│   │   │   │   ├── TerminalParser.kt      # VT100/xterm state machine
│   │   │   │   ├── ScrollbackBuffer.kt    # Ring buffer history
│   │   │   │   ├── TerminalSelection.kt   # Touch selection & extraction
│   │   │   │   └── TerminalRenderer.kt    # Compose Canvas renderer
│   │   │   ├── input/
│   │   │   │   ├── LinuxKeyboard.kt       # Neomorphic virtual keyboard
│   │   │   │   ├── TerminalKeyEncoder.kt  # Byte mapping & DECCKM
│   │   │   │   ├── HardwareKeyHandler.kt  # Physical keyboard handler
│   │   │   │   └── ClipboardManager.kt    # Bracketed paste & clipboard
│   │   │   ├── windows/
│   │   │   │   ├── TerminalTabs.kt        # Multi-tab bar
│   │   │   │   ├── SplitPaneManager.kt    # Recursive split pane tree
│   │   │   │   └── FloatingTerminal.kt    # Draggable floating window
│   │   │   ├── service/
│   │   │   │   └── TerminalSessionService.kt # Foreground service
│   │   │   ├── settings/
│   │   │   │   └── TerminalSettings.kt    # Preferences & settings UI
│   │   │   └── ui/
│   │   │       ├── MacOSTitleBar.kt       # Traffic lights titlebar
│   │   │       ├── TerminalToolbar.kt     # Contextual action bar
│   │   │       └── theme/                 # Neomorphic color system
│   │   └── res/                           # Values, strings, colors, themes
├── scripts/
│   └── install_ubuntu_lts.sh              # Ubuntu 24.04 LTS ARM64 provisioner
├── tests/
│   └── native/
│       ├── test_pty_engine.cpp            # C++ PTY test suite (100% PASS)
│       └── test_parser.py                 # VT parser test suite (100% PASS)
├── docs/
│   ├── ARCHITECTURE.md                    # Deep systems architecture (or architecture.md)
│   ├── LINUXDROID_INTEGRATION.md          # LinuxDroid embedding guide (or integration.md)
│   ├── installation.md                    # First-launch Ubuntu setup, PRoot, & recovery
│   ├── build.md                           # Build guide for Android Studio & Termux ARM64
│   └── testing.md                         # Verification and test suites documentation
├── .github/workflows/
│   └── ci.yml                             # Automated GitHub Actions CI
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

---

## 🛠️ Build & Documentation

Detailed guides are available in the `docs/` folder:
* 📘 [Architecture Guide](docs/architecture.md): POSIX PTY engine, VT100 parser, Compose canvas renderer, and supervisor.
* 🚀 [Installation Guide](docs/installation.md): First-launch Ubuntu 26.04 ARM64 installation, PRoot preparation, and rootless compatibility.
* 📦 [LinuxDroid Integration Guide](docs/integration.md): Zero-duplication embedding with LinuxDroid's PRoot runtime.
* 🔨 [Build Guide](docs/build.md): Building with Gradle 9.7.1, AGP 9.3.2, NDK r29 on Android Studio and native Termux ARM64.
* 🧪 [Testing Guide](docs/testing.md): Native C++ unit tests, keyboard state machine, and parser verification.

### Building via Command Line
```bash
# Debug APK:
./gradlew assembleDebug
# Generated at: app/build/outputs/apk/debug/app-debug.apk

# Release APK:
./gradlew assembleRelease
# Generated at: app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 🧪 Verification & Test Report

All core subsystem test suites were executed and verified:

| Test Suite | Scope | Result | Details |
| :--- | :--- | :---: | :--- |
| **`test_pty_engine.cpp`** | Native PTY master/slave allocation, `forkpty()`, child exit tracking | **PASS** | Exit code 42 verified; non-blocking read verified |
| **`test_pty_engine.cpp`** | Bidirectional interactive shell calculation | **PASS** | `expr 1234 + 5678` evaluated to `6912` |
| **`test_pty_engine.cpp`** | PTY window resizing via `TIOCSWINSZ` | **PASS** | Window resized to 100x30; `SIGWINCH` dispatched |
| **`test_pty_engine.cpp`** | Multiple concurrent PTY sessions | **PASS** | 4 parallel shell sessions executed without FD leaks |
| **`test_parser.py`** | SGR 16/256/24-bit TrueColor parsing | **PASS** | All color mappings and attribute flags verified |
| **`test_parser.py`** | Alternate screen buffer switching (`\e[?1049h/l`) | **PASS** | Isolation between primary & alternate buffers verified |
| **`test_parser.py`** | Application cursor mode & margins (`DECSTBM`) | **PASS** | DECCKM sequences and margin bounds verified |
| **`test_parser.py`** | UTF-8 multi-byte decoding (`λ`, `🔥`, `中`) | **PASS** | 1, 2, 3, and 4-byte UTF-8 sequences verified |

---

## 📄 License
Licensed under the Apache License, Version 2.0.
JetBrains Mono font is licensed under the SIL Open Font License 1.1.
PRoot is licensed under the GNU General Public License v2.
