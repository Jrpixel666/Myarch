#!/usr/bin/env bash
# Prepares a freshly extracted Arch rootfs for use under proot.
#
# Run once by the Myarch installer, inside the container. Everything here has
# to work without a real init system, without root privileges on the host, and
# with proot's syscall emulation -- which is why several pacman features are
# turned off rather than fought with.
set -uo pipefail

log() { printf '[myarch] %s\n' "$*"; }
fail() { printf '[myarch] error: %s\n' "$*" >&2; exit 1; }

log "container: $(uname -m), $(cat /etc/os-release 2>/dev/null | grep -m1 PRETTY_NAME || echo 'unknown')"

# ---------------------------------------------------------------- pacman.conf
#
# pacman 7 sandboxes package extraction with Landlock and seccomp. Under proot
# both are unavailable and the sandbox fails to initialise, so every install
# aborts with "unable to lock" or a seccomp error. Disabling it is required
# here, not optional.
if ! grep -q '^DisableSandbox' /etc/pacman.conf; then
    log "disabling pacman's sandbox (unavailable under proot)"
    sed -i 's/^\[options\]/[options]\nDisableSandbox/' /etc/pacman.conf
fi

# CheckSpace calls statvfs on the pacman root, which proot answers for the
# Android filesystem; the numbers do not match what pacman expects and installs
# fail with a bogus "not enough free disk space".
if grep -q '^CheckSpace' /etc/pacman.conf; then
    log "disabling CheckSpace (proot reports Android's filesystem)"
    sed -i 's/^CheckSpace/#CheckSpace/' /etc/pacman.conf
fi

# Progress bars that redraw constantly make the install log unreadable in the
# app, and the app shows its own progress.
if ! grep -q '^NoProgressBar' /etc/pacman.conf; then
    sed -i 's/^\[options\]/[options]\nNoProgressBar/' /etc/pacman.conf
fi

# ------------------------------------------------------------------- keyring
log "initialising the pacman keyring (this takes a minute)"
mkdir -p /etc/pacman.d/gnupg
pacman-key --init >/dev/null 2>&1 || log "pacman-key --init reported a problem; continuing"

keyring="archlinux"
if [ -f /usr/share/pacman/keyrings/archlinuxarm.gpg ]; then
    keyring="archlinuxarm"
fi
log "populating the $keyring keyring"
pacman-key --populate "$keyring" >/dev/null 2>&1 || log "could not populate $keyring; signature checks may fail"

# -------------------------------------------------------------------- locale
if ! grep -q '^C.UTF-8' /etc/locale.gen 2>/dev/null; then
    echo 'C.UTF-8 UTF-8' >> /etc/locale.gen
fi
echo 'en_US.UTF-8 UTF-8' >> /etc/locale.gen
locale-gen >/dev/null 2>&1 || log "locale-gen failed; falling back to C.UTF-8"

# -------------------------------------------------------------- housekeeping
# ALARM images ship an 'alarm' user; Myarch runs everything as root inside the
# container (proot fakes uid 0), so give root a sensible shell and home.
mkdir -p /root/.config /root/.local/share/myarch /root/Projects
chmod 700 /root

# A machine-id is expected by anything that talks to D-Bus.
if [ ! -s /etc/machine-id ]; then
    (dbus-uuidgen 2>/dev/null || head -c 16 /dev/urandom | od -An -tx1 | tr -d ' \n') > /etc/machine-id
fi

# -------------------------------------------------------------------- update
log "refreshing package databases"
if ! pacman -Sy --noconfirm; then
    fail "pacman could not reach any mirror. Check the network, or switch mirrors in the installer."
fi

log "upgrading the base system"
if ! pacman -Su --noconfirm; then
    log "the full upgrade failed; continuing with the packages that did install"
fi

log "first boot complete"
