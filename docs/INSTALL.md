# Installing Myarch

## What you need

- An Android phone or tablet, **Android 8.0 or newer**, `arm64-v8a` for
  preference (`armeabi-v7a` and `x86_64` also build).
- **About 3 GB free**, more if you build Quickshell: ~600 MB download, ~2 GB
  extracted, ~3 GB peak during a Quickshell build.
- Patience for the first install. It is a Linux distribution.

## 1. Get the APK

Download it from [Releases](../../releases), or build it:

```sh
git clone https://github.com/Jrpixel666/Myarch.git
cd Myarch
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Install it with `adb install -r app-debug.apk`, or copy it to the device and
open it (you will need to allow installing from unknown sources).

Myarch is **not on Google Play** and cannot be — see
[ANDROID_EXEC_CONSTRAINTS.md](ANDROID_EXEC_CONSTRAINTS.md).

## 2. Install the container

Open Myarch. You land on the console, not the launcher — the home screen is
opt-in.

1. Tap **Install**.
2. Choose a mirror, or leave *Choose automatically*. Myarch probes every mirror
   and uses the fastest that answers.
3. Tap **Install**.

What happens, in order:

| Step | Notes |
|------|-------|
| Testing mirrors | A one-byte range request per mirror. Unreachable ones are dropped. |
| Downloading | ~600 MB. Resumes if the connection drops — re-tapping Install continues where it stopped. |
| Extracting | ~40,000 files. The slowest step on cheap storage. |
| Configuring | DNS, hostname, pacman mirrorlist, and the pacman options that cannot work under proot. |
| First boot | `pacman-key --init`, locale generation, `pacman -Syu`. |
| Desktop packages | Installed in small batches so one bad package does not lose the lot. |

If a mirror fails, Myarch switches by itself and says so in the log. If every
mirror fails, **Try another mirror** reshuffles the rotation, and you can paste
a URL of your own.

Leaving the screen is fine. The install keeps running.

## 3. Start the session

**Start the session** on the Overview screen. The compositor binds its socket,
proot starts, and `start-session.sh` looks for a shell to run.

Without Quickshell installed yet it falls back to `waybar`, or to a terminal, so
you can see that the container works.

## 4. Build Quickshell

Quickshell is what puts the Omarchy bar and widgets on your home screen. It is
not in Arch's repositories — only the AUR — and the AUR has no prebuilt ARM
binaries, so it is compiled on the device.

**Packages → Build Quickshell.** Expect 20–40 minutes, and keep the device
plugged in. Progress streams into the log.

When it finishes, restart the session.

## 5. Make Myarch your home screen

System settings → *Default apps* → *Home app* → Myarch. (The exact path varies
by manufacturer.)

You can skip this entirely: everything except the home screen works from the
console, and Myarch's own app widget gives you a shortcut into the session from
whatever launcher you already use.

## 6. Make it yours

- **Themes** — pick a palette. It rewrites the launcher's colours and the
  container's config files together.
- **Build** — scaffold a widget or an application. Myarch installs whatever the
  template needs first.
- **Add** (on the home screen) — Android widgets, app shortcuts, new pages.
- **Settings** — window scale, keyboard layout, whether the session autostarts.

## Troubleshooting

**"libproot.so is missing"** — the build could not download proot. Run
`tools/vendor-proot.py` and rebuild the APK.

**The install fails on every mirror** — check that the device is actually
online, then try pasting a mirror URL. Some mobile networks block plain HTTP;
the HTTPS mirrors (Florida, California, Germany) are the ones to try.

**"unable to lock database" or a seccomp error from pacman** — `first-boot.sh`
disables pacman's sandbox, which cannot work under proot. If you installed with
an older build, run `/opt/myarch/first-boot.sh` again from a terminal.

**pacman says there is no disk space** — `CheckSpace` reports Android's
filesystem rather than the container's. `first-boot.sh` disables it; same fix as
above.

**The session starts and nothing appears** — open **Diagnostics**. If the log
shows "no Wayland socket", the compositor did not start; if it shows Quickshell
exiting, build it (step 4). A session with nothing installed opens a terminal
rather than showing you an empty screen.

**A window is blank** — likely a client using a rendering path Myarch does not
support yet (dmabuf, or an X11 app needing XWayland). Software-rendered Wayland
clients are what works today.

**Text is tiny or huge** — Settings → *Window scale*.

**Keyboard does nothing** — Myarch needs a keymap. The container generates one
with `xkbcli` on session start; if `libxkbcommon` is missing, install it from
**Packages**. The bundled fallback is US-only.

## Starting over

Reinstalling from the Install screen extracts a fresh rootfs over the old one,
which resets installed packages but keeps `/root`. To wipe everything, clear the
app's data in Android settings.
