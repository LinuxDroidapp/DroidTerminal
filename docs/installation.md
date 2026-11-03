# DroidTerminal Ubuntu Installation & Runtime Setup Guide

This document describes the automated first-startup installation, PRoot initialization, and rootless compatibility configurations of **DroidTerminal**.

---

## 1. Automated Installation Overview

DroidTerminal automatically installs a genuine Ubuntu ARM64 Linux userspace upon first application launch. The process requires zero manual shell commands, tar extractions, or binary installations from the user.

```
[ First Application Launch ]
             |
             v
   isInstalled() check (manifest.json, /bin/bash, proot)
             |
    +--------+--------+
    |                 |
 [Healthy]       [Not Installed]
    |                 |
    v                 v
Open Terminal    Launch Guided InstallationScreen
                 1. Verify ARM64 CPU & Storage (>= 400 MB)
                 2. Create private directory hierarchy
                 3. Download official Ubuntu ARM64 rootfs (with Range resume)
                 4. Verify SHA-256 archive signature
                 5. Extract into staging directory using toybox tar
                 6. Atomic promotion: staging -> rootfs
                 7. Deploy ARM64 PRoot & loader from packaged assets
                 8. Configure APT sandbox (01sandbox), policy-rc.d
                 9. Configure DNS (/etc/resolv.conf) & Shared Memory (/dev/shm)
                10. Run headless PRoot verification (uname -m, /etc/os-release)
                11. Write manifest.json & completion marker
                12. Start interactive Bash shell via genuine PTY
```

---

## 2. Ubuntu ARM64 Root Filesystem

### Source Artifact
* **Official URL**: `https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/ubuntu-base-26.04.1-base-arm64.tar.gz`
* **Target Architecture**: `arm64-v8a` (`aarch64`)
* **Archive Size**: ~35 MB gzip compressed, ~100–180 MB uncompressed
* **Official SHA-256 Signature**: `5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd`

### Download Resilience & Safety
* **Resumable HTTP Range Requests**: If network connectivity drops or the user backgrounds the application, downloads resume from the last byte using `.part` staging files.
* **Corrupt Download Purging**: Archives that fail SHA-256 verification or have zero/truncated length are safely deleted before retry.
* **Storage Space Pre-Flight Check**: Checks internal storage via `StatFs`. If available space is below 400 MB, installation halts with an actionable notification before downloading.

---

## 3. Directory Initialization, File Locking & Concurrency Protection

Before any file modifications occur:
1. **Recursive Parent Initialization**: The installer ensures `context.filesDir/linux` exists via `linuxDir.mkdirs()`, resolving first-startup `ENOENT` conditions.
2. **Directory Integrity Check**: Validates that `linuxDir.exists() && linuxDir.isDirectory`.
3. **Exclusive File Locking**: Opens `context.filesDir/linux/.installlock` using `RandomAccessFile` and `FileChannel.tryLock()`.
   - If another process or thread attempts to modify the rootfs concurrently, it throws an `IllegalStateException`, preventing simultaneous rootfs corruption.
   - If a previous process terminated unexpectedly, the operating system kernel automatically frees the underlying lock, enabling immediate clean recovery without stale lock freezes.
4. **Guaranteed Release in `finally`**: Lock channel and stream are released and closed within a guaranteed `finally` block upon completion, failure, or cancellation.

---

## 4. PRoot Runtime Preparation & Provenance

