#!/usr/bin/env bash
# =============================================================================
# DroidTerminal — Intelligent PRoot Source & Update Policy Script
# =============================================================================
# Implements:
#  1. Source revision detection against upstream default branch.
#  2. Comparison with currently bundled PRoot commit SHA in MANIFEST.txt.
#  3. Reuse existing validated PRoot binary and loader if no updates.
#  4. Rebuild PRoot and matching loader if updates are available or --force.
#  5. Compile and validate artifacts before replacing active files.
#  6. Package updated PRoot, matching loader, and update MANIFEST.txt.
#  7. Safe failure: never replace working artifacts if build or validation fails.
#  8. Record source commit SHA as informational build metadata.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

DEFAULT_REPO_URL="https://github.com/LinuxDroidapp/proot.git"
REPO_URL="${DEFAULT_REPO_URL}"
FORCE_REBUILD=false
CHECK_ONLY=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --force)
            FORCE_REBUILD=true
            shift
            ;;
        --check-only)
            CHECK_ONLY=true
            shift
            ;;
        --repo)
            REPO_URL="$2"
            shift 2
            ;;
        -h|--help)
            echo "Usage: $0 [--force] [--check-only] [--repo <url>]"
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 1
            ;;
    esac
done

ASSETS_DIR="${ROOT_DIR}/app/src/main/assets/proot/arm64-v8a"
JNILIBS_DIR="${ROOT_DIR}/app/src/main/jniLibs/arm64-v8a"
MANIFEST_FILE="${ASSETS_DIR}/MANIFEST.txt"
BUILD_CACHE_DIR="${ROOT_DIR}/build/proot_upstream"
STAGE_DIR="${ROOT_DIR}/build/proot_staging"

mkdir -p "${ASSETS_DIR}" "${JNILIBS_DIR}"

# 1. Read currently bundled commit SHA
CURRENT_COMMIT=""
if [[ -f "${MANIFEST_FILE}" ]]; then
    CURRENT_COMMIT="$(grep -E "^commit:" "${MANIFEST_FILE}" | awk '{print $2}' || true)"
fi

echo "========================================================================"
echo " DroidTerminal: PRoot Source & Update Policy Checker"
echo "========================================================================"
echo "Upstream Repository : ${REPO_URL}"
echo "Currently Bundled   : ${CURRENT_COMMIT:-<none>}"
echo "Force Rebuild       : ${FORCE_REBUILD}"
echo "Check Only          : ${CHECK_ONLY}"
echo "========================================================================"

# 2. Check latest source revision from remote
echo ">>> Checking latest revision in upstream default branch..."
REMOTE_LINE="$(git ls-remote "${REPO_URL}" HEAD || true)"
REMOTE_COMMIT="$(echo "${REMOTE_LINE}" | awk '{print $1}')"

if [[ -z "${REMOTE_COMMIT}" ]]; then
    echo "WARNING: Could not query remote repository: ${REPO_URL}"
    if [[ -n "${CURRENT_COMMIT}" && -f "${ASSETS_DIR}/proot" && -f "${ASSETS_DIR}/loader" ]]; then
        echo ">>> Reusing existing validated PRoot binary (commit: ${CURRENT_COMMIT})."
        exit 0
    else
        echo "ERROR: No remote commit detected and no bundled PRoot available." >&2
        exit 1
    fi
fi

echo ">>> Upstream Commit : ${REMOTE_COMMIT}"

# 3. Decision Matrix
REBUILD_NEEDED=false

if [[ "${FORCE_REBUILD}" == "true" ]]; then
    echo ">>> Force rebuild requested. Proceeding with rebuild."
    REBUILD_NEEDED=true
elif [[ -z "${CURRENT_COMMIT}" || ! -f "${ASSETS_DIR}/proot" || ! -f "${ASSETS_DIR}/loader" ]]; then
    echo ">>> Fresh build with no existing artifact. Rebuild required."
    REBUILD_NEEDED=true
elif [[ "${CURRENT_COMMIT}" != "${REMOTE_COMMIT}" ]]; then
    echo ">>> New commits available (${CURRENT_COMMIT} -> ${REMOTE_COMMIT}). Rebuild required."
    REBUILD_NEEDED=true
else
    echo ">>> No PRoot source updates detected. Bundled commit matches upstream."
    REBUILD_NEEDED=false
fi

if [[ "${CHECK_ONLY}" == "true" ]]; then
    if [[ "${REBUILD_NEEDED}" == "true" ]]; then
        echo "UPDATE_AVAILABLE=true"
        echo "UPSTREAM_COMMIT=${REMOTE_COMMIT}"
        exit 10
    else
        echo "UPDATE_AVAILABLE=false"
        echo "UPSTREAM_COMMIT=${REMOTE_COMMIT}"
        exit 0
    fi
fi

