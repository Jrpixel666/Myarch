#!/usr/bin/env bash
# Starts the Myarch graphical session inside the container.
#
# The compositor is already listening on $WAYLAND_DISPLAY by the time this
# runs: the Android service binds the socket before launching proot.
set -uo pipefail

log() { printf '[myarch] %s\n' "$*"; }

: "${XDG_RUNTIME_DIR:=/run/user/0}"
: "${WAYLAND_DISPLAY:=wayland-0}"
export XDG_RUNTIME_DIR WAYLAND_DISPLAY

mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR" 2>/dev/null || true

socket="$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY"
for _ in $(seq 1 50); do
    [ -S "$socket" ] && break
    sleep 0.1
done
if [ ! -S "$socket" ]; then
    log "no Wayland socket at $socket -- is the Myarch compositor running?"
    exit 1
fi
log "found the compositor at $socket"

# Regenerate the keymap on every session start: the user may have changed the
# layout in the app's settings.
if command -v xkbcli >/dev/null 2>&1; then
    layout="${MYARCH_KEYBOARD_LAYOUT:-us}"
    xkbcli compile-keymap --layout "$layout" > "$XDG_RUNTIME_DIR/keymap.xkb" 2>/dev/null || true
fi

# A session bus is what lets notifications, portals and MPRIS work between
# guest applications. Without it, each app silently loses those features.
if command -v dbus-launch >/dev/null 2>&1 && [ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]; then
    eval "$(dbus-launch --sh-syntax)"
    export DBUS_SESSION_BUS_ADDRESS
    log "started a session D-Bus"
fi

# Notification daemon and wallpaper, if the user installed them.
command -v mako >/dev/null 2>&1 && (mako &) && log "started mako"

if [ -f /root/.config/myarch/wallpaper ] && command -v swaybg >/dev/null 2>&1; then
    (swaybg -m fill -i "$(cat /root/.config/myarch/wallpaper)" &) && log "set the wallpaper"
fi

# User autostart: anything executable here runs before the shell.
if [ -d /root/.config/myarch/autostart ]; then
    for script in /root/.config/myarch/autostart/*; do
        [ -x "$script" ] || continue
        log "autostart: $(basename "$script")"
        ("$script" &)
    done
fi

if command -v quickshell >/dev/null 2>&1; then
    log "starting Quickshell"
    exec quickshell -c myarch
fi

log "Quickshell is not installed yet."
log "Build it with /opt/myarch/build-quickshell.sh, or install waybar as a stand-in."

if command -v waybar >/dev/null 2>&1; then
    log "starting waybar instead"
    exec waybar
fi

# Nothing to show would leave the user with an empty home screen and no clue
# why, so open a terminal: it proves the container works and gives them a
# place to act.
if command -v foot >/dev/null 2>&1; then
    log "starting a terminal so the session is not empty"
    exec foot
fi

log "no shell, no bar and no terminal is installed; nothing to start"
exit 1
