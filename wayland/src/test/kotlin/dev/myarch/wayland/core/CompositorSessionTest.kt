package dev.myarch.wayland.core

import com.google.common.truth.Truth.assertThat
import dev.myarch.wayland.protocol.WlCompositor
import dev.myarch.wayland.protocol.WlDisplay
import dev.myarch.wayland.protocol.WlRegistry
import dev.myarch.wayland.protocol.WlShm
import dev.myarch.wayland.protocol.WlShmPool
import dev.myarch.wayland.protocol.WlSurface
import dev.myarch.wayland.protocol.XdgSurface
import dev.myarch.wayland.protocol.XdgToplevel
import dev.myarch.wayland.protocol.XdgWmBase
import dev.myarch.wayland.protocol.ZwlrLayerShellV1
import dev.myarch.wayland.protocol.ZwlrLayerSurfaceV1
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a whole client session against the compositor.
 *
 * This is the test that would catch a regression anywhere in the chain: wire
 * decoding, registry binds, shm mapping, role assignment, the commit that maps
 * a window, and the frame that reaches the Android layer. It uses a real file
 * as the shared memory pool, which is exactly what a client's `memfd` looks
 * like to us.
 */
class CompositorSessionTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val width = 4
    private val height = 2
    private val stride = width * 4

    private fun globalName(interfaceName: String, compositor: Compositor): Int =
        compositor.globals.single { it.spec.name == interfaceName }.name

    /** Writes a pool whose pixels are a known pattern, in wl_shm byte order. */
    private fun shmPool(): Pair<java.io.FileDescriptor, Int> {
        val file = folder.newFile("pool")
        val size = stride * height
        val buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { index ->
            // wl_shm ARGB8888 is 0xAARRGGBB little endian: B, G, R, A in memory.
            buffer.put(0x10.toByte()) // blue
            buffer.put(0x20.toByte()) // green
            buffer.put((0x30 + index).toByte()) // red, varies per pixel
            buffer.put(0xFF.toByte()) // alpha
        }
        val handle = RandomAccessFile(file, "rw")
        handle.write(buffer.array())
        handle.seek(0)
        return handle.fd to size
    }

    private fun session(): Triple<Compositor, FakeClientTransport, RecordingSink> {
        val sink = RecordingSink()
        val compositor = Compositor(
            CompositorConfig(outputWidthPx = 1080, outputHeightPx = 2400, uiScale = 2),
            sink,
        )
        return Triple(compositor, FakeClientTransport(), sink)
    }

    @Test
    fun `a toplevel that commits a buffer becomes a window with pixels`() {
        val (compositor, transport, sink) = session()
        val (fd, poolSize) = shmPool()

        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlCompositor.NAME, compositor),
            Triple(WlCompositor.NAME, WlCompositor.VERSION, 3),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlShm.NAME, compositor),
            Triple(WlShm.NAME, WlShm.VERSION, 4),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(XdgWmBase.NAME, compositor),
            Triple(XdgWmBase.NAME, XdgWmBase.VERSION, 5),
        )
        transport.request(3, WlCompositor.SPEC, "create_surface", 6)
        transport.request(4, WlShm.SPEC, "create_pool", 7, fd, poolSize)
        transport.request(7, WlShmPool.SPEC, "create_buffer", 8, 0, width, height, stride, 0)
        transport.request(5, XdgWmBase.SPEC, "get_xdg_surface", 9, 6)
        transport.request(9, XdgSurface.SPEC, "get_toplevel", 10)
        transport.request(10, XdgToplevel.SPEC, "set_app_id", "org.example.editor")
        transport.request(10, XdgToplevel.SPEC, "set_title", "Untitled document")
        transport.request(6, WlSurface.SPEC, "attach", 8, 0, 0)
        transport.request(6, WlSurface.SPEC, "commit")

        Client(compositor, transport, clientId = 1).serve()

        assertThat(transport.protocolErrors()).isEmpty()
        assertThat(sink.added).hasSize(1)
        val window = sink.added.single()
        assertThat(window.title).isEqualTo("Untitled document")
        assertThat(window.appId).isEqualTo("org.example.editor")

        assertThat(sink.frames).hasSize(1)
        val frame = sink.frames.single()
        assertThat(frame.windowId).isEqualTo(window.windowId)
        assertThat(frame.frame.width).isEqualTo(width)
        assertThat(frame.frame.height).isEqualTo(height)
        assertThat(frame.x).isEqualTo(0)
        assertThat(frame.y).isEqualTo(0)

        // Red and blue must have been swapped into Android's byte order, and
        // the per-pixel red value must survive.
        // The source pixel was B=0x10, G=0x20, R=0x30+index, A=0xFF, and the
        // sink reads the converted bytes back as 0xAARRGGBB.
        val pixels = sink.framePixels.single()
        assertThat(pixels).hasLength(width * height)
        assertThat(pixels[0]).isEqualTo((0xFF shl 24) or (0x30 shl 16) or (0x20 shl 8) or 0x10)
        assertThat(pixels[3]).isEqualTo((0xFF shl 24) or (0x33 shl 16) or (0x20 shl 8) or 0x10)
    }

    @Test
    fun `the window list is emptied when the client disconnects`() {
        val (compositor, transport, sink) = session()
        val (fd, poolSize) = shmPool()

        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlCompositor.NAME, compositor),
            Triple(WlCompositor.NAME, WlCompositor.VERSION, 3),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlShm.NAME, compositor),
            Triple(WlShm.NAME, WlShm.VERSION, 4),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(XdgWmBase.NAME, compositor),
            Triple(XdgWmBase.NAME, XdgWmBase.VERSION, 5),
        )
        transport.request(3, WlCompositor.SPEC, "create_surface", 6)
        transport.request(4, WlShm.SPEC, "create_pool", 7, fd, poolSize)
        transport.request(7, WlShmPool.SPEC, "create_buffer", 8, 0, width, height, stride, 0)
        transport.request(5, XdgWmBase.SPEC, "get_xdg_surface", 9, 6)
        transport.request(9, XdgSurface.SPEC, "get_toplevel", 10)
        transport.request(6, WlSurface.SPEC, "attach", 8, 0, 0)
        transport.request(6, WlSurface.SPEC, "commit")

        val client = Client(compositor, transport, clientId = 1)
        client.serve()

        assertThat(sink.added).hasSize(1)
        // serve() returns once the stream ends, which closes the client.
        assertThat(sink.removed).containsExactly(sink.added.single().windowId)
        assertThat(compositor.windows()).isEmpty()
        assertThat(transport.closed).isTrue()
    }

    @Test
    fun `a layer surface becomes a panel rather than a window`() {
        val (compositor, transport, sink) = session()
        val (fd, poolSize) = shmPool()

        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlCompositor.NAME, compositor),
            Triple(WlCompositor.NAME, WlCompositor.VERSION, 3),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlShm.NAME, compositor),
            Triple(WlShm.NAME, WlShm.VERSION, 4),
        )
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(ZwlrLayerShellV1.NAME, compositor),
            Triple(ZwlrLayerShellV1.NAME, ZwlrLayerShellV1.VERSION, 5),
        )
        transport.request(3, WlCompositor.SPEC, "create_surface", 6)
        transport.request(4, WlShm.SPEC, "create_pool", 7, fd, poolSize)
        transport.request(7, WlShmPool.SPEC, "create_buffer", 8, 0, width, height, stride, 0)
        transport.request(
            5,
            ZwlrLayerShellV1.SPEC,
            "get_layer_surface",
            9,
            6,
            0,
            ZwlrLayerShellV1.Layer.TOP,
            "myarch-bar",
        )
        transport.request(9, ZwlrLayerSurfaceV1.SPEC, "set_anchor", ZwlrLayerSurfaceV1.Anchor.TOP or ZwlrLayerSurfaceV1.Anchor.LEFT or ZwlrLayerSurfaceV1.Anchor.RIGHT)
        transport.request(9, ZwlrLayerSurfaceV1.SPEC, "set_size", 0, 38)
        transport.request(9, ZwlrLayerSurfaceV1.SPEC, "set_exclusive_zone", 38)
        transport.request(6, WlSurface.SPEC, "attach", 8, 0, 0)
        transport.request(6, WlSurface.SPEC, "commit")

        Client(compositor, transport, clientId = 1).serve()

        assertThat(transport.protocolErrors()).isEmpty()
        // A bar is not an application window: it must not get a task.
        assertThat(sink.added).isEmpty()
        assertThat(sink.panelFrames).isNotEmpty()
        val panel = sink.panelsAdded.first()
        assertThat(panel.namespace).isEqualTo("myarch-bar")
        assertThat(panel.layer).isEqualTo(ZwlrLayerShellV1.Layer.TOP)
        assertThat(panel.exclusiveZone).isEqualTo(38)
        assertThat(panel.height).isEqualTo(38)
        // Anchored left and right with no width means "stretch": 1080 device
        // pixels at scale 2 is 540 logical pixels.
        assertThat(panel.width).isEqualTo(540)
    }

    @Test
    fun `binding a global at an unsupported version is a protocol error`() {
        val (compositor, transport, _) = session()
        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlCompositor.NAME, compositor),
            Triple(WlCompositor.NAME, WlCompositor.VERSION + 5, 3),
        )
        Client(compositor, transport, clientId = 1).serve()
        assertThat(transport.protocolErrors()).hasSize(1)
        assertThat(transport.protocolErrors().single()).contains("wl_compositor")
    }

    @Test
    fun `a buffer larger than its pool is rejected instead of crashing`() {
        val (compositor, transport, _) = session()
        val (fd, poolSize) = shmPool()
        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        transport.request(
            2,
            WlRegistry.SPEC,
            "bind",
            globalName(WlShm.NAME, compositor),
            Triple(WlShm.NAME, WlShm.VERSION, 4),
        )
        transport.request(4, WlShm.SPEC, "create_pool", 7, fd, poolSize)
        // Claims far more rows than the pool can hold.
        transport.request(7, WlShmPool.SPEC, "create_buffer", 8, 0, width, height * 100, stride, 0)
        Client(compositor, transport, clientId = 1).serve()
        assertThat(transport.protocolErrors()).hasSize(1)
        assertThat(transport.protocolErrors().single()).contains("pool holds")
    }

    @Test
    fun `the registry advertises every global we implement`() {
        val (compositor, transport, _) = session()
        transport.request(DISPLAY_ID, WlDisplay.SPEC, "get_registry", 2)
        Client(compositor, transport, clientId = 1).serve()

        // One wl_registry.global event per global, all on object 2.
        val globalEvents = transport.eventHeaders().filter { it.first == 2 && it.second == WlRegistry.Event.GLOBAL }
        assertThat(globalEvents).hasSize(compositor.globals.size)
        assertThat(compositor.globals.map { it.spec.name }).containsExactly(
            "wl_compositor",
            "wl_subcompositor",
            "wl_shm",
            "wl_seat",
            "wl_output",
            "xdg_wm_base",
            "zxdg_decoration_manager_v1",
            "zwlr_layer_shell_v1",
        )
    }
}
