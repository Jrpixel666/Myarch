# forge

Prebuilt APK for **ApkForge** — the standalone "load an APK, run it, edit it
with apktool" app whose source lives in [`../apkforge`](../apkforge).

| File | Build | Min Android | Signing |
|------|-------|-------------|---------|
| `apkforge-0.1.0-debug.apk` | debug, v0.1.0 | 8.0 (API 26) | Android debug key |

## Install

1. Copy the APK to an Android device (8.0 or newer).
2. Open it with a file manager or browser.
3. Allow "install unknown apps" for that app if prompted, then tap Install.

## Notes

- It is a debug build signed with the standard Android debug key, meant for
  sideloading and testing — not a release artifact.
- The apktool **decode** path is verified; **full-decode rebuild** needs a
  native `aapt2` that is not bundled (use "Sources only" mode for on-device
  rebuilds). See [`../apkforge/README.md`](../apkforge/README.md) for the full
  capability matrix.
- To rebuild from source: `cd ../apkforge && ./gradlew assembleDebug`.
