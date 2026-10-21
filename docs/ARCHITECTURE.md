# Architecture

Myarch is three things in one APK: an Android launcher, a Wayland compositor,
and a manager for an Arch Linux container. This describes how they fit, and why
each boundary is where it is.

## The short version

```
Linux client (Quickshell, foot, GTK app)
        │  Wayland protocol over AF_UNIX, descriptors via SCM_RIGHTS
        ▼
:wayland  — Kotlin compositor
        │  WindowSink callbacks
        ▼
:app      — CompositorService  ──► starts one Android task per window
        │                      ──► draws layer-shell panels on the home screen
        ▼
:runtime  — proot, pacman, mirrors, themes
        │  execve of libproot.so from the native library directory
        ▼
Arch Linux ARM rootfs in the app's private files directory
```

## Module boundaries

### `:wayland` — the compositor

Pure Kotlin, no Android UI dependencies beyond `LocalSocket` (which is isolated
in `android/LocalSocketTransport.kt` behind the `ClientTransport` interface).
That is what makes the whole protocol stack testable on a plain JVM:
`CompositorSessionTest` plays a scripted client through a real session and
inspects the resulting pixels.

**Wire protocol.** `wire/` holds the codec. Wayland messages are a stream of
32-bit words with an 8-byte header packing size and opcode into one word;
argument layouts are not self-describing, so both peers must know them in
advance. Rather than hand-transcribe those layouts, `tools/wlgen/wlgen.py`
generates `protocol/Protocols.kt` from the upstream XML in `tools/protocols/`.
Regenerate with `./gradlew :wayland:generateProtocols`.

**Resources.** Wayland is object-oriented on the wire: the client allocates ids,
the server attaches behaviour. `Resource` is that behaviour; `Client` owns one
id space and the read/dispatch loop.

**Roles.** A `wl_surface` means nothing until a role is assigned. Myarch
implements four, and the role decides where the pixels go:

| Role | Where it lands |
|------|----------------|
| `xdg_toplevel` | its own Android task |
| `xdg_popup` | composited into the parent window's task |
| `wl_subsurface` | composited into its parent's task |
| `zwlr_layer_surface_v1` | drawn on the launcher's home screen |

**Threading.** One thread per client, plus the Android UI thread calling in.
Every mutation of the resource graph happens under `Compositor.lock`, and socket
writes are serialised by the transport, so an event sent from the UI thread
cannot interleave with one from the client's own thread. A thread per client is
the right shape here: a phone runs a handful of windows, and `LocalSocket` has
no selectable channel to build a poll loop on anyway.

### `:runtime` — the container

No UI, no compositor. Mirrors and their fallback logic, a resumable downloader,
a tar extractor, the proot command line, a pacman wrapper, theme rendering, and
the builder templates. Almost all of it is pure functions over data, which is
why it carries most of the test suite.

### `:app` — Android

`CompositorService` is a foreground service that owns the socket, the
compositor and the container's session process. It has to be foreground:
if Android reclaimed it, every open Linux window would vanish at once.

`AndroidWindowSink` is the hinge. When a toplevel maps, it starts
`LinuxWindowActivity` with `FLAG_ACTIVITY_NEW_DOCUMENT | MULTIPLE_TASK` and a
per-window `myarch://window/<id>` data URI — the URI matters, because
`documentLaunchMode` keys tasks by intent data and without a distinct one
Android would reuse a single task for every window.

## Rendering

A client commits a `wl_shm` buffer. The compositor maps the pool (via
`FileChannel.map` on the descriptor the client passed over `SCM_RIGHTS`),
converts the pixels, and hands a `FrameBuffer` to the sink, which copies it into
a `Bitmap`. `LinuxWindowView` composites that window's layers onto a
`SurfaceView` from the compositor thread.

The pixel conversion is a red/blue swap: a Wayland `ARGB8888` buffer is a
little-endian `0xAARRGGBB` word, so in memory it is B, G, R, A, while an Android
`ARGB_8888` bitmap is R, G, B, A. `XRGB8888`'s alpha byte is undefined and in
practice often zero, so it is forced opaque — otherwise the window renders
invisible. Row padding is dropped at the same time, since a bitmap cannot
express a stride wider than its width.

Buffers are released as soon as they are copied. A zero-copy path would hold the
release until the frame retires; see the dmabuf item in
[ROADMAP.md](ROADMAP.md).

### Scaling

Clients see `wl_output.scale = 1`, and windows are configured at
`device pixels / uiScale` logical pixels. The renderer scales the result back up
when it blits.

This is not the textbook approach — properly, we would advertise the real scale
and let clients render at high DPI. But toolkit support for that is uneven, and
a window that silently renders at phone-pixel density is unreadable. Doing the
scaling ourselves is predictable for every client. `uiScale` is a setting; 1
gives a desktop-sized workspace for an external screen.

## Input

Android events → `Compositor.dispatch*` → `wl_seat` events.

- **Touch** goes to `wl_touch`. Many toolkits never bind it, so the first touch
  point is also synthesised as a pointer press — without that, a finger does
  nothing in a surprising number of apps.
- **Keys** are translated from Android key codes to Linux evdev scancodes by
  `AndroidKeyMap`, because Wayland carries scancodes and the client resolves
  them through the keymap. The table is written against numeric constants so it
  can be tested off-device.
- **Keymap.** `wl_keyboard.keymap` must be sent or `libwayland` clients block
  forever. The container generates a full XKB keymap with `xkbcli` on session
  start; before that exists, Myarch sends the compact self-contained keymap in
  `container/keymap/us-fallback.xkb` (generated by
  `tools/make-fallback-keymap.py`, which deliberately emits no `include`
  directives since the client resolves those against its own data path).
- **Focus** follows Android's task order: whichever task the user brought
  forward is the window that receives keys.

## The container

`proot` fakes `chroot`, uid 0 and bind mounts using `ptrace`, with no
privileges. `ProotCommand` builds the invocation; the flags are not arbitrary:

- `-0` makes the guest believe it is root, which pacman insists on.
- `--link2symlink` emulates hard links, which Android's filesystem refuses and
  pacman creates constantly.
- `/dev`, `/proc`, `/sys` and `/system` are bound through; `/dev/shm` and `/tmp`
  are bound to a writable host directory because Mesa, Qt and pulseaudio need
  them.
- The host runtime directory is bound to `/run/user/0`, which is how the guest
  sees the Wayland socket.
- `LD_LIBRARY_PATH` points at the native library directory: the proot binary
  carries Termux's `RUNPATH`, which does not exist here.

`first-boot.sh` also turns off two pacman features that cannot work under proot:
the sandbox (pacman 7 uses Landlock and seccomp, neither available) and
`CheckSpace` (it stats Android's filesystem and concludes there is no room).
These are requirements, not preferences — without them every install fails with
a misleading error.

## Themes

One `Palette` is rendered into each tool's own config format by `ThemeWriter`,
and into a Compose `ColorScheme` by `MyarchTheme`. The launcher and the Linux
session therefore cannot drift apart. Quickshell gets a QML singleton it
reloads on change, so a theme switch is immediate on the shell side.

## What is deliberately not here

- **A window manager.** Android is the window manager. Interactive move, resize,
  maximise and minimise requests are accepted and answered with a configure, but
  they have no meaning when the window fills a task.
- **Cursor rendering.** Touch has no cursor, and a Bluetooth mouse gets
  Android's own pointer. `wl_pointer.set_cursor` is accepted and ignored.
- **A terminal emulator.** The container has `foot` and `alacritty`.
