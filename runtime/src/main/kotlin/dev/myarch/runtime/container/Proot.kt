package dev.myarch.runtime.container

/** A host path made visible inside the container. */
public data class Binding(val hostPath: String, val guestPath: String = hostPath) {
    /** proot's `-b` syntax. */
    public fun toArgument(): String = if (hostPath == guestPath) hostPath else "$hostPath:$guestPath"
}

/** Everything needed to build one proot invocation. */
public data class ProotSpec(
    /** Absolute path to the proot executable (shipped as a `lib*.so`). */
    val prootBinary: String,
    /** The rootfs to chroot into. */
    val rootfs: String,
    val bindings: List<Binding>,
    val environment: Map<String, String>,
    /** The command to run inside the container. */
    val command: List<String>,
    val workingDirectory: String = "/root",
    /**
     * Emulate hard links with symlinks.
     *
     * Android's filesystem refuses hard links across some directories, and
     * pacman creates them constantly, so without this package installs fail in
     * confusing ways.
     */
    val linkToSymlink: Boolean = true,
    /** Kill guest processes when the leader exits, so nothing is orphaned. */
    val killOnExit: Boolean = true,
)

/**
 * Builds the proot command line.
 *
 * Kept as a pure function because the argument order and the exact set of
 * bindings are the difference between a container that boots and one that
 * fails with an unhelpful error, and that deserves tests rather than trial and
 * error on a phone.
 */
public object ProotCommand {

    public fun build(spec: ProotSpec): List<String> = buildList {
        add(spec.prootBinary)
        if (spec.killOnExit) add("--kill-on-exit")
        if (spec.linkToSymlink) add("--link2symlink")
        // -0 makes the guest see uid 0. Nothing is actually privileged: proot
        // fakes the credentials so that packages which insist on being root
        // (pacman, in particular) will run.
        add("-0")
        add("-r")
        add(spec.rootfs)
        add("-w")
        add(spec.workingDirectory)
        for (binding in spec.bindings) {
            add("-b")
            add(binding.toArgument())
        }
        // `env -i` clears whatever Android put in the environment, so the guest
        // starts from exactly the variables we list and nothing else.
        add("/usr/bin/env")
        add("-i")
        for ((key, value) in spec.environment) {
            add("$key=$value")
        }
        addAll(spec.command)
    }

    /**
     * The bindings every Myarch container needs.
     *
     * @param runtimeDirHost the host directory holding the Wayland socket
     * @param scriptsHost the host directory holding Myarch's guest-side scripts
     * @param tmpHost a host directory to use as the guest's `/tmp`
     */
    public fun defaultBindings(
        runtimeDirHost: String,
        scriptsHost: String,
        tmpHost: String,
        guestRuntimeDir: String,
        guestScriptsDir: String,
        extra: List<Binding> = emptyList(),
    ): List<Binding> = buildList {
        add(Binding("/dev"))
        add(Binding("/proc"))
        add(Binding("/sys"))
        // Android keeps its own libraries here; some guest tooling inspects it,
        // and binding it read-only costs nothing.
        add(Binding("/system"))
        add(Binding("/proc/self/fd", "/dev/fd"))
        add(Binding("/proc/self/fd/0", "/dev/stdin"))
        add(Binding("/proc/self/fd/1", "/dev/stdout"))
        add(Binding("/proc/self/fd/2", "/dev/stderr"))
        // /dev/shm has to be writable: Mesa, Qt and pulseaudio all use it.
        add(Binding(tmpHost, "/dev/shm"))
        add(Binding(tmpHost, "/tmp"))
        add(Binding(runtimeDirHost, guestRuntimeDir))
        add(Binding(scriptsHost, guestScriptsDir))
        addAll(extra)
    }

    /**
     * The environment a graphical Wayland session needs.
     *
     * Each toolkit has its own switch for choosing Wayland over X11, and
     * missing one means that application silently fails to start -- so they are
     * all set explicitly.
     */
    public fun defaultEnvironment(
        guestRuntimeDir: String,
        waylandSocketName: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        put("HOME", "/root")
        put("USER", "root")
        put("LOGNAME", "root")
        put("TERM", "xterm-256color")
        put("LANG", "C.UTF-8")
        put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/bin:/usr/sbin:/sbin:/bin")
        put("XDG_RUNTIME_DIR", guestRuntimeDir)
        put("XDG_SESSION_TYPE", "wayland")
        put("XDG_CURRENT_DESKTOP", "Hyprland")
        put("XDG_SESSION_DESKTOP", "myarch")
        put("WAYLAND_DISPLAY", waylandSocketName)
        put("QT_QPA_PLATFORM", "wayland")
        put("GDK_BACKEND", "wayland")
        put("CLUTTER_BACKEND", "wayland")
        put("SDL_VIDEODRIVER", "wayland")
        put("MOZ_ENABLE_WAYLAND", "1")
        put("ELECTRON_OZONE_PLATFORM_HINT", "wayland")
        // Myarch scales windows itself, so toolkits should not scale again.
        put("GDK_SCALE", "1")
        put("QT_AUTO_SCREEN_SCALE_FACTOR", "0")
        put("QT_WAYLAND_DISABLE_WINDOWDECORATION", "1")
        // No GPU passthrough yet: force the software rasteriser rather than
        // letting Mesa fail to find a device and take the client down with it.
        put("LIBGL_ALWAYS_SOFTWARE", "1")
        put("GALLIUM_DRIVER", "llvmpipe")
        put("QSG_RENDER_LOOP", "basic")
        put("QT_QUICK_BACKEND", "software")
        // Java apps under a non-reparenting WM need this or they show a blank
        // window.
        put("_JAVA_AWT_WM_NONREPARENTING", "1")
        put("MYARCH", "1")
        putAll(extra)
    }
}
