package dev.myarch.runtime.container

import android.content.Context
import dev.myarch.runtime.MyarchPaths
import java.io.File

/**
 * Builds container sessions from the app's paths and settings.
 *
 * One place owns the binding list, the environment and the proot binary
 * locations, so the installer, the desktop session and the package manager all
 * see an identical container.
 */
public class ContainerManager(
    private val paths: MyarchPaths,
    private val binaries: NativeBinaries = NativeBinaries(paths.nativeLibraryDir),
    /**
     * Host directories bound into every session on top of the defaults.
     *
     * This is how the app's external files directory reaches the container at
     * `/mnt/android`, so files can be moved between Android and Linux without
     * either side needing permissions it does not have.
     */
    private val sharedBindings: List<Binding> = emptyList(),
) {

    /** True when a container is installed and the native helpers are usable. */
    public fun isReady(): Boolean = paths.isInstalled && binaries.isComplete()

    /** Why the container cannot run, or `null` when it can. */
    public fun readinessProblem(): String? = binaries.describeProblem()
        ?: if (!paths.isInstalled) "No Arch container is installed yet." else null

    /**
     * Creates a session.
     *
     * @param extraBindings additional host paths, e.g. shared storage when the
     *   user has granted access to it
     */
    public fun session(extraBindings: List<Binding> = emptyList()): ContainerSession {
        paths.prepare()
        val spec = ProotSpec(
            prootBinary = binaries.proot.absolutePath,
            rootfs = paths.rootfs.absolutePath,
            bindings = ProotCommand.defaultBindings(
                runtimeDirHost = paths.runtimeDir.absolutePath,
                scriptsHost = paths.scripts.absolutePath,
                tmpHost = paths.prootTmp.absolutePath,
                guestRuntimeDir = MyarchPaths.GUEST_RUNTIME_DIR,
                guestScriptsDir = MyarchPaths.GUEST_SCRIPTS_DIR,
                extra = sharedBindings + extraBindings,
            ),
            environment = ProotCommand.defaultEnvironment(
                guestRuntimeDir = MyarchPaths.GUEST_RUNTIME_DIR,
                waylandSocketName = MyarchPaths.WAYLAND_SOCKET_NAME,
            ),
            command = listOf("/bin/bash", "--login"),
        )
        return ContainerSession(
            baseSpec = spec,
            prootTmpDir = paths.prootTmp,
            loaderPath = binaries.prootLoader.takeIf { it.exists() }?.absolutePath,
            nativeLibraryDir = paths.nativeLibraryDir,
        )
    }

    /**
     * Starts the Omarchy desktop session: Quickshell plus the user's autostart.
     *
     * The compositor is already listening by the time this runs, so the guest
     * finds `$WAYLAND_DISPLAY` immediately and the shell's own windows arrive as
     * the first Android tasks.
     */
    public fun startDesktop(): Process =
        session().start(listOf("/bin/bash", "--login", "${MyarchPaths.GUEST_SCRIPTS_DIR}/start-session.sh"))

    /** Runs a single desktop application, detached from the shell session. */
    public fun launchApplication(exec: String): Process =
        session().start(listOf("/bin/bash", "--login", "-c", "exec $exec"))

    public companion object {
        /**
         * The app's own external files directory, if it is usable.
         *
         * No permission is needed for this directory, and it is visible to the
         * user through Android's file manager, which makes it the natural
         * handover point between the two systems.
         */
        public fun optionalSharedStorage(context: Context): List<Binding> {
            val external: File? = context.getExternalFilesDir(null)
            return if (external != null && (external.exists() || external.mkdirs()) && external.canWrite()) {
                listOf(Binding(external.absolutePath, GUEST_SHARED_DIR))
            } else {
                emptyList()
            }
        }

        /** Where [optionalSharedStorage] appears inside the container. */
        public const val GUEST_SHARED_DIR: String = "/mnt/android"
    }
}
