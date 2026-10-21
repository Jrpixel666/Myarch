# Running executables on Android without root

Two Android restrictions shape almost every structural decision in Myarch. They
are worth understanding before changing anything in `:runtime` or the APK
packaging.

## 1. You may not execute files from your data directory

Since Android 10, an app whose `targetSdk` is 29 or higher runs in an SELinux
domain (`untrusted_app_29` and later) that denies `execute_no_trans` on
`app_data_file`. In plain terms: a file your app wrote cannot be executed, by
you or by anything you start.

That is fatal for a container. The Arch rootfs *is* a directory of executables
in the app's private storage, and every `pacman`, `bash` and `quickshell`
invocation is an `execve()` of one of them. proot does not get around this: it
uses `ptrace` and real `execve` calls, and SELinux is checked on each one.

### What Myarch does

`gradle.properties` sets `myarch.targetSdk=28`, which keeps the app in
`untrusted_app_27`, where the permission still exists. This is the same choice
Termux made, for the same reason.

The consequences are real and worth stating plainly:

- **Not publishable on Google Play.** Play requires a recent `targetSdk`. Myarch
  is a sideloaded app.
- **No scoped storage, no modern permission model.** Fine here, since Myarch
  works inside its own private directory.
- **Android 14 does not block it.** Installation is refused below `targetSdk`
  23; 28 is comfortably above that.
- `compileSdk` stays at 35, so modern APIs are still available at compile time
  behind version checks.

Override it with `-Pmyarch.targetSdk=35` if you only want to work on the
launcher and compositor. The app builds and the compositor runs; the container
will not start.

### The alternatives, and why they were not taken

| Approach | Why not |
|---|---|
| Ship everything in the native library directory | Works for *our* binaries (see below), but an Arch rootfs is 40,000 files that pacman rewrites. It cannot live in a read-only APK directory. |
| Root / Magisk | Defeats the point. Myarch should work on an unmodified phone. |
| An in-process emulator | Enormous, and slower than `ptrace`. |
| `app_process` / `dalvikvm` tricks | Do not help with native ELF binaries. |
| Termux's `RUN_COMMAND` plugin | Makes Myarch depend on Termux being installed and configured. |

## 2. The native library directory *is* executable

The exception to the rule above: an app's native library directory is populated
by the installer, not by the app, and it is executable at every API level.

So anything Myarch must run itself — proot and its loader — is shipped as a
`lib*.so` file in `jniLibs`, and Android unpacks it there. Hence the names:

```
libproot.so            → proot
libproot-loader.so     → proot's loader stub
libproot-loader32.so   → the 32-bit loader
libtalloc.so           → libtalloc (upstream: libtalloc.so.2)
libandroid-shmem.so    → Termux's System V shared memory shim
```

Three details that bite:

1. **`android:extractNativeLibs="true"` is required.** With extraction disabled
   the libraries stay compressed inside the APK and there is no file to execute.
   The manifest sets it, and `packaging { jniLibs { useLegacyPackaging = true } }`
   keeps the Gradle plugin from overriding it.
2. **Only `lib*.so` names are packaged.** A file called `libtalloc.so.2` would
   never reach the device, so `tools/vendor-proot.py` renames it — and rewrites
   proot's `DT_NEEDED` string in place so the dynamic linker still finds it.
3. **`LD_LIBRARY_PATH` must be set.** The vendored proot carries Termux's
   `RUNPATH` (`/data/data/com.termux/files/usr/lib`), which does not exist in
   our app. `ContainerSession` sets `LD_LIBRARY_PATH` to the native library
   directory.

## If you raise `targetSdk` later

Everything below has to be handled, and the container still will not run:

- Declare `foregroundServiceType` on `CompositorService` (API 29+) and, for
  API 34+, the matching `FOREGROUND_SERVICE_*` permission and `specialUse`
  property.
- `QUERY_ALL_PACKAGES` or `<queries>` becomes necessary to list launchable apps.
- `POST_NOTIFICATIONS` must be requested at runtime (API 33+).
- Scoped storage rules apply to anything outside the app's own directory.

The exec restriction is the blocker. If Android ever grows a sanctioned way for
an app to execute code it downloaded — a `MANAGE_EXTERNAL_EXEC`-style
permission, or an official container API — that is the day this document gets
much shorter.
