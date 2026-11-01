#!/bin/bash
# DroidTerminal - Canonical Ubuntu 24.04 LTS Rootfs Provisioner
# Downloads official Ubuntu Base 24.04 LTS ARM64 and configures it for PRoot.

set -e

TARGET_DIR="$1"
if [ -z "$TARGET_DIR" ]; then
    echo "Usage: $0 <target_rootfs_dir>"
    exit 1
fi

echo "[*] Initializing Ubuntu LTS rootfs directory at: $TARGET_DIR"
mkdir -p "$TARGET_DIR"

UBUNTU_LTS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.1-base-arm64.tar.gz"

echo "[*] Downloading Ubuntu 24.04 LTS (Noble Numbat) ARM64 Rootfs..."
curl -L "$UBUNTU_LTS_URL" -o /tmp/ubuntu-base.tar.gz

echo "[*] Unpacking Ubuntu Rootfs..."
tar -xzf /tmp/ubuntu-base.tar.gz -C "$TARGET_DIR"
rm /tmp/ubuntu-base.tar.gz

echo "[*] Applying PRoot compatibility fixes..."

# 1. DNS setup (Google & Cloudflare)
echo "nameserver 8.8.8.8" > "$TARGET_DIR/etc/resolv.conf"
echo "nameserver 1.1.1.1" >> "$TARGET_DIR/etc/resolv.conf"

# 2. Hostname
echo "127.0.0.1 localhost ubuntu" > "$TARGET_DIR/etc/hosts"

# 3. APT sandbox configuration (prevents _apt privilege dropping error under rootless PRoot)
mkdir -p "$TARGET_DIR/etc/apt/apt.conf.d"
echo 'APT::Sandbox::User "root";' > "$TARGET_DIR/etc/apt/apt.conf.d/01sandbox"

# 4. Service policy override (prevents systemd/sysv service autostart failures during apt install)
mkdir -p "$TARGET_DIR/usr/sbin"
cat << 'EOF' > "$TARGET_DIR/usr/sbin/policy-rc.d"
#!/bin/sh
exit 101
EOF
chmod 755 "$TARGET_DIR/usr/sbin/policy-rc.d"

# 5. Fix permissions for shared memory and tmp
mkdir -p "$TARGET_DIR/dev/shm" "$TARGET_DIR/tmp"
chmod 1777 "$TARGET_DIR/tmp" "$TARGET_DIR/dev/shm"

echo "[+] Full Ubuntu 24.04 LTS rootfs prepared successfully!"
echo "[+] Launch DroidTerminal to enter your rootless Linux environment."
