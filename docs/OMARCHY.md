# Omarchy on a phone

[Omarchy](https://omarchy.org/) is an opinionated Arch setup: Hyprland, a
coherent palette applied across every tool, and a widget layer. Myarch borrows
the parts that make sense on a phone and replaces the parts that do not.

## What is kept, and what is replaced

| Omarchy | Myarch | Why |
|---|---|---|
| Hyprland as the compositor | Myarch's own compositor; Android manages windows | Hyprland needs DRM/KMS. And on a phone, Android's recents list is a better window switcher than anything we would build. |
| Hyprland keybindings and tiling | Android gestures, recents, split screen | There is no keyboard to bind, and the phone already has a window switcher people know. |
| Themes across every tool | Same idea, extended to the launcher's own UI | A theme that stops at the Linux border would look broken. |
| Quickshell widgets | Same, drawn on the Android home screen | Layer-shell surfaces composited over the launcher's pages. |
| `omarchy-theme-set` and friends | The app's Themes screen | Same files written, from a GUI. |

## The widget layer

Quickshell panels are `zwlr_layer_shell_v1` surfaces. The compositor resolves
their anchors and sizes and hands the launcher a position; the launcher draws
them over its pages and forwards taps back as touch and pointer events.

Layers map onto the home screen like this:

| Layer | Drawn |
|---|---|
| `background`, `bottom` | behind the icon grid |
| `top` | above the icon grid — bars and widget panels |
| `overlay` | above everything, including open Linux windows |

A panel's `exclusiveZone` is honoured, so a bar at the top of the screen keeps
that strip to itself.

The session's QML lives in `container/quickshell/` and is installed to
`~/.config/quickshell/myarch/` inside the container:

```
shell.qml              the session: a bar and a widget layer
Theme.qml              a QML singleton holding the active palette (generated)
WidgetRegistry.qml     the widgets you have built (generated)
Meter.qml              a shared labelled progress bar
panels/MyarchBar.qml   the default bar
panels/MyarchWidgets.qml  the home screen widget host
widgets/               what the builder writes
qmldir                 singleton registration
```

`Theme.qml` and `WidgetRegistry.qml` are rewritten by the app. Everything else
is yours to edit — Quickshell reloads QML when a file changes, so edits appear
without restarting the session.

## Why Quickshell is compiled on the device

Quickshell is not in Arch's official repositories; it is in the AUR, which does
not publish prebuilt binaries for ARM. There is no package to install, so
`container/scripts/build-quickshell.sh` compiles it: 20–40 minutes and about
3 GB of scratch space.

The build turns off features that need hardware Myarch does not expose
(PipeWire, Hyprland and i3 integration, the crash reporter) — they would either
fail to configure or pull in large dependencies for nothing.

Until it exists, `start-session.sh` falls back to `waybar`, then to a terminal.
An empty home screen with no explanation would be a worse first impression than
a terminal.

## Themes

A theme is one `Palette` — background, surface, foreground, muted, accent, two
status colours, and the sixteen ANSI colours — rendered into each tool's own
format by `ThemeWriter`:

```
~/.config/alacritty/colors.toml
~/.config/foot/colors.ini
~/.config/btop/themes/myarch.theme
~/.config/mako/config
~/.config/waybar/colors.css
~/.config/quickshell/myarch/Theme.qml
~/.config/myarch/theme.json          ← machine-readable, for your own scripts
```

The same palette becomes a Compose `ColorScheme` for the launcher, so both
halves change together.

Bundled: Tokyo Night, Catppuccin Mocha, Catppuccin Latte, Gruvbox Dark, Nord,
Everforest Dark, Rose Pine, Matte Black.

### Importing an Omarchy theme

`ThemeImporter` reads a theme directory laid out the way Omarchy's are. There is
no single palette file to read, so it takes the colours from `alacritty.toml` —
every Omarchy theme has one and the format is stable — and derives the rest:

- `light.mode` marks a light theme; without it, brightness is measured from the
  background colour.
- The surface colour is `primary.dim_background` if present, otherwise the
  background nudged towards mid grey.
- A wallpaper is the first image in `backgrounds/`.

Copy a theme directory into the container and import it from the Themes screen.

## Building widgets

**Build → Home screen widgets** scaffolds a Quickshell component: the files are
written into the container, the component is added to `WidgetRegistry.qml`, and
any missing packages are installed first. The point is that you end up with
readable QML you can edit, not a black box.

Templates: clock, system monitor (reads `/proc` directly), battery (reads
`/sys/class/power_supply`, so it shows the phone's real battery), sticky note,
weather (via `wttr.in`), and a top bar.

Applications work the same way and get their own Android task when launched: a
QML app, a PyQt6 app, or a shell script with a drawer entry.

Each template declares the packages it needs, which is what lets the builder say
"this needs `qt6-multimedia`, shall I install it?" rather than scaffolding
something that fails to start.

## What Omarchy has that Myarch does not

- Hyprland's animations, tiling and keybindings.
- `walker` (the launcher) and `hyprlock`/`hypridle` — Android handles launching
  and locking.
- Anything needing GPU acceleration: rendering is software for now.
