# DroidTerminal Architecture Documentation

## 1. Executive Summary
**DroidTerminal** is a production-grade, native Android terminal application inspired by macOS's iTerm2, designed specifically for touchscreen and mobile Linux workflows. It executes genuine Linux distributions (such as Ubuntu 24.04 LTS) rootless on Android using PRoot and a native C++ PTY engine, rendering output via a high-performance Jetpack Compose Canvas grid styled with JetBrains Mono.

---

## 2. System Architecture Layers

```
+-----------------------------------------------------------------------------------+
|                            PRESENTATION & UI LAYER                                |
|  - macOS Window Chrome (Traffic lights: Red/Yellow/Green, Session title & badge)  |
|  - Jetpack Compose Canvas Terminal Renderer (Monospace grid, JetBrains Mono)      |
|  - Multi-Tab Session Manager & Tab Bar (Independent process states)               |
|  - Recursive Split-Pane Tree Layout (Arbitrary vertical & horizontal splits)      |
|  - Floating In-App Terminal Overlay (Draggable, resizable, minimize/restore)       |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                           INPUT SUBSYSTEM LAYER                                   |
|  - Dedicated Neomorphic Linux Terminal Keyboard (Ctrl/Alt/Shift/Fn latching)      |
|  - Android IME Bridge (Toggle soft keyboard for multilingual / predictive text)   |
|  - Hardware Keyboard Interceptor (USB/Bluetooth shortcuts, function keys)         |
|  - TerminalKeyEncoder (POSIX ASCII, VT100 normal & Application Cursor Mode)       |
|  - Clipboard & Bracketed Paste Handler (\e[200~ ... \e[201~)                      |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                        TERMINAL EMULATION LAYER                                   |
|  - VT100 / VT220 / xterm State Machine Parser (Ground, Esc, CSI, OSC)             |
|  - SGR Color Engine (Standard 16, 256-color palette, 24-bit TrueColor)            |
|  - Dual Screen Buffers: Primary Screen & Alternate Screen (Vim, Nano, Htop, Tmux) |
|  - Configurable Scrollback Ring Buffer (Bounded memory deques up to 50k lines)    |
|  - Text Selection & Search Match Indexer                                          |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                       NATIVE RUNTIME & PTY LAYER (NDK/C++)                        |
|  - POSIX PTY Engine: forkpty() / /dev/ptmx <-> /dev/pts/N                         |
|  - Non-blocking I/O Loop (poll() based, handling EINTR, EAGAIN, EIO, POLLHUP)     |
|  - Dynamic Window Resize: ioctl(TIOCSWINSZ) dispatching SIGWINCH                  |
|  - Process Supervisor: Non-blocking waitpid(), signal delivery (SIGINT, SIGTSTP)   |
+-----------------------------------------------------------------------------------+
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                          LINUX USERSPACE (PRoot)                                  |
|  - PRoot ptrace Syscall Interceptor (--link2symlink, -0 fake root)                |
|  - Rootless Bind Mounts (/dev, /proc, /sys, /dev/shm)                             |
|  - Ubuntu 24.04 LTS GNU Userland (glibc 2.39, apt, dpkg, bash, zsh, coreutils)    |
+-----------------------------------------------------------------------------------+
```

---

## 3. Native PTY Process Management
1. **PTY Pair Allocation**: `forkpty()` creates `/dev/ptmx` (master) and connects the slave terminal to standard streams (`stdin=0`, `stdout=1`, `stderr=2`).
2. **Terminal Line Discipline (`termios`)**:
   - `c_iflag`: `ICRNL | IXON` (Translate CR to NL on input)
   - `c_oflag`: `OPOST | ONLCR` (Map NL to CR-NL on output)
   - `c_cflag`: `CS8 | CREAD` (8-bit characters, enable receiver)
   - `c_lflag`: `ISIG | ICANON | ECHO | ECHOE | ECHOK | ECHOCTL` (Signals, canonical editing, echo)
3. **Child Execution**:
   - Clears signal masks and resets handlers to `SIG_DFL`.
   - Applies environment variables (`TERM=xterm-256color`, `COLORTERM=truecolor`, `LANG=C.UTF-8`).
   - Invokes `execvp` running PRoot pointing to the rootfs.
4. **Parent Process Loop**:
   - Flags master FD with `O_NONBLOCK`.
   - Uses `poll()` with timeout `0` for low-latency asynchronous reading.
   - Buffers partial writes until socket/pty pipe buffer space is available.
   - Monitors child status via `waitpid(childPid, &status, WNOHANG)`.

---

## 4. VT State Machine & ANSI Compatibility
The terminal parser implements a robust finite-state automaton:
* **Ground**: Emits visible characters to the current cursor position, advancing the column index with auto-wrap support. Handles control bytes: `\r` (CR), `\n` (LF), `\b` (BS), `\t` (Tab), `\x07` (Bell).
* **Escape**: Processes two-character sequences (`\x1b7` DECSC save cursor, `\x1b8` DECRC restore cursor, `\x1bM` reverse index).
* **CSI (Control Sequence Introducer `\x1b[`)**:
  - Cursor movement: `CSI A..D`, `CSI H`, `CSI f`, `CSI G`.
  - Erase in display (`CSI J`) & Erase in line (`CSI K`).
  - Margins (`CSI r` DECSTBM).
  - Private modes: Application Cursor Mode (`?1h`), Alternate Screen (`?1049h`), Bracketed Paste (`?2004h`).
  - SGR Colors: Parses 16 standard colors, 256 indexed colors (`38;5;n`), and 24-bit TrueColor (`38;2;r;g;b`).
* **OSC (Operating System Command `\x1b]`)**:
  - Dynamic window title change (`OSC 0; <title> \x07`).
  - Hyperlinks (`OSC 8;; <url> \x07`).