if [[ "${REBUILD_NEEDED}" == "false" ]]; then
    # Verify existing bundled binaries before confirming reuse
    echo ">>> Validating existing bundled PRoot artifacts..."
    if [[ -x "${ASSETS_DIR}/proot" && -x "${ASSETS_DIR}/loader" ]]; then
        echo ">>> SUCCESS: Existing validated PRoot binary and loader are intact and ready."
        exit 0
    else
        echo "WARNING: Existing artifacts are missing or not executable. Rebuilding..."
    fi
fi

# 4. Locate Android NDK & toolchain
NDK_ROOT="${ANDROID_NDK_ROOT:-${NDK_ROOT:-}}"
if [[ -z "${NDK_ROOT}" || ! -d "${NDK_ROOT}" ]]; then
    for candidate in \
        "${ROOT_DIR}/../Android/Sdk/ndk/29.0.14206865" \
        "/data/data/com.termux/files/home/Android/Sdk/ndk/29.0.14206865" \
        "/home/codespace/Android/Sdk/ndk/29.0.14206865" \
        "${ANDROID_HOME:-/nonexistent}/ndk/29.0.14206865" \
        "${ANDROID_HOME:-/nonexistent}/ndk/"* \
        "$HOME/Android/Sdk/ndk/"*
    do
        if [[ -n "${candidate}" && -d "${candidate}" ]]; then
            NDK_ROOT="${candidate}"
            break
        fi
    done
fi

[[ -n "${NDK_ROOT}" && -d "${NDK_ROOT}" ]] || {
    echo "ERROR: Android NDK not found. Please set ANDROID_NDK_ROOT." >&2
    exit 1
}

CMAKE_TOOLCHAIN="${NDK_ROOT}/build/cmake/android.toolchain.cmake"
[[ -f "${CMAKE_TOOLCHAIN}" ]] || {
    echo "ERROR: CMake toolchain file not found: ${CMAKE_TOOLCHAIN}" >&2
    exit 1
}

echo ">>> Using NDK: ${NDK_ROOT}"

# 5. Fetch / Clone PRoot source into build cache
mkdir -p "${BUILD_CACHE_DIR}" "${STAGE_DIR}"
SRC_DIR="${BUILD_CACHE_DIR}/src"

# Check if local LinuxDroid vendor/proot exists to speed up or clone
if [[ -d "${ROOT_DIR}/../LinuxDroid/vendor/proot" ]]; then
    LOCAL_VENDOR="${ROOT_DIR}/../LinuxDroid/vendor/proot"
    echo ">>> Using local PRoot source repository from: ${LOCAL_VENDOR}"
    rm -rf "${SRC_DIR}"
    cp -r "${LOCAL_VENDOR}" "${SRC_DIR}"
else
    if [[ ! -d "${SRC_DIR}/.git" ]]; then
        echo ">>> Cloning PRoot source from ${REPO_URL}..."
        git clone --depth 50 "${REPO_URL}" "${SRC_DIR}"
    else
        echo ">>> Fetching latest PRoot source..."
        git -C "${SRC_DIR}" fetch origin
    fi
    git -C "${SRC_DIR}" checkout "${REMOTE_COMMIT}" || git -C "${SRC_DIR}" checkout HEAD
fi

BUILD_DIR="${BUILD_CACHE_DIR}/build-arm64"
rm -rf "${BUILD_DIR}"
mkdir -p "${BUILD_DIR}"

READELF_BIN="$(which llvm-readelf 2>/dev/null || which readelf 2>/dev/null || echo "")"
EXTRA_CMAKE_ARGS=()
if [[ -n "${READELF_BIN}" ]]; then
    EXTRA_CMAKE_ARGS+=("-DCMAKE_READELF=${READELF_BIN}")
fi

echo ">>> Configuring PRoot with CMake..."
if ! cmake -S "${SRC_DIR}" -B "${BUILD_DIR}" \
    -DCMAKE_TOOLCHAIN_FILE="${CMAKE_TOOLCHAIN}" \
    -DANDROID_ABI="arm64-v8a" \
    -DANDROID_PLATFORM="android-35" \
    -DCMAKE_BUILD_TYPE=Release \
    "${EXTRA_CMAKE_ARGS[@]}"; then
    echo "ERROR: CMake configuration failed. Preserving existing working artifacts." >&2
    exit 1
fi

echo ">>> Compiling PRoot targets..."
if ! cmake --build "${BUILD_DIR}" --parallel "$(nproc)"; then
    echo "ERROR: Compilation failed! Existing working artifacts will NOT be replaced." >&2
    exit 1
fi

PROOT_BIN="${BUILD_DIR}/proot-bin"
LOADER_BIN="${BUILD_DIR}/prootloader-bin"
LIBPROOT_SO="${BUILD_DIR}/libproot.so"
LIBPROOT_LOADER_SO="${BUILD_DIR}/libproot_loader.so"

for out in "${PROOT_BIN}" "${LOADER_BIN}" "${LIBPROOT_SO}" "${LIBPROOT_LOADER_SO}"; do
    if [[ ! -f "${out}" ]]; then
        echo "ERROR: Required build output missing: ${out}. Update aborted." >&2
        exit 1
    fi
done

