# Myarch

An Android launcher that is also a Wayland compositor, with an optional Arch
Linux system running next to your phone's apps.

Each Linux window opens as **its own Android task** — so you switch between
`neovim`, a browser and your messaging app in the same recents list, and
Android's split screen puts a Linux window beside an Android one. Quickshell
bars and Omarchy widgets are drawn directly onto the home screen, next to your
Android widgets.

No root. No kernel modules. The Arch system runs under `proot`.

```
┌──────────────────────────────────────────────────────────┐
│  Android                                                 │
│                                                          │
│   ┌────────────────────────┐   ┌──────────────────────┐   │
│   │ Myarch launcher        │   │ task: foot           │   │
│   │  • pages + widgets     │   │ task: neovim         │   │
│   │  • Omarchy panels      │   │ task: Firefox        │   │
│   │  • app drawer          │   └──────────▲───────────┘   │
│   │                        │              │ one task      │
│   │  Wayland compositor ───┼──────────────┘ per window    │
│   └───────────▲────────────┘                              │
│               │ $WAYLAND_DISPLAY (AF_UNIX + SCM_RIGHTS)   │
│   ┌───────────┴────────────────────────────────────────┐  │
│   │ proot container: Arch Linux ARM                    │  │
│   │  Quickshell · pacman · your software               │  │
│   └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

## What it does

- **Launcher.** Swipeable pages, Android app widgets, an app drawer that lists
  Android *and* Linux applications together, and a window switcher.
- **Wayland compositor, written in Kotlin.** Speaks `wl_compositor`, `wl_shm`,
  `wl_surface`, `wl_subsurface`, `wl_seat` (keyboard, pointer, touch),
  `wl_output`, `xdg-shell`, `xdg-decoration` and `wlr-layer-shell`.
- **A window is a task.** Mapped `xdg_toplevel`s become Android document tasks:
  recents, split screen and the back gesture all work on them.
- **Omarchy widgets on the home screen.** Quickshell panels arrive as
  layer-shell surfaces and are composited over the launcher's pages, with taps
  forwarded back as touch and pointer events.
- **An installer with a mirror switcher.** Myarch probes the Arch Linux ARM
  mirrors, downloads from the fastest one that answers, resumes interrupted
  downloads, and switches mirrors by itself when one fails. You can also pin a
  mirror or paste your own URL.
- **Themes.** One Omarchy palette drives the launcher's own UI, Alacritty, foot,
  btop, mako, waybar and Quickshell at once.
- **A builder menu.** Pick a widget or application template, see which packages
  it needs, and Myarch installs them and writes the code into the container.

## Status, honestly

The protocol stack is real and covered by tests — 109 of them, including an
end-to-end test that drives a scripted Wayland client through registry binds, a
shared memory pool, an `xdg_toplevel` and a layer-shell panel, and checks the
pixels that come out the other side.

**What has not happened: nobody has run this on a physical phone yet.** The
Android integration (proot under Android's SELinux policy, `LocalSocket`
descriptor passing on a real device, Quickshell compiled on an ARM phone) is
written carefully and reasoned about, but it is unverified on hardware. Treat
v0.1 as a working foundation, not a finished product, and please
[report what breaks](../../issues).

Known limits today:

| | |
|---|---|
| Rendering | Software only (`wl_shm`). Heavy apps will feel slow. GPU via dmabuf is the next big piece. |
| X11 apps | No XWayland yet. |
| Clipboard | `wl_data_device` is not implemented, so copy/paste between Linux apps does not work yet. |
| Audio | Not wired up. |
| Quickshell | Not in Arch's repositories; it is compiled on the device (20–40 minutes). Until then Myarch falls back to waybar or a terminal. |
| `targetSdk` | 28, deliberately — see [below](#why-targetsdk-28). |

See [docs/ROADMAP.md](docs/ROADMAP.md) for what comes next.

## Getting started

### Build the APK

Needs JDK 17+, the Android SDK (API 35) and Python 3.

```sh
git clone https://github.com/Jrpixel666/Myarch.git
cd Myarch
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. The build downloads `proot` and
its libraries into `app/src/main/jniLibs` on the way (see
[docs/THIRD_PARTY.md](docs/THIRD_PARTY.md)); without a network it still builds,
and the app tells you what is missing.

Prebuilt APKs are attached to [each release](../../releases).

### Install the container

1. Open Myarch. The console offers **Install**.
2. Leave the mirror on *Choose automatically*, or pick one. About 600 MB comes
   down; it resumes if your connection drops.
3. Myarch extracts the rootfs, configures pacman and installs the Omarchy
   package set.
4. **Start the session.** Set Myarch as your home screen when you want the
   launcher.

Then, from **Packages**, build Quickshell — that is what brings the Omarchy bar
and widgets to the home screen.

Full walkthrough: [docs/INSTALL.md](docs/INSTALL.md).

## How it fits together

| Module | What lives there |
|--------|------------------|
| `:wayland` | The compositor: wire protocol, resources, roles, input. No Android UI code, so it is unit-testable on a JVM. |
| `:runtime` | Mirrors, downloads, tar extraction, proot, pacman, themes, templates. |
| `:app` | Launcher, installer, builder, the compositor service, and the per-window activity. |
| `container/` | What runs *inside* Arch: bootstrap scripts, the Quickshell session, the fallback keymap. |
| `tools/` | Protocol code generator, keymap generator, proot vendoring. |

The Wayland protocol tables are generated from the upstream XML by
`tools/wlgen/wlgen.py`, so argument layouts are never hand-transcribed.

Deeper detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Why `targetSdk` 28

proot has to `execve()` binaries that live inside the app's private data
directory — that is what the Arch rootfs is. Android enforces W^X on app data
files for processes whose target SDK is 29 or newer, so a container would be
unable to start any guest program at all.

Targeting 28 keeps the app in the `untrusted_app_27` SELinux domain, which still
permits it. This is the same choice Termux made, for the same reason. It means
Myarch is a sideloaded app and not a Play Store one.

The full explanation, the alternatives, and what would have to change:
[docs/ANDROID_EXEC_CONSTRAINTS.md](docs/ANDROID_EXEC_CONSTRAINTS.md).

## Contributing

Tests run with `./gradlew test`. The compositor is the part most worth
protecting — if you change protocol handling, add a case to
`CompositorSessionTest`. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Licence and credits

Myarch's own code is Apache-2.0 ([LICENSE](LICENSE)).

It stands on other people's work: [proot](https://proot-me.github.io/) and
[Termux](https://termux.dev/) for running Linux on Android without root,
[Arch Linux ARM](https://archlinuxarm.org/) for the rootfs,
[Omarchy](https://omarchy.org/) for the palettes and the idea of a coherent
themed desktop, and [Quickshell](https://quickshell.outfoxxed.me/) for the
widget layer. Bundled third-party binaries and their licences are listed in
[docs/THIRD_PARTY.md](docs/THIRD_PARTY.md).
