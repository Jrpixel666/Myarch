# Roadmap

What v0.1 does not do, roughly in the order the work matters.

## 1. Run it on a phone

The protocol stack has tests; the device integration has none, because nobody
has run it on hardware yet. Everything below is speculation until that happens.

Specifically unverified: proot under a real device's SELinux policy,
`LocalSocket` descriptor passing on vendor kernels, Quickshell compiling on an
ARM phone, and whether the frame rate of the software path is tolerable in
practice.

## 2. GPU rendering (`zwp_linux_dmabuf_v1`)

Today every frame is a CPU copy: the client renders into shared memory, Myarch
converts the pixels and copies them into a `Bitmap`, and the view blits that.
That is three touches of every pixel, and it caps what heavy applications can
do.

The real path is `linux-dmabuf`: the client renders with Mesa (Zink on Turnip,
or a vendor driver), exports a dmabuf, and Myarch imports it as an
`AHardwareBuffer` and hands it straight to a `SurfaceTexture`. No copy at all.

What it needs: the protocol (the XML is already vendored), importing a dmabuf as
an `AHardwareBuffer` (JNI — the first native code in the project), format and
modifier negotiation, and release fences so buffers are not reused too early.

The pixel path is deliberately isolated in `PixelConverter` and `FrameBuffer` so
this can be added beside it rather than through it.

## 3. XWayland

No X11 applications run today. `xorg-xwayland` would need the X11 protocol
surfaces wired into the compositor as override-redirect windows, plus
`wl_data_device` for clipboard interop. A large piece of work, and the reason
many familiar Linux apps do not start yet.

## 4. Clipboard

`wl_data_device_manager` is not implemented, so copy and paste between Linux
applications does not work. It needs server-allocated object ids (the only place
the compositor creates objects rather than the client) and pipe-based transfers.
Bridging it to Android's clipboard is the obvious follow-up and would be the
more useful half.

## 5. Audio

Nothing is wired up. PipeWire or PulseAudio inside the container would need a
sink that reaches Android's `AAudio`, in the shape of Termux's
`pulseaudio-module-sles`.

## 6. Smaller gaps

- **Fractional scaling** (`wp_fractional_scale_v1`) — the XML is vendored, the
  implementation is not. Would remove the upscaling blur on high-DPI screens.
- **Viewporter** — lets clients ask for cropping and scaling we currently do
  ourselves.
- **Text input** (`zwp_text_input_v3`) — Android's on-screen keyboard reaches
  Linux apps only as synthesised key events; proper IME support needs this.
- **`wlr_foreign_toplevel_management`** — would let a Quickshell bar show and
  switch windows, which is a very natural fit given each one is an Android task.
- **Cursor rendering** — a Bluetooth mouse gets Android's pointer, not the
  client's requested cursor shape.
- **Popup grabs** — popups are dismissed on a back gesture or a tap, but there
  is no true input grab, so a menu can be left open in odd cases.
- **Subsurface sync mode** — tracked but not honoured; a subsurface commits
  immediately rather than with its parent.
- **Icon themes** — Linux apps show a monogram, because resolving an icon name
  means walking the container's icon theme and parsing SVGs.
- **Home screen layout** — widgets flow in a grid rather than being freely
  placed and resized.
- **Multiple outputs** — one `wl_output`; an external display would want two.

## Non-goals

- **A window manager.** Android is the window manager. Tiling, animations and
  keybindings belong to it.
- **Replacing Termux.** Termux is a better terminal environment. Myarch is about
  graphical Linux windows living beside Android apps.
- **Google Play.** Impossible while the container needs `targetSdk` 28; see
  [ANDROID_EXEC_CONSTRAINTS.md](ANDROID_EXEC_CONSTRAINTS.md).
