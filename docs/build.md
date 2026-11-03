# DroidTerminal Build Guide

This document describes how to build **DroidTerminal** independently on both Android Studio (Desktop) and directly on ARM64 Android devices running **Termux**.

---

## 1. Toolchain & Prerequisites

### Supported Platforms
* **Target OS**: Android 8.0+ (API level 26 minimum, API level 36 target)
* **ABIs**: `arm64-v8a` (Primary), `x86_64` (Emulator)

### Required Components
* **Java Development Kit**: JDK 17 or JDK 21 (e.g. OpenJDK 21)
* **Android SDK**: Platforms `android-36`, Build-Tools `36.0.0`
* **Android NDK**: NDK r29 (`29.0.14206865`) or NDK r25+
* **Gradle**: Gradle 9.7.1+ (included via Gradle Wrapper `./gradlew`)
* **Android Gradle Plugin (AGP)**: 9.3.2+
* **Kotlin**: 2.3.20+ with Jetpack Compose Compiler plugin

---

## 2. On-Device Termux ARM64 Build Instructions

DroidTerminal is specifically configured to build natively on Android devices inside Termux:

### Environment Setup
```bash
# 1. Set JAVA_HOME and PATH for OpenJDK 21
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$PATH

# 2. Configure local.properties
cat << 'EOF' > local.properties
sdk.dir=/data/data/com.termux/files/home/Android/Sdk
ndk.dir=/data/data/com.termux/files/home/Android/Sdk/ndk/29.0.14206865
EOF

# 3. Termux ARM64 aapt2 override (configured in gradle.properties)
# android.aapt2.override=/data/data/com.termux/files/usr/bin/aapt2
# android.aapt2FromMaven=false
```

### Compiling Debug APK
```bash
./gradlew assembleDebug
```
The output APK will be generated at:
```
app/build/outputs/apk/debug/app-debug.apk
```

### Compiling Release APK
```bash
./gradlew assembleRelease
```
The output unsigned release APK will be generated at:
```
app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## 3. Desktop Android Studio Build Instructions

1. Clone the repository:
   ```bash
   git clone https://github.com/LinuxDroidapp/DroidTerminal.git
   cd DroidTerminal
   ```
2. Open the project in **Android Studio Meerkat (2024.3+)** or newer.
3. Android Studio will automatically locate your installed Android SDK and NDK.
4. Select `app` in the run configuration dropdown and click **Run** (or `Shift + F10`).
5. Select an ARM64 physical device or x86_64 emulator.

---

## 4. Native C++ Engine Compilation

DroidTerminal includes a CMake configuration for the native PTY engine.
The native code compiles into `libdroidterm_native.so`:

```
app/src/main/cpp/
├── CMakeLists.txt
├── include/
│   └── pty_internal.h
└── pty/
    ├── pty_bridge.cpp         # JNI methods
    ├── process_manager.cpp    # forkpty() / PRoot supervision
    ├── io_loop.cpp            # poll() non-blocking I/O
    └── terminal_resize.cpp    # TIOCSWINSZ
```

To run the native unit tests directly on the CLI:
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
