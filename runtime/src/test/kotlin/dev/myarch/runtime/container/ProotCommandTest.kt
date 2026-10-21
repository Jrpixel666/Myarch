package dev.myarch.runtime.container

import com.google.common.truth.Truth.assertThat
import dev.myarch.runtime.MyarchPaths
import org.junit.Test

/**
 * The proot command line is the difference between a container that boots and
 * one that fails with an opaque error, so its shape is asserted rather than
 * discovered on a device.
 */
class ProotCommandTest {

    private val spec = ProotSpec(
        prootBinary = "/data/app/lib/arm64/libproot.so",
        rootfs = "/data/data/dev.myarch.app/files/myarch/rootfs",
        bindings = listOf(Binding("/dev"), Binding("/host/run", "/run/user/0")),
        environment = linkedMapOf("HOME" to "/root", "WAYLAND_DISPLAY" to "wayland-0"),
        command = listOf("/bin/bash", "--login"),
    )

    @Test
    fun `puts the rootfs, working directory and bindings before the command`() {
        val command = ProotCommand.build(spec)
        assertThat(command.first()).isEqualTo("/data/app/lib/arm64/libproot.so")
        assertThat(command).containsAtLeast("-r", spec.rootfs).inOrder()
        assertThat(command).containsAtLeast("-w", "/root").inOrder()
        assertThat(command).containsAtLeast("-b", "/dev").inOrder()
        assertThat(command).containsAtLeast("-b", "/host/run:/run/user/0").inOrder()
        // env -i clears Android's environment, then our variables follow.
        assertThat(command).containsAtLeast("/usr/bin/env", "-i", "HOME=/root").inOrder()
        assertThat(command.takeLast(2)).containsExactly("/bin/bash", "--login").inOrder()
    }

    @Test
    fun `asks for root emulation and link2symlink by default`() {
        val command = ProotCommand.build(spec)
        // -0 makes pacman believe it is root; --link2symlink is required
        // because pacman creates hard links Android's filesystem refuses.
        assertThat(command).contains("-0")
        assertThat(command).contains("--link2symlink")
        assertThat(command).contains("--kill-on-exit")
    }

    @Test
    fun `those behaviours can be turned off`() {
        val command = ProotCommand.build(spec.copy(linkToSymlink = false, killOnExit = false))
        assertThat(command).doesNotContain("--link2symlink")
        assertThat(command).doesNotContain("--kill-on-exit")
    }

    @Test
    fun `a binding with the same host and guest path is written once`() {
        assertThat(Binding("/proc").toArgument()).isEqualTo("/proc")
        assertThat(Binding("/a", "/b").toArgument()).isEqualTo("/a:/b")
    }

    @Test
    fun `default bindings cover what a graphical session needs`() {
        val bindings = ProotCommand.defaultBindings(
            runtimeDirHost = "/host/run",
            scriptsHost = "/host/scripts",
            tmpHost = "/host/tmp",
            guestRuntimeDir = MyarchPaths.GUEST_RUNTIME_DIR,
            guestScriptsDir = MyarchPaths.GUEST_SCRIPTS_DIR,
        ).map { it.toArgument() }

        assertThat(bindings).containsAtLeast("/dev", "/proc", "/sys")
        // The Wayland socket has to be visible to guest processes.
        assertThat(bindings).contains("/host/run:/run/user/0")
        assertThat(bindings).contains("/host/scripts:/opt/myarch")
        // Mesa, Qt and pulseaudio all need a writable /dev/shm.
        assertThat(bindings).contains("/host/tmp:/dev/shm")
        assertThat(bindings).contains("/proc/self/fd:/dev/fd")
    }

    @Test
    fun `default environment selects Wayland for every toolkit`() {
        val environment = ProotCommand.defaultEnvironment(
            guestRuntimeDir = "/run/user/0",
            waylandSocketName = "wayland-0",
        )
        assertThat(environment["WAYLAND_DISPLAY"]).isEqualTo("wayland-0")
        assertThat(environment["XDG_RUNTIME_DIR"]).isEqualTo("/run/user/0")
        assertThat(environment["QT_QPA_PLATFORM"]).isEqualTo("wayland")
        assertThat(environment["GDK_BACKEND"]).isEqualTo("wayland")
        assertThat(environment["SDL_VIDEODRIVER"]).isEqualTo("wayland")
        assertThat(environment["XDG_SESSION_TYPE"]).isEqualTo("wayland")
        // No GPU passthrough yet, so Mesa must not go looking for a device.
        assertThat(environment["LIBGL_ALWAYS_SOFTWARE"]).isEqualTo("1")
        assertThat(environment["PATH"]).contains("/usr/bin")
    }

    @Test
    fun `extra environment entries override the defaults`() {
        val environment = ProotCommand.defaultEnvironment(
            guestRuntimeDir = "/run/user/0",
            waylandSocketName = "wayland-0",
            extra = mapOf("LIBGL_ALWAYS_SOFTWARE" to "0", "MYARCH_EXTRA" to "1"),
        )
        assertThat(environment["LIBGL_ALWAYS_SOFTWARE"]).isEqualTo("0")
        assertThat(environment["MYARCH_EXTRA"]).isEqualTo("1")
    }
}
