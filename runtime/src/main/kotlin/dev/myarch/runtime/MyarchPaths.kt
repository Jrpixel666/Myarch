package dev.myarch.runtime

import android.content.Context
import java.io.File

/**
 * Where everything Myarch owns lives on disk.
 *
 * The layout is deliberate rather than incidental:
 *
 * - the rootfs sits in the app's private `filesDir`, the only place a
 *   non-rooted app can write a Linux filesystem it can also execute from (see
 *   docs/ANDROID_EXEC_CONSTRAINTS.md);
 * - the runtime directory is bind-mounted to `/run/user/0` inside the
 *   container, so the Wayland socket is visible to guest processes;
 *  - downloads go to `cacheDir`, so Android may reclaim them under storage
 *   pressure without breaking an installed container.
 */
public class MyarchPaths(context: Context) {

    /** Root of everything we own. */
    public val base: File = File(context.filesDir, "myarch")

    /** The extracted Arch Linux filesystem. */
    public val rootfs: File = File(base, "rootfs")

    /** `XDG_RUNTIME_DIR`; holds the Wayland socket and the generated keymap. */
    public val runtimeDir: File = File(base, "run")

    /** `PROOT_TMP_DIR`: proot needs a writable, exec-free scratch directory. */
    public val prootTmp: File = File(base, "proot-tmp")

    /** Where container-side helper scripts are unpacked from app assets. */
    public val scripts: File = File(base, "scripts")

    /** Installed theme definitions, shared between the launcher and the guest. */
    public val themes: File = File(base, "themes")

    /** Partially and fully downloaded rootfs images. */
    public val downloads: File = File(context.cacheDir, "myarch/downloads")

    /** Install and session logs, surfaced in the app's diagnostics screen. */
    public val logs: File = File(base, "logs")

    /**
     * The directory Android extracted our bundled executables into.
     *
     * Binaries shipped as `lib*.so` in `jniLibs` land here, and this is the one
     * app-writable-adjacent location that is mounted without `noexec` for
     * every target SDK level.
     */
    public val nativeLibraryDir: File = File(context.applicationInfo.nativeLibraryDir)

    /** The Wayland socket path, inside [runtimeDir]. */
    public val waylandSocket: File = File(runtimeDir, WAYLAND_SOCKET_NAME)

    /** The XKB keymap the compositor hands to clients. */
    public val keymapFile: File = File(runtimeDir, "keymap.xkb")

    /** Marker written once a rootfs has been fully extracted and set up. */
    public val installMarker: File = File(base, ".install-complete")

    /** True when a usable container is already installed. */
    public val isInstalled: Boolean
        get() = installMarker.exists() && File(rootfs, "usr/bin/env").exists()

    /** Creates every directory Myarch expects to exist. */
    public fun prepare() {
        for (dir in listOf(base, rootfs, runtimeDir, prootTmp, scripts, themes, downloads, logs)) {
            dir.mkdirs()
        }
        // The runtime directory is a socket directory: 0700 is what
        // XDG_RUNTIME_DIR is specified to be, and some toolkits check.
        runtimeDir.setReadable(true, true)
        runtimeDir.setWritable(true, true)
        runtimeDir.setExecutable(true, true)
    }

    public companion object {
        /** Matches `WAYLAND_DISPLAY=wayland-0`, the default clients look for. */
        public const val WAYLAND_SOCKET_NAME: String = "wayland-0"

        /** Where [MyarchPaths.runtimeDir] is bound inside the container. */
        public const val GUEST_RUNTIME_DIR: String = "/run/user/0"

        /** Where [MyarchPaths.scripts] is bound inside the container. */
        public const val GUEST_SCRIPTS_DIR: String = "/opt/myarch"
    }
}
