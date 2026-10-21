package dev.myarch.wayland.wire

import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Thrown when a client sends something that cannot be parsed as a Wayland
 * message. The connection is unrecoverable at that point and must be dropped:
 * the stream has no resynchronisation point.
 */
public class WireFormatException(message: String) : RuntimeException(message)

/**
 * Wayland uses the host byte order on the wire. Every Android ABI we target
 * (arm64-v8a, armeabi-v7a, x86_64) is little endian, and the guest runs inside
 * our own container on the same CPU, so the two always agree.
 */
public val WIRE_ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

/** Encodes events into the wire format. */
public object MessageEncoder {

    /**
     * Encodes a single event.
     *
     * @param objectId the object the event originates from
     * @param message the event's layout, from the generated protocol tables
     * @param args values positionally matching [Message.args]; [ArgType.FD]
     *   arguments must be [FileDescriptor]s and are returned separately for the
     *   caller to attach as ancillary data
     */
    public fun encode(objectId: Int, message: Message, args: List<Any?>): EncodedMessage {
        require(args.size == message.args.size) {
            "${message.name} expects ${message.args.size} arguments, got ${args.size}"
        }
        val fds = ArrayList<FileDescriptor>(1)
        val body = ByteBuffer.allocate(MAX_MESSAGE_SIZE).order(WIRE_ORDER)
        body.position(HEADER_SIZE)
        for ((index, spec) in message.args.withIndex()) {
            writeArg(body, spec, args[index], fds, message)
        }
        val size = body.position()
        if (size > MAX_MESSAGE_SIZE) {
            throw WireFormatException("${message.name} encodes to $size bytes, over the 64 KiB limit")
        }
        body.putInt(0, objectId)
        body.putInt(4, (size shl 16) or (message.opcode and 0xFFFF))
        body.flip()
        val bytes = ByteArray(size)
        body.get(bytes)
        return EncodedMessage(bytes, fds)
    }

    private fun writeArg(
        out: ByteBuffer,
        spec: Arg,
        value: Any?,
        fds: MutableList<FileDescriptor>,
        message: Message,
    ) {
        when (spec.type) {
            ArgType.INT, ArgType.UINT -> out.putInt(asInt(value, spec, message))
            ArgType.FIXED -> out.putInt(
                when (value) {
                    is Fixed -> value.raw
                    is Int -> Fixed.of(value).raw
                    is Double -> Fixed.of(value).raw
                    else -> throw WireFormatException("${message.name}.${spec.name} wants a fixed, got $value")
                },
            )
            ArgType.OBJECT, ArgType.NEW_ID -> {
                if (spec.type == ArgType.NEW_ID && spec.interfaceName == null) {
                    // An untyped new_id (wl_registry.bind) is three words on the
                    // wire: interface name, version, id.
                    @Suppress("UNCHECKED_CAST")
                    val triple = value as? Triple<String, Int, Int>
                        ?: throw WireFormatException("${message.name}.${spec.name} wants Triple(iface, version, id)")
                    writeString(out, triple.first)
                    out.putInt(triple.second)
                    out.putInt(triple.third)
                } else {
                    out.putInt(asInt(value, spec, message))
                }
            }
            ArgType.STRING -> {
                val str = value as String?
                if (str == null) {
                    if (!spec.nullable) {
                        throw WireFormatException("${message.name}.${spec.name} is not nullable")
                    }
                    out.putInt(0)
                } else {
                    writeString(out, str)
                }
            }
            ArgType.ARRAY -> {
                val bytes = (value as? ByteArray) ?: ByteArray(0)
                out.putInt(bytes.size)
                out.put(bytes)
                padTo4(out, bytes.size)
            }
            ArgType.FD -> fds.add(
                value as? FileDescriptor
                    ?: throw WireFormatException("${message.name}.${spec.name} wants a FileDescriptor"),
            )
        }
    }

    private fun asInt(value: Any?, spec: Arg, message: Message): Int = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is Boolean -> if (value) 1 else 0
        null -> if (spec.nullable || spec.type == ArgType.OBJECT) {
            0
        } else {
            throw WireFormatException("${message.name}.${spec.name} is not nullable")
        }
        else -> throw WireFormatException("${message.name}.${spec.name} wants an integer, got $value")
    }

    private fun writeString(out: ByteBuffer, value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        out.putInt(bytes.size + 1)
        out.put(bytes)
        out.put(0)
        padTo4(out, bytes.size + 1)
    }

    private fun padTo4(out: ByteBuffer, written: Int) {
        repeat(pad4(written) - written) { out.put(0) }
    }
}

/** An event rendered to bytes, plus any descriptors that go with it. */
public data class EncodedMessage(val bytes: ByteArray, val fds: List<FileDescriptor>) {
    override fun equals(other: Any?): Boolean =
        other is EncodedMessage && bytes.contentEquals(other.bytes) && fds == other.fds

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + fds.hashCode()
}