### Binary Provenance
* **Upstream**: [LinuxDroid PRoot](https://github.com/LinuxDroidapp/proot.git)
* **Target Version**: `LinuxDroid-PRoot v5.1.107.92`
* **Commit**: `caadcae0e7697ec29f02e231a3a88866561aacd0`
* **Features Enabled**: `seccomp_filter`, `process_vm`, 16 KB memory page alignment for Android 15/16.
* **Companion Loader**: Standalone static loader packaged alongside PRoot.

### Installation, Permissions & Android W^X Handling
* PRoot and loader binaries are packaged in `app/src/main/assets/proot/arm64-v8a/` and extracted to private app storage: `context.filesDir/linux/bin/`.
* **Multi-Layer Android-Compatible Permissions**: Executable bits (`0755`) are applied across three distinct layers:
  1. Direct POSIX `chmod(2)` via JNI native bridge (`NativePtyBridge.nativeSetPermissions`).
  2. Android Bionic `android.system.Os.chmod(path, 0755)` API.
  3. Standard `File.setExecutable(true, false)` and `File.setReadable(true, false)`.
  4. Binary directory (`context.filesDir/linux/bin`) is explicitly given `0755` directory search permissions.
* **Pre-Flight ELF & Architecture Verification**: Validates 64-bit class, Little-Endian, and ARM64 machine architecture (`e_machine == 0xB7` / AArch64) via `ElfValidator`.
* **Android W^X & SELinux Policy Handling**:
  - Carrier libraries (`libproot.so`, `libproot_loader.so`) are packaged in `jniLibs` with `packaging.jniLibs.useLegacyPackaging = true`.
  - Android Package Manager extracts these into `context.applicationInfo.nativeLibraryDir` with SELinux `apk_data_file` execution permissions.
  - If a device enforces strict SELinux `noexec` on `app_data_file` (`context.filesDir/linux/bin/proot`), DroidTerminal automatically detects the W^X restriction (`error=13`) and establishes a symlink or direct reference to the carrier library in `nativeLibraryDir`, bypassing W^X without compromising security.
* **Non-Suppression Policy**: If PRoot cannot execute, installation does not silently loop or reinstall; it halts immediately, marks state as `RECOVERY_REQUIRED`, and logs the exact stage and underlying OS error code in diagnostics.

---

## 5. Rootless PRoot Compatibility Configurations

DroidTerminal applies mandatory optimizations during first launch:

### 4.1 Hard-Link Translation (`--link2symlink`)
* PRoot's `--link2symlink` argument is centrally enforced by `ProotLauncher`.
* Translates Unix hard links into symbolic links where target Android filesystems lack standard hard-link support, guaranteeing smooth `dpkg` and `apt` operations.

### 4.2 APT Privilege-Dropping Override
* Under rootless PRoot, APT's attempt to drop privileges to the `_apt` system user fails because PRoot emulates root without kernel privilege separation.
* Created file: `/etc/apt/apt.conf.d/01sandbox`
* Contents: `APT::Sandbox::User "root";`

### 4.3 Service Autostart Suppression (`policy-rc.d`)
* PRoot does not run systemd as PID 1. Debian/Ubuntu service maintenance scripts attempting to start background daemons fail.
* Created executable script: `/usr/sbin/policy-rc.d`
* Contents:
  ```sh
  #!/bin/sh
  exit 101
  ```

### 4.4 Shared Memory (`/dev/shm`)
* Prepares a writable backing directory at `context.filesDir/linux/shm/` with permissions `1777`.
* Centrally bind mounted to `/dev/shm` inside the Linux userspace, enabling POSIX shared memory operations.

### 4.5 DNS & Networking
* Automatically writes `/etc/resolv.conf`:
  ```
  nameserver 1.1.1.1
  nameserver 8.8.8.8
  ```
* Automatically writes `/etc/hosts`:
  ```
  127.0.0.1 localhost ubuntu
  ::1 localhost ip6-localhost ip6-loopback
  ```
* Sets `/etc/hostname` to `ubuntu`.

---

## 5. Persistent Storage & Recovery State Machine

### Directory Structure
```
app-private (context.filesDir)/
└── linux/
    ├── rootfs/            # Verified active Ubuntu Linux root filesystem
    ├── staging/           # Temporary extraction folder before atomic promotion
    ├── bin/
    │   ├── proot          # ARM64 PRoot executable (chmod 0755)
    │   └── loader         # PRoot companion static loader
    ├── downloads/         # Cached archives and .part resume files
    ├── tmp/               # PROOT_TMP_DIR for sockets and pipes
    ├── shm/               # /dev/shm backing directory (chmod 1777)
    ├── manifest.json      # Environment metadata manifest
    ├── .installed         # Persistent installation verification marker
    └── .install.lock      # Concurrency lock
```

### Transactional Staging & Promotion
1. Archive extraction writes exclusively to `linux/staging/`.
2. Extracted contents are verified (`/bin/bash` must exist).
3. `staging/` is promoted to `rootfs/`. Existing working installations are preserved until promotion succeeds.

### Safe Repair & Reinstall
Users can access **"Reinstall / Repair Linux Environment"** from the Settings Dialog.
This removes the completion marker and triggers a clean re-verification or fresh installation without wiping user configuration unexpectedly.
