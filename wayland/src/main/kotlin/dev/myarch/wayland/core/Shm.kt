package dev.myarch.wayland.core

import dev.myarch.wayland.protocol.WlBuffer
import dev.myarch.wayland.protocol.WlShm
import dev.myarch.wayland.protocol.WlShmPool
import dev.myarch.wayland.wire.WireMessage
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * `wl_shm`: the software rendering path.
 *
 * Clients hand us a descriptor for a shared memory file, we map it read-only,
 * and every `wl_buffer` cut from that pool is a rectangle inside the mapping.
 * This is the path GTK, Qt and `foot` use when no GPU is available, and it is
 * the only path Myarch supports today -- see docs/ARCHITECTURE.md for the
 * dmabuf work that would replace the copy with a zero-copy import.
 */
public class ShmResource(client: Client, id: Int, version: Int) :
    Resource(client, id, version, WlShm.SPEC) {

    init {
        // Advertised at bind time: the client needs them before it allocates.
        send("format", ShmFormat.ARGB8888)
        send("format", ShmFormat.XRGB8888)
    }

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlShm.Request.CREATE_POOL -> {
                val poolId = message.newId(0)
                val fd = message.fd(1)
                val size = message.int(2)
                if (size <= 0) {
                    throw ProtocolError(
                        id,
                        WlShm.Error.INVALID_STRIDE,
                        "pool size must be positive, got $size",
                    )
                }
                client.addResource(ShmPoolResource(client, poolId, version, fd, size))
            }
            WlShm.Request.RELEASE -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_shm opcode ${message.opcode}")
        }
    }
}

/** A mapped shared memory pool a client carves buffers out of. */
public class ShmPoolResource(
    client: Client,
    id: Int,
    version: Int,
    private val fd: FileDescriptor,
    initialSize: Int,
) : Resource(client, id, version, WlShmPool.SPEC) {

    private var stream: FileInputStream? = null
    private var channel: FileChannel? = null

    /** The mapping, or `null` if the client gave us something unmappable. */
    public var mapping: ByteBuffer? = null
        private set

    public var size: Int = initialSize
        private set

    /** Buffers still referencing this pool; the mapping outlives the pool for them. */
    private val liveBuffers = HashSet<Int>()

    init {
        remap(initialSize)
    }

    private fun remap(newSize: Int) {
        try {
            val existingStream = stream ?: FileInputStream(fd).also { stream = it }
            val existingChannel = channel ?: existingStream.channel.also { channel = it }
            mapping = existingChannel.map(FileChannel.MapMode.READ_ONLY, 0, newSize.toLong())
            size = newSize
        } catch (io: IOException) {
            // A client that lies about its pool size, or passes a descriptor
            // that cannot be mapped (a pipe, say), gets a protocol error rather
            // than taking the compositor down with it.
            mapping = null
            throw ProtocolError(
                id,
                WlShm.Error.INVALID_FD,
                "cannot map a $newSize byte pool: ${io.message}",
            )
        }
    }

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlShmPool.Request.CREATE_BUFFER -> {
                val bufferId = message.newId(0)
                val offset = message.int(1)
                val width = message.int(2)
                val height = message.int(3)
                val stride = message.int(4)
                val format = message.uint(5)
                validateBuffer(offset, width, height, stride, format)
                val buffer = BufferResource(client, bufferId, this, offset, width, height, stride, format)
                client.addResource(buffer)
                liveBuffers.add(bufferId)
            }
            WlShmPool.Request.DESTROY -> destroySelf()
            WlShmPool.Request.RESIZE -> {
                val newSize = message.int(0)
                if (newSize < size) {
                    throw ProtocolError(
                        id,
                        WlShm.Error.INVALID_FD,
                        "pools may only grow: $size -> $newSize",
                    )
                }
                remap(newSize)
            }
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_shm_pool opcode ${message.opcode}")
        }
    }

    @Suppress("LongParameterList")
    private fun validateBuffer(offset: Int, width: Int, height: Int, stride: Int, format: Int) {
        if (!ShmFormat.isSupported(format)) {
            throw ProtocolError(
                id,
                WlShm.Error.INVALID_FORMAT,
                "format ${ShmFormat.name(format)} is not one we advertised",
            )
        }
        if (width <= 0 || height <= 0) {
            throw ProtocolError(id, WlShm.Error.INVALID_STRIDE, "buffer size ${width}x$height")
        }
        if (stride < width * FrameBuffer.BYTES_PER_PIXEL) {
            throw ProtocolError(id, WlShm.Error.INVALID_STRIDE, "stride $stride for width $width")
        }
        // Checked in Long arithmetic: the product of three ints overflows for
        // sizes a malicious client can easily ask for.
        val required = offset.toLong() + stride.toLong() * height.toLong()
        if (offset < 0 || required > size.toLong()) {
            throw ProtocolError(
                id,
                WlShm.Error.INVALID_STRIDE,
                "buffer needs $required bytes but the pool holds $size",
            )
        }
    }

    internal fun onBufferDestroyed(bufferId: Int) {
        liveBuffers.remove(bufferId)
    }

    override fun onDestroy() {
        // wl_shm_pool.destroy only retires the object: buffers already created
        // stay valid, so the mapping is released when the last one goes.
        if (liveBuffers.isEmpty()) {
            releaseMapping()
        }
    }

    internal fun releaseMapping() {
        mapping = null
        runCatching { channel?.close() }
        runCatching { stream?.close() }
        channel = null
        stream = null
    }
}

/** A rectangle of pixels inside a [ShmPoolResource]. */
@Suppress("LongParameterList")
public class BufferResource(
    client: Client,
    id: Int,
    public val pool: ShmPoolResource,
    public val offset: Int,
    public val width: Int,
    public val height: Int,
    public val stride: Int,
    public val format: Int,
) : Resource(client, id, WlBuffer.VERSION, WlBuffer.SPEC) {

    override fun handleRequest(message: WireMessage) {
        when (message.opcode) {
            WlBuffer.Request.DESTROY -> destroySelf()
            else -> throw ProtocolError(id, DisplayError.INVALID_METHOD, "wl_buffer opcode ${message.opcode}")
        }
    }

    /**
     * Copies the buffer into [dest] in Android's pixel order.
     *
     * @return false when the pool mapping has gone away, in which case the
     *   frame is simply skipped.
     */
    public fun copyInto(dest: ByteBuffer): Boolean {
        val mapping = pool.mapping ?: return false
        dest.clear()
        PixelConverter.toAndroidArgb8888(mapping, offset, width, height, stride, format, dest)
        dest.flip()
        return true
    }

    /** Tells the client it may draw into the buffer again. */
    public fun release() {
        send("release")
    }

    override fun onDestroy() {
        pool.onBufferDestroyed(id)
    }
}
