#!/usr/bin/env bash
# Installs the Omarchy package set. Called with the package names as arguments.
set -uo pipefail

log() { printf '[myarch] %s\n' "$*"; }

if [ "$#" -eq 0 ]; then
    log "no packages requested"
    exit 0
fi

log "installing ${#} packages"

# Installing one batch means one failure (an unavailable package, a mirror
# hiccup) loses the lot. Installing in small groups keeps progress, and the
# retry below handles the mirror case.
failed=()
batch=()
flush() {
    [ "${#batch[@]}" -eq 0 ] && return 0
    if ! pacman -S --needed --noconfirm "${batch[@]}"; then
        log "batch failed, retrying one by one"
        for pkg in "${batch[@]}"; do
            pacman -S --needed --noconfirm "$pkg" || failed+=("$pkg")
        done
    fi
    batch=()
}

for pkg in "$@"; do
    batch+=("$pkg")
    if [ "${#batch[@]}" -ge 8 ]; then
        flush
    fi
done
flush

if [ "${#failed[@]}" -gt 0 ]; then
    log "could not install: ${failed[*]}"
    log "the desktop will still start; install them later from the app's package browser"
fi

# A font cache is needed or every window renders boxes instead of glyphs.
log "rebuilding the font cache"
fc-cache -f >/dev/null 2>&1 || log "fc-cache is unavailable; fonts may not be picked up until a restart"

# Generate the XKB keymap the compositor hands to clients. Doing it here means
# the keymap matches the installed xkeyboard-config rather than the compact
# fallback bundled in the APK.
if command -v xkbcli >/dev/null 2>&1; then
    log "generating the XKB keymap"
    mkdir -p "${XDG_RUNTIME_DIR:-/run/user/0}"
    xkbcli compile-keymap --layout us > "${XDG_RUNTIME_DIR:-/run/user/0}/keymap.xkb" 2>/dev/null \
        || log "xkbcli could not compile a keymap; the bundled fallback will be used"
fi

log "desktop packages done"
exit 0
