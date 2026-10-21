package dev.myarch.wayland.android

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import dev.myarch.wayland.core.ClientTransport
import dev.myarch.wayland.core.ServerTransport
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The Wayland socket, on Android.
 *
 * `LocalSocket` is the only public API that both binds a filesystem AF_UNIX
 * socket (which is what `WAYLAND_DISPLAY` points at inside the container) and
 * passes file descriptors over `SCM_RIGHTS` (which `wl_shm` and
 * `wl_keyboard.keymap` depend on). The alternative -- `java.nio`'s
 * `UnixDomainSocketAddress` -- cannot do ancillary data at all.
 *
 * @param socketPath the path to create, e.g.
 *   `<rootfs>/run/user/0/wayland-0`. The file must be inside a directory the
 *   container can see.
 */
public class LocalSocketServerTransport(
    private val socketPath: File,
) : ServerTransport {

    private val serverSocket: LocalServerSocket

    init {
        // Bind fails if a stale socket file is in the way, which is the normal
        // state after the app was killed rather than shut down.
        if (socketPath.exists() && !socketPath.delete()) {
            throw IOException("cannot remove the stale socket at $socketPath")
        }
        socketPath.parentFile?.mkdirs()
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        socket.bind(LocalSocketAddress(socketPath.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        serverSocket = LocalServerSocket(socket.fileDescriptor)
    }

    /** Where clients should point `WAYLAND_DISPLAY`. */
    public val path: String get() = socketPath.absolutePath

    override fun accept(): ClientTransport? {
        val socket = serverSocket.accept() ?: return null
        return LocalSocketClientTransport(socket)
    }

    override fun close() {
        runCatching { serverSocket.close() }
        runCatching { socketPath.delete() }
    }
}

/** One connected client over `LocalSocket`. */
public class LocalSocketClientTransport(
    private val socket: LocalSocket,
) : ClientTransport {

    private val input = socket.inputStream
    private val output = socket.outputStream
    private val scratch = ByteArray(READ_CHUNK)
    private val pendingFds = ArrayList<FileDescriptor>(2)

    override val describe: String
        get() = runCatching { "pid ${socket.peerCredentials.pid}" }.getOrDefault("unknown peer")

    override fun read(dst: ByteBuffer): Int {
        val limit = minOf(scratch.size, dst.remaining())
        if (limit == 0) {
            // The caller's buffer is full, which means a single message was
            // larger than the read buffer: impossible, since Wayland caps
            // messages at 64 KiB and the buffer is 64 KiB.
            throw IOException("read buffer is full with an incomplete message")
        }
        val read = input.read(scratch, 0, limit)
        if (read <= 0) return -1
        // Ancillary descriptors belong to the bytes just read, so collect them
        // immediately: the next read would clear them.
        socket.ancillaryFileDescriptors?.let { fds ->
            for (fd in fds) {
                if (fd != null) pendingFds.add(fd)
            }
        }
        dst.put(scratch, 0, read)
        return read
    }

    override fun write(bytes: ByteArray, fds: List<FileDescriptor>) {
        if (fds.isNotEmpty()) {
            socket.setFileDescriptorsForSend(fds.toTypedArray())
        }
        output.write(bytes)
        output.flush()
        if (fds.isNotEmpty()) {
            // The array is consumed by the next write; clearing it stops the
            // same descriptors being attached to unrelated events.
            socket.setFileDescriptorsForSend(null)
        }
    }

    override fun drainReceivedFds(): List<FileDescriptor> {
        if (pendingFds.isEmpty()) return emptyList()
        val drained = pendingFds.toList()
        pendingFds.clear()
        return drained
    }

    override fun close() {
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    private companion object {
        const val READ_CHUNK = 1 shl 16
    }
}
