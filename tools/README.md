# Build-time tools

Nothing here ships in the APK. These generate or fetch things the build needs.

| Tool | What it does |
|---|---|
| `wlgen/wlgen.py` | Generates `Protocols.kt` from the Wayland protocol XML in `protocols/`. Run via `./gradlew :wayland:generateProtocols`. |
| `make-fallback-keymap.py` | Generates `container/keymap/us-fallback.xkb`, the self-contained keymap the compositor sends before the container has produced a real one. |
| `vendor-proot.py` | Downloads proot and its libraries into `app/src/main/jniLibs/`. Runs automatically as part of the build. |
| `protocols/` | Vendored protocol definitions; see `protocols/LICENSE`. |

Both generators' outputs are committed, and CI checks that regenerating them
produces no diff — so a change to a generator or its input cannot silently
diverge from the checked-in result.

```sh
# Regenerate everything and verify nothing drifted.
python3 tools/wlgen/wlgen.py \
  --out wayland/src/main/kotlin/dev/myarch/wayland/protocol/Protocols.kt \
  tools/protocols/*.xml
python3 tools/make-fallback-keymap.py --out container/keymap/us-fallback.xkb
git diff --stat

# Validate the keymap with X11's compiler, if you have it.
xkbcomp -w 5 container/keymap/us-fallback.xkb /dev/null
```
