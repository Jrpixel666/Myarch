package dev.myarch.wayland.core

import dev.myarch.wayland.wire.EncodedMessage
import dev.myarch.wayland.wire.Interface
import dev.myarch.wayland.wire.Message
import dev.myarch.wayland.wire.MessageEncoder
import dev.myarch.wayland.wire.WIRE_ORDER
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * A scripted client, for driving the compositor without a socket.
 *
 * Wayland clients allocate their own object ids, so a whole session can be
 * written out in advance as a byte stream and replayed. That is enough to
 * exercise the real dispatch path -- decoding, resource creation, roles,
 * commits -- in a plain JVM test.
 */
internal class FakeClientTransport : ClientTransport {

    private val requests = ArrayDeque<EncodedMessage>()
    private val pendingFds = ArrayDeque<FileDescriptor>()

    /** Every event the server wrote, in order. */
    val events = ArrayList<ByteArray>()

    override val describe: String = "fake client"

    var closed = false
        private set

    /** Queues a request, as if the client had sent it. */
    fun send(objectId: Int, message: Message, args: List<Any?>) {
        requests.add(MessageEncoder.encode(objectId, message, args))
    }

    /** Queues a request by interface and message name, for readability. */
    fun request(objectId: Int, spec: Interface, name: String, vararg args: Any?) {
        val message = spec.requests.single { it.name == name }
        send(objectId, message, args.toList())
    }

    override fun read(dst: ByteBuffer): Int {
        val next = requests.removeFirstOrNull() ?: return -1
        dst.put(next.bytes)
        pendingFds.addAll(next.fds)
        return next.bytes.size
    }

    override fun write(bytes: ByteArray, fds: List<FileDescriptor>) {
        events.add(bytes)
    }

    override fun drainReceivedFds(): List<FileDescriptor> {
        if (pendingFds.isEmpty()) return emptyList()
        val drained = pendingFds.toList()
        pendingFds.clear()
        return drained
    }

    override fun close() {
        closed = true
    }

    /** Decodes the events the server sent, as (objectId, opcode) pairs. */
    fun eventHeaders(): List<Pair<Int, Int>> = events.map { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(WIRE_ORDER)
        val objectId = buffer.getInt(0)
        val opcode = buffer.getInt(4) and 0xFFFF
        objectId to opcode
    }

    /** True when the server sent `wl_display.error`, which tests should not expect. */
    fun protocolErrors(): List<String> = events.mapNotNull { bytes ->
        val buffer = ByteBuffer.wrap(bytes).order(WIRE_ORDER)
        val objectId = buffer.getInt(0)
        val opcode = buffer.getInt(4) and 0xFFFF
        if (objectId != DISPLAY_ID || opcode != 0) return@mapNotNull null
        // wl_display.error is (object id, code, message).
        val messageLength = buffer.getInt(16)
        String(bytes, 20, (messageLength - 1).coerceAtLeast(0))
    }
}

/** Records what the compositor asked the Android layer to do. */
internal class RecordingSink : WindowSink {
    val added = ArrayList<WindowInfo>()
    val changed = ArrayList<WindowInfo>()
    val removed = ArrayList<Int>()
    val frames = ArrayList<LayerFrame>()
    val layersRemoved = ArrayList<Pair<Int, Int>>()
    val panelsAdded = ArrayList<LayerSurfaceInfo>()
    val panelFrames = ArrayList<LayerSurfaceInfo>()
    val panelsRemoved = ArrayList<Int>()

    /** Copies of the pixels, since the compositor reuses its buffer. */
    val framePixels = ArrayList<IntArray>()

    override fun onWindowAdded(info: WindowInfo) {
        added.add(info)
    }

    override fun onWindowChanged(info: WindowInfo) {
        changed.add(info)
    }

    override fun onLayerFrame(layer: LayerFrame) {
        frames.add(layer)
        val pixels = IntArray(layer.frame.width * layer.frame.height)
        val buffer = layer.frame.pixels.duplicate()
        buffer.position(0)
        for (index in pixels.indices) {
            // Stored as R,G,B,A bytes; read back as one int for easy assertions.
            val r = buffer.get().toInt() and 0xFF
            val g = buffer.get().toInt() and 0xFF
            val b = buffer.get().toInt() and 0xFF
            val a = buffer.get().toInt() and 0xFF
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        framePixels.add(pixels)
    }

    override fun onLayerRemoved(windowId: Int, surfaceId: Int) {
        layersRemoved.add(windowId to surfaceId)
    }

    override fun onWindowRemoved(windowId: Int) {
        removed.add(windowId)
    }

    override fun onPanelAdded(info: LayerSurfaceInfo) {
        panelsAdded.add(info)
    }

    override fun onPanelChanged(info: LayerSurfaceInfo) {
        panelsAdded.add(info)
    }

    override fun onPanelFrame(info: LayerSurfaceInfo, frame: FrameBuffer) {
        panelFrames.add(info)
    }

    override fun onPanelRemoved(surfaceId: Int) {
        panelsRemoved.add(surfaceId)
    }
}
