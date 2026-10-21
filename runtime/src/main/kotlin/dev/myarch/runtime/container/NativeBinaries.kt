package dev.myarch.runtime.container

import java.io.File

/**
 * Locates the native helpers Myarch ships inside the APK.
 *
 * Android refuses to execute files from an app's data directory once the app
 * targets API 29 or later, but it *does* execute the contents of the native
 * library directory -- that is where the system itself puts `.so` files it
 * dlopen()s. Shipping proot as `libproot.so` therefore keeps it runnable no
 * matter what the app targets, which is why the names look odd.
 *
 * See docs/ANDROID_EXEC_CONSTRAINTS.md and container/scripts/build-proot.sh.
 */
public class NativeBinaries(private val nativeLibraryDir: File) {

    /** The proot executable. */
    public val proot: File get() = File(nativeLibraryDir, PROOT)

    /**
     * proot's loader stub.
     *
     * proot injects this into each guest process to take control of it; without
     * it, nothing in the container starts.
     */
    public val prootLoader: File get() = File(nativeLibraryDir, PROOT_LOADER)

    /** The 32 bit loader, used when a 64 bit proot runs 32 bit guest code. */
    public val prootLoader32: File get() = File(nativeLibraryDir, PROOT_LOADER_32)

    /** talloc, which proot links against for its memory pools. */
    public val talloc: File get() = File(nativeLibraryDir, TALLOC)

    /** Termux's System V shared memory shim, also a proot dependency. */
    public val androidShmem: File get() = File(nativeLibraryDir, ANDROID_SHMEM)

    /** True when every required helper is present and executable. */
    public fun isComplete(): Boolean =
        proot.canExecute() && prootLoader.exists() && talloc.exists() && androidShmem.exists()

    /**
     * A description of what is missing, for the installer to show.
     *
     * The build ships placeholders rather than binaries (we cannot
     * cross-compile proot inside a Gradle build), so a fresh checkout hits this
     * path and the message has to explain the fix rather than just failing.
     */
    public fun describeProblem(): String? = when {
        !proot.exists() ->
            "$PROOT is missing from ${nativeLibraryDir.absolutePath}. " +
                "Build it with container/scripts/build-proot.sh and rebuild the APK."
        !proot.canExecute() ->
            "$PROOT is present but not executable, which means the APK was built with " +
                "android:extractNativeLibs=\"false\". Rebuild with extraction enabled."
        !prootLoader.exists() ->
            "$PROOT_LOADER is missing; proot cannot start guest processes without it."
        !talloc.exists() || !androidShmem.exists() ->
            "proot's libraries ($TALLOC, $ANDROID_SHMEM) are missing from the APK. " +
                "Run tools/vendor-proot.py and rebuild."
        else -> null
    }

    public companion object {
        /** proot itself. */
        public const val PROOT: String = "libproot.so"

        /** `loader`, renamed to survive APK packaging. */
        public const val PROOT_LOADER: String = "libproot-loader.so"

        /** `loader32`, for 32 bit guests on a 64 bit kernel. */
        public const val PROOT_LOADER_32: String = "libproot-loader32.so"

        /**
         * libtalloc.
         *
         * Upstream's file is `libtalloc.so.2`, but the APK packager only
         * extracts names matching `lib*.so`, so tools/vendor-proot.py renames
         * it and rewrites proot's `DT_NEEDED` to match.
         */
        public const val TALLOC: String = "libtalloc.so"

        /** Termux's System V shared memory shim. */
        public const val ANDROID_SHMEM: String = "libandroid-shmem.so"
    }
}