/**
 * Decodes requests out of a byte stream.
 *
 * The decoder is deliberately tolerant about *framing* (it returns `null` when
 * a message is only partially buffered, so the caller can read more) and
 * deliberately strict about *content*: an argument that does not fit the spec
 * is a protocol error.
 */
public class MessageDecoder(
    private val lookup: (objectId: Int) -> Interface?,
) {
    /**
     * Attempts to decode one message from [buffer] starting at its current
     * position.
     *
     * On success the position advances past the message. When the buffer holds
     * less than one full message the position is left untouched and `null` is
     * returned. File descriptors are pulled from [fdQueue] in arrival order,
     * which is how `SCM_RIGHTS` associates them with the stream.
     */
    public fun decode(buffer: ByteBuffer, fdQueue: ArrayDeque<FileDescriptor>): WireMessage? {
        if (buffer.remaining() < HEADER_SIZE) return null
        val start = buffer.position()
        val objectId = buffer.getInt(start)
        val sizeAndOpcode = buffer.getInt(start + 4)
        val size = (sizeAndOpcode ushr 16) and 0xFFFF
        val opcode = sizeAndOpcode and 0xFFFF
        if (size < HEADER_SIZE) {
            throw WireFormatException("message for object $objectId declares size $size")
        }
        if (buffer.remaining() < size) return null

        val iface = lookup(objectId)
            ?: throw WireFormatException("request for unknown object $objectId")
        val message = iface.request(opcode)
            ?: throw WireFormatException("${iface.name} has no request with opcode $opcode")

        val body = buffer.duplicate().order(WIRE_ORDER)
        body.position(start + HEADER_SIZE)
        body.limit(start + size)

        val args = ArrayList<Any?>(message.args.size)
        val fds = ArrayList<FileDescriptor>(1)
        for (spec in message.args) {
            args.add(readArg(body, spec, fdQueue, fds, message))
        }
        buffer.position(start + size)
        return WireMessage(objectId, opcode, args, fds)
    }

    private fun readArg(
        body: ByteBuffer,
        spec: Arg,
        fdQueue: ArrayDeque<FileDescriptor>,
        fds: MutableList<FileDescriptor>,
        message: Message,
    ): Any? = when (spec.type) {
        ArgType.INT, ArgType.UINT, ArgType.OBJECT -> requireWord(body, spec, message)
        ArgType.FIXED -> Fixed(requireWord(body, spec, message))
        ArgType.NEW_ID -> if (spec.interfaceName == null) {
            val name = readString(body, spec, message)
                ?: throw WireFormatException("${message.name}.${spec.name} has a null interface name")
            val version = requireWord(body, spec, message)
            val id = requireWord(body, spec, message)
            UntypedNewId(name, version, id)
        } else {
            requireWord(body, spec, message)
        }
        ArgType.STRING -> {
            val value = readString(body, spec, message)
            if (value == null && !spec.nullable) {
                throw WireFormatException("${message.name}.${spec.name} is not nullable")
            }
            value
        }
        ArgType.ARRAY -> {
            val length = requireWord(body, spec, message)
            if (length < 0 || length > body.remaining()) {
                throw WireFormatException("${message.name}.${spec.name} declares $length bytes")
            }
            val bytes = ByteArray(length)
            body.get(bytes)
            skipPadding(body, length)
            bytes
        }
        ArgType.FD -> {
            val fd = fdQueue.removeFirstOrNull()
                ?: throw WireFormatException("${message.name}.${spec.name} expected a descriptor")
            fds.add(fd)
            fd
        }
    }

    private fun requireWord(body: ByteBuffer, spec: Arg, message: Message): Int {
        if (body.remaining() < 4) {
            throw WireFormatException("${message.name} truncated before ${spec.name}")
        }
        return body.int
    }

    private fun readString(body: ByteBuffer, spec: Arg, message: Message): String? {
        val length = requireWord(body, spec, message)
        if (length == 0) return null
        if (length < 0 || length > body.remaining()) {
            throw WireFormatException("${message.name}.${spec.name} declares $length bytes")
        }
        val bytes = ByteArray(length)
        body.get(bytes)
        skipPadding(body, length)
        // The declared length counts the NUL terminator.
        return String(bytes, 0, length - 1, StandardCharsets.UTF_8)
    }

    private fun skipPadding(body: ByteBuffer, written: Int) {
        val padding = pad4(written) - written
        if (padding > 0) {
            body.position(body.position() + minOf(padding, body.remaining()))
        }
    }
}

/**
 * The value decoded for a `new_id` argument that the protocol leaves untyped,
 * i.e. `wl_registry.bind`.
 */
public data class UntypedNewId(val interfaceName: String, val version: Int, val id: Int)
