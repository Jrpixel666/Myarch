# ApkForge

A small, standalone Android app for loading an APK, running it, and taking it
apart with **apktool** — all on-device.

It has three pages, reached from the bottom navigation bar:

1. **Load** — pick an APK from storage, or copy one out of an app already
   installed on the device. The APK's icon, package name, version and SDK
   levels are shown.
2. **Run** — a phone-shaped "window" that previews the loaded app and launches
   it. Installing happens through the system installer; launching opens the
   real app.
3. **Edit** — runs `apktool d` to decode the APK into an editable project
   (manifest, smali, resources), lets you browse and edit the files, then runs
   `apktool b` and signs the result so it is installable again. A **Search**
   tab greps the decoded project by file name and (optionally) file contents —
   e.g. find a string, a method or a resource — and each hit opens the file in
   the editor.

The **Load** page also has a filter box to search the installed-apps list by
label or package name.

> This is a sibling project living in the Myarch repository. It is **not** part
> of the Myarch launcher/compositor and builds on its own.

## About the "emulated window"

The Run page draws ApkForge's own device frame, but the app it launches opens
**full-screen as its own Android task** — it is not rendered inside the frame.
That is a platform limit, not a shortcut: Android does not let one ordinary app
host another app's UI inside itself (that needs full virtualization, e.g. a
nested Android VM, which a normal installable app cannot do). So "Launch" starts
the real activity, which is the honest equivalent of running it.

## What actually works on-device, and what is fragile

ApkForge drives Google's and apktool's own libraries rather than reimplementing
them:

| Step | Library | Status |
|------|---------|--------|
| Read APK metadata, install, launch | Android `PackageManager` / `PackageInstaller` | Solid. |
| Decode (`apktool d`) | `org.apktool:apktool-lib` | Works on-device; manifest + smali + resources become editable files. |
| Rebuild (`apktool b`) — **sources-only project** | `org.apktool:apktool-lib` | Repackages without recompiling resources, so no native tool is needed. This is the reliable edit→rebuild loop. |
| Rebuild (`apktool b`) — **full decode** | `org.apktool:apktool-lib` + native `aapt2` | Needs a native `aapt2` for the device's CPU (see below). Without it, rebuilding a full-decode project fails with a clear log line. |
| Sign the rebuilt APK | `com.android.tools.build:apksig` | Solid. Uses a bundled throwaway debug key. |

Two decode modes are offered on the Edit page:

- **Sources only** (default) — decompiles code to smali and leaves resources
  as-is. You can edit smali and rebuild entirely on-device, no `aapt2` needed.
- **Full decode** — also turns resources into editable XML. Editing is nicer,
  but rebuilding has to *recompile* those resources, which requires `aapt2`.

### Adding `aapt2` for full rebuilds

Android only lets an app execute a binary from its native-library directory, and
only if it was packaged as `lib*.so`. To enable full-decode rebuilds, drop a
native `aapt2` built for each ABI here:

```
app/src/main/jniLibs/arm64-v8a/libaapt2.so
app/src/main/jniLibs/armeabi-v7a/libaapt2.so
app/src/main/jniLibs/x86_64/libaapt2.so      # emulator
```

ApkForge auto-detects `libaapt2.so` (or `libaapt.so`) in its native library
directory at build time and points apktool at it. These binaries are
ABI-specific and several MB each, so they are **not** committed here.

## The debug signing key

Rebuilt APKs are signed with `app/src/main/assets/debug.p12`, a self-signed
PKCS12 key (store/key password `apkforge`, alias `apkforge`). It exists only so
a rebuilt APK is installable for testing. It is intentionally public and is
**not** a release key — never ship anything signed with it.

## Permissions

- `REQUEST_INSTALL_PACKAGES` — to hand a loaded or rebuilt APK to the system
  installer. Android prompts the user to allow "install unknown apps" the first
  time.
- `READ_EXTERNAL_STORAGE` (API ≤ 32 only) — to read APKs picked from shared
  storage. On newer releases the Storage Access Framework file picker covers it
  with no permission.

A `<queries>` block declares the intent to see launchable apps and to launch /
install packages, as required by package-visibility rules on API 30+.

## Building

Needs JDK 17+ and the Android SDK (API 35).

```sh
cd apkforge
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. Run the unit tests with:

```sh
./gradlew test
```

## Project layout

```
apkforge/
  app/src/main/kotlin/com/apkforge/
    MainActivity.kt          Single activity, Compose + Navigation.
    ForgeApp.kt              Application.
    data/ApkRepository.kt    Pick / copy / inspect / install / launch APKs.
    engine/
      ApkToolEngine.kt       Decode & rebuild via apktool-lib (reflection).
      ApkSigner.kt           Sign the rebuilt APK via apksig.
      Aapt2.kt               Locate a bundled native aapt2.
    model/Models.kt          LoadedApk, FileNode, DecodeMode, …
    viewmodel/ForgeViewModel.kt   UI state + actions.
    ui/                      Compose screens: home, run, edit, editor.
  app/src/main/assets/debug.p12   Throwaway debug signing key.
```

## Honest status

The data layer (load/install/launch) and the signer are straightforward Android
and should work as written. The apktool decode path is expected to work
on-device; the full-decode rebuild path depends on a native `aapt2` you supply.
This project was written against apktool-lib 2.9.3 and apksig 8.7.3 — the engine
reaches apktool through reflection so minor API drift degrades to a readable
error in the Edit → Log view rather than a crash. It has not yet been run on a
physical device.
