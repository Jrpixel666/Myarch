# Contributing

## Building

JDK 17+, the Android SDK (API 35), Python 3.

```sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
./gradlew test            # 109 unit tests, no device needed
```

The build downloads proot into `app/src/main/jniLibs` the first time. If you are
offline it warns and carries on; the app then reports what is missing instead of
the build failing.

## Where things live

| | |
|---|---|
| `:wayland` | The compositor. No Android UI code — keep it that way, it is what makes it testable. |
| `:runtime` | Container, mirrors, packages, themes, templates. Mostly pure functions. |
| `:app` | Launcher, console, services, per-window activity. |
| `container/` | Runs *inside* Arch. Shell and QML. |
| `tools/` | Generators and the proot vendoring script. |

## The parts worth being careful with

**Protocol handling.** A one-byte mistake in the wire format desynchronises a
session with no way to recover. If you touch `wire/` or add a request handler,
add a case to `CompositorSessionTest` — it drives a scripted client through a
real session and checks the pixels that come out. Do not hand-edit
`protocol/Protocols.kt`; change `tools/wlgen/wlgen.py` or the XML and run
`./gradlew :wayland:generateProtocols`.

**Anything to do with executing binaries.** Read
[docs/ANDROID_EXEC_CONSTRAINTS.md](docs/ANDROID_EXEC_CONSTRAINTS.md) first. The
`targetSdk`, the `lib*.so` naming and `extractNativeLibs` are load-bearing, not
leftovers.

**proot flags and pacman options.** `ProotCommand` and `first-boot.sh` contain
choices that look arbitrary and are not — each one works around something
specific. The comments say which. If you remove one, say what replaced it.

## Style

- Kotlin official style; `:wayland` and `:runtime` are compiled with explicit
  API mode, so public declarations need explicit visibility.
- Comments explain *why*, not *what*. The code already says what.
- Prefer a tested pure function over a clever one that needs a device.
- No new dependencies without a reason that survives being written down.

## Tests

```sh
./gradlew test                          # everything
./gradlew :wayland:testDebugUnitTest    # the compositor
./gradlew :runtime:testDebugUnitTest    # container, mirrors, themes
```

Two of the bugs fixed before the first commit were found by tests written at the
same time as the code: a tar entry that skipped its padding and desynchronised
the stream, and a TOML reader that ate hex colours as comments. Tests here earn
their keep.

## What is most useful right now

The top of [docs/ROADMAP.md](docs/ROADMAP.md): **running it on a real phone and
reporting what breaks**. A log from Diagnostics and the model of the device is
more valuable than any feature at this stage.

After that: dmabuf, XWayland and the clipboard, in that order.

## Reporting a bug

Include the device and Android version, what you expected, what happened, and
the Diagnostics log. If the container is involved, the install log too.