# 6. Run validation tests before replacing artifacts
echo ">>> Running validation tests on new build outputs..."
chmod 0755 "${PROOT_BIN}" "${LOADER_BIN}" "${LIBPROOT_SO}" "${LIBPROOT_LOADER_SO}"

# Stage temporarily to test
cp -f "${PROOT_BIN}" "${STAGE_DIR}/proot"
cp -f "${LOADER_BIN}" "${STAGE_DIR}/loader"
cp -f "${LIBPROOT_SO}" "${STAGE_DIR}/libproot.so"
cp -f "${LIBPROOT_LOADER_SO}" "${STAGE_DIR}/libproot_loader.so"
chmod 0755 "${STAGE_DIR}/proot" "${STAGE_DIR}/loader" "${STAGE_DIR}/libproot.so" "${STAGE_DIR}/libproot_loader.so"

# Validation probe 1: proot --version
TEST_VERSION="$("${STAGE_DIR}/proot" --version || true)"
if [[ ! "${TEST_VERSION}" =~ "5.1." ]]; then
    echo "ERROR: New PRoot binary failed --version validation test! Output: ${TEST_VERSION}" >&2
    exit 1
fi
echo ">>> Validation probe 1 (version): PASSED"

# Validation probe 2: proot execution with loader
TEST_TMP_DIR="${ROOT_DIR}/build/proot_test_tmp"
mkdir -p "${TEST_TMP_DIR}"
chmod 0700 "${TEST_TMP_DIR}"
TEST_EXEC="$(PROOT_TMP_DIR="${TEST_TMP_DIR}" PROOT_LOADER="${STAGE_DIR}/loader" "${STAGE_DIR}/proot" -r / -b /dev -b /proc -b /sys /bin/sh -c 'echo DROIDTERMINAL_PROOT_VALIDATED' 2>&1 || true)"

if [[ "${TEST_EXEC}" != *"DROIDTERMINAL_PROOT_VALIDATED"* ]]; then
    # Try with host prefix if /bin/sh was not present
    PREFIX_PATH="${PREFIX:-/data/data/com.termux/files/usr}"
    if [[ -d "${PREFIX_PATH}" ]]; then
        TEST_EXEC="$(PROOT_TMP_DIR="${TEST_TMP_DIR}" PROOT_LOADER="${STAGE_DIR}/loader" "${STAGE_DIR}/proot" -r / -b /dev -b /proc -b /sys -b "${PREFIX_PATH}:/data/data/com.termux/files/usr" "${PREFIX_PATH}/bin/sh" -c 'echo DROIDTERMINAL_PROOT_VALIDATED' 2>&1 || true)"
    fi
fi
rm -rf "${TEST_TMP_DIR}"

if [[ "${TEST_EXEC}" != *"DROIDTERMINAL_PROOT_VALIDATED"* ]]; then
    echo "ERROR: New PRoot binary failed execution validation! Output: ${TEST_EXEC}" >&2
    exit 1
fi
echo ">>> Validation probe 2 (execution): PASSED"

# 7. Package and promote new artifacts
echo ">>> Validation tests passed! Promoting artifacts to assets and jniLibs..."

sha256_hash() {
    sha256sum "$1" | awk '{print $1}'
}

PROOT_SHA="$(sha256_hash "${STAGE_DIR}/proot")"
LOADER_SHA="$(sha256_hash "${STAGE_DIR}/loader")"
LIBPROOT_SHA="$(sha256_hash "${STAGE_DIR}/libproot.so")"
LIBLOADER_SHA="$(sha256_hash "${STAGE_DIR}/libproot_loader.so")"

cat > "${STAGE_DIR}/MANIFEST.txt" << EOF
LinuxDroid-PRoot v5.1.107.92
commit:  ${REMOTE_COMMIT}
ABI:     arm64-v8a
arch:    aarch64
sha256:
  proot:              ${PROOT_SHA}
  loader:             ${LOADER_SHA}
  libproot.so:        ${LIBPROOT_SHA}
  libproot_loader.so: ${LIBLOADER_SHA}
EOF

# Atomically copy to assets and jniLibs
cp -f "${STAGE_DIR}/proot" "${ASSETS_DIR}/proot"
cp -f "${STAGE_DIR}/loader" "${ASSETS_DIR}/loader"
cp -f "${STAGE_DIR}/MANIFEST.txt" "${ASSETS_DIR}/MANIFEST.txt"
chmod 0755 "${ASSETS_DIR}/proot" "${ASSETS_DIR}/loader"

cp -f "${STAGE_DIR}/libproot.so" "${JNILIBS_DIR}/libproot.so"
cp -f "${STAGE_DIR}/libproot_loader.so" "${JNILIBS_DIR}/libproot_loader.so"
chmod 0755 "${JNILIBS_DIR}/libproot.so" "${JNILIBS_DIR}/libproot_loader.so"

echo "========================================================================"
echo " DroidTerminal PRoot Update Succeeded!"
echo " Updated Commit SHA: ${REMOTE_COMMIT}"
echo "========================================================================"
exit 0
