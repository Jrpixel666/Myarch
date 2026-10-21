# Native helpers

This directory holds the executables Myarch ships inside the APK. They are
**not committed**: `tools/vendor-proot.py` downloads them, and the Gradle build
runs that script automatically before assembling the app.

| File | What it is |
|------|------------|
| `libproot.so` | `proot` — runs the Arch container with no root and no kernel modules |
| `libproot-loader.so` | proot's loader stub; it cannot start guest processes without this |
| `libproot-loader32.so` | the 32-bit loader, for running 32-bit guest code under a 64-bit proot (absent on `armeabi-v7a`, which needs no second loader) |
| `libtalloc.so` | talloc, which proot links against |
| `libandroid-shmem.so` | Termux's System V shared memory shim, also a proot dependency |

## Why the names look like libraries

Android refuses to execute files from an app's data directory, but it *does*
execute the contents of the native library directory. Only files matching
`lib*.so` are packaged there and unpacked onto the device, so every executable
has to be named as though it were a library. `libtalloc.so` is upstream's
`libtalloc.so.2`, renamed — the vendoring script rewrites proot's `DT_NEEDED`
entry to match, so the dynamic linker still finds it.

See [`docs/ANDROID_EXEC_CONSTRAINTS.md`](../../../../docs/ANDROID_EXEC_CONSTRAINTS.md)
for the full story, and [`docs/THIRD_PARTY.md`](../../../../docs/THIRD_PARTY.md)
for licences and sources.

## Refreshing them

```sh
tools/vendor-proot.py              # all ABIs
tools/vendor-proot.py --check      # report what is present
```
