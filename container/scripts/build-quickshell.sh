#!/usr/bin/env bash
# Builds Quickshell from source inside the container.
#
# Quickshell is not in Arch's official repositories, only the AUR, and the AUR
# has no prebuilt ARM binaries -- so there is no way around compiling it. On a
# recent phone this takes roughly 20-40 minutes and needs about 3 GB of free
# space. It is worth it: Quickshell is what makes the Omarchy widget layer work.
set -euo pipefail

log() { printf '[myarch] %s\n' "$*"; }

REPO="${QUICKSHELL_REPO:-https://git.outfoxxed.me/outfoxxed/quickshell.git}"
REF="${QUICKSHELL_REF:-master}"
SRC="/root/.cache/myarch/quickshell"
JOBS="${MYARCH_BUILD_JOBS:-$(nproc 2>/dev/null || echo 2)}"

log "installing build dependencies"
pacman -S --needed --noconfirm \
    base-devel cmake ninja pkgconf git \
    qt6-base qt6-declarative qt6-wayland qt6-svg qt6-shadertools qt6-tools \
    cli11 jemalloc libdrm libxcb wayland wayland-protocols

mkdir -p "$(dirname "$SRC")"
if [ -d "$SRC/.git" ]; then
    log "updating the existing checkout"
    git -C "$SRC" fetch --depth 1 origin "$REF"
    git -C "$SRC" checkout FETCH_HEAD
else
    log "cloning Quickshell ($REF)"
    git clone --depth 1 --branch "$REF" "$REPO" "$SRC"
fi

cd "$SRC"
log "configuring (this is the slow part; $JOBS parallel jobs)"

# Features that need hardware Myarch does not expose are turned off: they would
# either fail to configure or pull in large dependencies for nothing.
cmake -B build -G Ninja \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_INSTALL_PREFIX=/usr \
    -DDISTRIBUTOR="Myarch" \
    -DCRASH_REPORTER=OFF \
    -DI3=OFF \
    -DHYPRLAND=OFF \
    -DSERVICE_PIPEWIRE=OFF

log "building"
cmake --build build --parallel "$JOBS"

log "installing"
cmake --install build

if command -v quickshell >/dev/null 2>&1; then
    log "Quickshell installed: $(quickshell --version 2>&1 | head -1)"
    log "Restart the Myarch session to use it."
else
    log "the build finished but quickshell is not on PATH; check the output above"
    exit 1
fi
