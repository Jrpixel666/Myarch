package dev.myarch.wayland.wire

import com.google.common.truth.Truth.assertThat
import dev.myarch.wayland.protocol.WlDisplay
import dev.myarch.wayland.protocol.WlRegistry
import dev.myarch.wayland.protocol.WlShm
import dev.myarch.wayland.protocol.WlSurface
import org.junit.Test
import java.io.FileDescriptor
import java.nio.ByteBuffer

/**
 * The wire format is the one place where a one-byte mistake desynchronises the
 * whole session, so it is tested against values computed by hand from the
 * specification rather than against the implementation's own output.
 */
class CodecTest {

    private fun lookup(name: String): (Int) -> Interface? = { Protocolsish[name] }

    private object Protocolsish {
        operator fun get(name: String): Interface = dev.myarch.wayland.protocol.Protocols[name]!!
    }

    @Test
    fun `encodes a header with the size and opcode packed into one word`() {
        val encoded = MessageEncoder.encode(
            objectId = 1,
            message = WlDisplay.SPEC.event("delete_id"),
            args = listOf(7),
        )
        // 8 byte header plus one 32 bit argument.
        assertThat(encoded.bytes).hasLength(12)
        val buffer = ByteBuffer.wrap(encoded.bytes).order(WIRE_ORDER)
        assertThat(buffer.getInt(0)).isEqualTo(1)
        val sizeAndOpcode = buffer.getInt(4)
        assertThat(sizeAndOpcode ushr 16).isEqualTo(12)
        assertThat(sizeAndOpcode and 0xFFFF).isEqualTo(WlDisplay.Event.DELETE_ID)
        assertThat(buffer.getInt(8)).isEqualTo(7)
    }

    @Test
    fun `pads strings to a four byte boundary and counts the terminator`() {
        val encoded = MessageEncoder.encode(
            objectId = 2,
            message = WlRegistry.SPEC.event("global"),
            args = listOf(1, "wl_shm", 1),
        )
        val buffer = ByteBuffer.wrap(encoded.bytes).order(WIRE_ORDER)
        assertThat(buffer.getInt(8)).isEqualTo(1)
        // "wl_shm" is 6 bytes, so the declared length is 7 and the padded
        // payload is 8 bytes.
        assertThat(buffer.getInt(12)).isEqualTo(7)
        assertThat(encoded.bytes.size).isEqualTo(8 + 4 + 4 + 8 + 4)
        assertThat(encoded.bytes[12 + 4 + 6]).isEqualTo(0)
    }

    @Test
    fun `round trips a request with a string argument`() {
        val spec = dev.myarch.wayland.protocol.XdgToplevel.SPEC
        val setTitle = spec.requests.single { it.name == "set_title" }
        val encoded = MessageEncoder.encode(9, setTitle, listOf("hello wayland"))
        val decoder = MessageDecoder { spec }
        val buffer = ByteBuffer.wrap(encoded.bytes).order(WIRE_ORDER)
        val decoded = decoder.decode(buffer, ArrayDeque())
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.objectId).isEqualTo(9)
        assertThat(decoded.opcode).isEqualTo(setTitle.opcode)
        assertThat(decoded.string(0)).isEqualTo("hello wayland")
        assertThat(buffer.remaining()).isEqualTo(0)
    }

    @Test
    fun `returns null until a whole message is buffered`() {
        val spec = WlSurface.SPEC
        val damage = spec.requests.single { it.name == "damage" }
        val encoded = MessageEncoder.encode(5, damage, listOf(1, 2, 3, 4))
        val decoder = MessageDecoder { spec }
        val partial = ByteBuffer.wrap(encoded.bytes.copyOfRange(0, 10)).order(WIRE_ORDER)
        assertThat(decoder.decode(partial, ArrayDeque())).isNull()
        assertThat(partial.position()).isEqualTo(0)
    }

    @Test
    fun `decodes the untyped new_id of wl_registry bind`() {
        val bind = WlRegistry.SPEC.requests.single { it.name == "bind" }
        val body = ByteBuffer.allocate(64).order(WIRE_ORDER)
        body.position(HEADER_SIZE)
        body.putInt(3) // global name
        body.putInt(7) // "wl_shm" + NUL
        body.put("wl_shm".toByteArray())
        body.put(0)
        body.put(ByteArray(1)) // padding to 8 bytes
        body.putInt(1) // version
        body.putInt(42) // new id
        val size = body.position()
        body.putInt(0, 2)
        body.putInt(4, (size shl 16) or bind.opcode)
        body.flip()
        val decoded = MessageDecoder { WlRegistry.SPEC }.decode(body, ArrayDeque())
        assertThat(decoded).isNotNull()
        val newId = decoded!!.args[1] as UntypedNewId
        assertThat(newId.interfaceName).isEqualTo("wl_shm")
        assertThat(newId.version).isEqualTo(1)
        assertThat(newId.id).isEqualTo(42)
    }

    @Test
    fun `pulls descriptors from the ancillary queue in arrival order`() {
        val createPool = WlShm.SPEC.requests.single { it.name == "create_pool" }
        val encoded = MessageEncoder.encode(3, createPool, listOf(4, FileDescriptor.`in`, 4096))
        // The descriptor takes no space on the wire.
        assertThat(encoded.bytes).hasLength(8 + 4 + 4)
        assertThat(encoded.fds).hasSize(1)

        val queue = ArrayDeque(listOf(FileDescriptor.`in`))
        val decoded = MessageDecoder { WlShm.SPEC }
            .decode(ByteBuffer.wrap(encoded.bytes).order(WIRE_ORDER), queue)
        assertThat(decoded!!.args[1]).isSameInstanceAs(FileDescriptor.`in`)
        assertThat(decoded.int(2)).isEqualTo(4096)
        assertThat(queue).isEmpty()
    }

    @Test
    fun `rejects a message that claims an impossible size`() {
        val buffer = ByteBuffer.allocate(16).order(WIRE_ORDER)
        buffer.putInt(1)
        buffer.putInt((4 shl 16) or 0)
        buffer.flip()
        val error = runCatching { MessageDecoder { WlDisplay.SPEC }.decode(buffer, ArrayDeque()) }
        assertThat(error.exceptionOrNull()).isInstanceOf(WireFormatException::class.java)
    }

    @Test
    fun `fixed point values survive a round trip at 1-256 precision`() {
        assertThat(Fixed.of(1.5).raw).isEqualTo(384)
        assertThat(Fixed.of(1.5).toDouble()).isEqualTo(1.5)
        assertThat(Fixed.of(-2.0).raw).isEqualTo(-512)
        assertThat(Fixed.of(7).toInt()).isEqualTo(7)
    }

    @Test
    fun `pad4 rounds up to the next word`() {
        assertThat(pad4(0)).isEqualTo(0)
        assertThat(pad4(1)).isEqualTo(4)
        assertThat(pad4(4)).isEqualTo(4)
        assertThat(pad4(7)).isEqualTo(8)
    }
}
