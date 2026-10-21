# The container side

Everything here runs *inside* the Arch Linux container, not on Android. It is
packaged into the APK's assets and unpacked to the app's `scripts` directory,
which proot binds at `/opt/myarch` inside the container.

```
scripts/first-boot.sh         prepares a freshly extracted rootfs
scripts/install-desktop.sh    installs the Omarchy package set, in batches
scripts/start-session.sh      starts the graphical session
scripts/build-quickshell.sh   compiles Quickshell from source
scripts/generate-keymap.sh    produces an XKB keymap with xkbcli
quickshell/                   the Quickshell session: bar, widget layer, theme
keymap/us-fallback.xkb        the keymap used before the container makes one
```

These are plain files on purpose: they are the part of Myarch most likely to
need adjusting for a particular device or a newer Arch, and editing a shell
script is easier than rebuilding an APK. The copies inside the container are
refreshed whenever the app version changes.

A few decisions that look odd and are not:

- `first-boot.sh` disables pacman's sandbox and `CheckSpace`. Neither can work
  under proot — the sandbox needs Landlock and seccomp, and `CheckSpace` stats
  Android's filesystem and concludes there is no room. Without this, every
  package install fails with a misleading error.
- `install-desktop.sh` installs in batches of eight and retries individually.
  One unavailable package should not lose an hour of downloads.
- `start-session.sh` falls back to waybar, then to a terminal. An empty home
  screen with no explanation is a worse first impression than a terminal.
- `build-quickshell.sh` turns off PipeWire, Hyprland and i3 support. They need
  hardware or compositors Myarch does not expose.
