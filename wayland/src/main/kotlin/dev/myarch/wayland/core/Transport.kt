package dev.myarch.wayland.core

import java.io.Closeable
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * A connected client, abstracted away from `LocalSocket` so the compositor
 * core can be exercised by unit tests on a plain JVM.
 */
public interface ClientTransport : Closeable {
    /** Reads into [dst]; returns the byte count, or -1 at end of stream. */
    public fun read(dst: ByteBuffer): Int

    /** Writes [bytes], attaching [fds] as `SCM_RIGHTS` ancillary data. */
    public fun write(bytes: ByteArray, fds: List<FileDescriptor>)

    /**
     * Descriptors received since the last call, in arrival order.
     *
     * Wayland matches descriptors to `fd` arguments by order rather than by
     * position, so the queue is drained as messages are decoded.
     */
    public fun drainReceivedFds(): List<FileDescriptor>

    /** A label used in logs, e.g. the peer pid when the OS exposes it. */
    public val describe: String
}

/** The listening socket. */
public interface ServerTransport : Closeable {
    /** Blocks until a client connects, or returns `null` once closed. */
    public fun accept(): ClientTransport?
}
