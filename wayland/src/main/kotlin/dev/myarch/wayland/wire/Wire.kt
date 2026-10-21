package dev.myarch.wayland.wire

import java.io.FileDescriptor

/**
 * The argument types the Wayland wire format defines.
 *
 * Everything except [FD] is carried inline in the message body; file
 * descriptors travel out of band as `SCM_RIGHTS` ancillary data, which is why
 * they occupy no space in the 32-bit word stream.
 */
public enum class ArgType {
    INT,
    UINT,
    FIXED,
    STRING,
    OBJECT,
    NEW_ID,
    ARRAY,
    FD,
}

/** A single argument of a request or event. */
public data class Arg(
    val name: String,
    val type: ArgType,
    val interfaceName: String?,
    val nullable: Boolean,
)

/** A request or an event: its name, its opcode and its argument layout. */
public data class Message(
    val name: String,
    val opcode: Int,
    val since: Int,
    val destructor: Boolean,
    val args: List<Arg>,
) {
    /** `true` when the message carries at least one file descriptor. */
    public val hasFds: Boolean = args.any { it.type == ArgType.FD }
}

/** A protocol interface: a versioned set of requests and events. */
public data class Interface(
    val name: String,
    val version: Int,
    val requests: List<Message>,
    val events: List<Message>,
) {
    private val requestsByOpcode = requests.associateBy { it.opcode }
    private val eventsByName = events.associateBy { it.name }

    public fun request(opcode: Int): Message? = requestsByOpcode[opcode]

    public fun event(name: String): Message =
        eventsByName[name] ?: error("$name is not an event of ${this.name}")
}

/**
 * A 24.8 signed fixed point number, the unit Wayland uses for surface-local
 * coordinates.
 */
@JvmInline
public value class Fixed(public val raw: Int) {
    public fun toDouble(): Double = raw / 256.0

    public fun toInt(): Int = raw shr 8

    override fun toString(): String = "Fixed(${toDouble()})"

    public companion object {
        public fun of(value: Double): Fixed = Fixed(Math.round(value * 256.0).toInt())

        public fun of(value: Int): Fixed = Fixed(value shl 8)
    }
}

/** A decoded request, before it is dispatched to a resource. */
public data class WireMessage(
    val objectId: Int,
    val opcode: Int,
    val args: List<Any?>,
    val fds: List<FileDescriptor>,
) {
    public fun int(index: Int): Int = args[index] as Int

    public fun uint(index: Int): Int = args[index] as Int

    public fun fixed(index: Int): Fixed = args[index] as Fixed

    public fun string(index: Int): String = args[index] as String

    public fun stringOrNull(index: Int): String? = args[index] as String?

    public fun objectId(index: Int): Int = args[index] as Int

    public fun newId(index: Int): Int = args[index] as Int

    public fun array(index: Int): ByteArray = args[index] as ByteArray

    public fun fd(index: Int): FileDescriptor = args[index] as FileDescriptor
}

/** Header size in bytes: object id plus the packed size/opcode word. */
public const val HEADER_SIZE: Int = 8

/** The largest message Wayland can express, since the size field is 16 bits. */
public const val MAX_MESSAGE_SIZE: Int = 0xFFFF

/** Rounds [value] up to the next multiple of four, the wire format's padding. */
public fun pad4(value: Int): Int = (value + 3) and 3.inv()
