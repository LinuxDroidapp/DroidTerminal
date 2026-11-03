# DroidTerminal Testing Guide

This document describes the test strategy and test suites implemented for **DroidTerminal**.

---

## 1. Native C++ PTY Test Suite

The native PTY engine is validated using `tests/native/test_pty_engine.cpp`.
This test suite verifies:
1. **PTY Basic Allocation & Child Execution**:
   - Master `/dev/ptmx` and slave FD pairing via `forkpty()` or equivalent.
   - Child process execution and non-blocking stdout capture.
   - Child termination detection and exit code reporting.
2. **Interactive Bidirectional I/O**:
   - Asynchronous write to child `stdin` through master FD (`expr 1234 + 5678`).
   - Non-blocking `poll()` reading computed response (`6912`) from slave `stdout`.
   - Dynamic terminal window resizing with `TIOCSWINSZ` without crashing or dropping buffers.
3. **Concurrent Multi-Session Supervision**:
   - Creating 4 simultaneous live PTY master/slave sessions.
   - Verifying independent file descriptors without FD descriptor collision or leak.
   - Concurrent teardown and signal delivery.

### Running the Native Tests
```bash
clang++ -std=c++17 -Iapp/src/main/cpp/include \
  app/src/main/cpp/pty/pty_bridge.cpp \
  app/src/main/cpp/pty/process_manager.cpp \
  app/src/main/cpp/pty/io_loop.cpp \
  app/src/main/cpp/pty/terminal_resize.cpp \
  tests/native/test_pty_engine.cpp \
  -o test_pty_runner

./test_pty_runner
```

Output:
```
============================================
Starting DroidTerminal Native PTY Engine Tests
============================================
[TEST] Running testPtyBasicAllocationAndExecution...
Pty allocated: masterFd=3, childPid=32187
  Captured PTY output: Child executing: /bin/sh
DROIDTERM_PTY_TEST_PASSED
[PASS] testPtyBasicAllocationAndExecution passed!
[TEST] Running testPtyBidirectionalInteractiveIO...
Pty allocated: masterFd=3, childPid=32188
Pty resized to 100x30
  Captured interactive calculation output: expr 1234 + 5678
Child executing: /bin/sh
$ 6912
[PASS] testPtyBidirectionalInteractiveIO passed!
[TEST] Running testMultipleConcurrentPtySessions...
Pty allocated: masterFd=3, childPid=32190
Pty allocated: masterFd=4, childPid=32191
Pty allocated: masterFd=5, childPid=32192
Pty allocated: masterFd=6, childPid=32193
[PASS] testMultipleConcurrentPtySessions passed!
============================================
ALL NATIVE PTY TESTS PASSED SUCCESSFULLY!
============================================
```

---

## 2. VT100 / xterm Parser Verification

The parser (`TerminalParser.kt`) is designed according to the Paul Flo Williams state machine:
* **CSI Sequences**: Cursor positioning (`CUP`), cursor forward/back (`CUF`/`CUB`), clear screen (`ED`), scroll regions (`DECSTBM`).
* **SGR Color Parsing**: Standard 16 colors, 256 colors (`38;5;n`), and 24-bit TrueColor (`38;2;r;g;b`).
* **DEC Private Modes**:
  - `?1049h/l`: Alternate screen buffer switching (used by vim, htop, tmux).
  - `?1h/l`: Application cursor mode (DECCKM).
  - `?2004h/l`: Bracketed paste mode.
  - `?25h/l`: Cursor visibility toggling.

---

## 3. On-Screen Keyboard Interaction Tests

The on-screen keyboard (`LinuxKeyboard.kt`) verifies:
1. **Modifier Key State Machine**:
   - Tap `Shift`, `Ctrl`, `Alt`, `Fn`: enters **ONE_SHOT** mode (highlighted with cyan border).
   - Typing any key combines with the modifier and consumes it back to **OFF**.
   - Long-press `Shift`, `Ctrl`, `Alt`, `Fn`: enters **LOCKED** mode (green border + lock icon `🔒`).
   - Typing multiple keys keeps the modifier locked.
   - Tapping the locked modifier immediately unlocks it to **OFF**.
   - Strictly NO double-tap behavior.
2. **Fn Function Row**:
   - F1–F12 row is completely hidden by default.
   - Activating `Fn` (either one-shot or locked) slides open the F1–F12 row.
   - One-shot `Fn` consumes and closes the row after keypress.
   - Locked `Fn` keeps the row accessible until unlocked.
3. **Fn Secondary Navigation Mappings**:
   - `Fn + Left Arrow` $\to$ `Home` (`\x1b[H`)
   - `Fn + Right Arrow` $\to$ `End` (`\x1b[F`)
   - `Fn + Up Arrow` $\to$ `Page Up` (`\x1b[5~`)
   - `Fn + Down Arrow` $\to$ `Page Down` (`\x1b[6~`)
   - `Fn + Backspace` $\to$ `Delete` (`\x1b[3~`)
   - `Fn + \` $\to$ `Insert` (`\x1b[2~`)
4. **Layout Verification**:
   - Exactly one Shift key on the left.
   - True inverted-T arrow cluster:
     ```
            [ ↑ ]
     [ ← ]  [ ↓ ]  [ → ]
     ```
   - Wide spacebar.
   - Numbers 1–0 with shifted punctuation, backtick, hyphen, equals.

---

## 4. Installer Regression Test Suite

The installer regression test suite (`UbuntuInstallerTest.kt`) verifies the directory initialization, `.installlock` lifecycle, and transactional integrity on fresh data directories.

Run the unit test suite:
```bash
./gradlew testDebugUnitTest
```

Verified Test Scenarios:
1. `testFreshApplicationDataDirectoryInitializationAndLocking`:
   - Verifies that on a completely fresh application data directory where `context.filesDir/linux` does not exist, the installer recursively initializes the directory using `mkdirs()` before attempting to open `.installlock`.
   - Verifies that `linuxDir.exists()` and `linuxDir.isDirectory` pass before acquiring the lock.
   - Verifies that `.installlock` is successfully opened and an exclusive `FileLock` is acquired without `ENOENT` / `FileNotFoundException`.
2. `testConcurrentInstallerOperationsPreventSimultaneousModification`:
   - Spawns two independent installer instances targeting the identical rootfs directory.
   - Verifies that while the primary installer holds the exclusive lock, the secondary installer is immediately rejected with an `IllegalStateException`, guaranteeing rootfs files cannot be modified simultaneously.
   - Verifies that once the primary installer releases the lock, the secondary installer can acquire the lock cleanly.
3. `testFreshInstallationProceedsToDownloadWithoutEnoent`:
   - Executes the installation pipeline on a brand-new directory through device check, storage pre-flight, and directory preparation (`rootfs`, `staging`, `bin`, `tmp`, `shm`, `downloads`).
   - Verifies that the installer state successfully advances to `InstallStage.DOWNLOADING_ROOTFS` without throwing any `ENOENT` error.
4. `testLockReleaseLifecycleInFinallyOnFailure`:
   - Simulates an unexpected failure during the setup pipeline.
   - Verifies that the `releaseLock()` cleanup in `finally` closes channels and file descriptors, ensuring no deadlocks on subsequent installation attempts.

---

## 5. End-to-End Build Verification

Run the complete build suite:
```bash
./gradlew test assembleDebug assembleRelease
```
Both `app-debug.apk` and `app-release-unsigned.apk` should compile without errors.
