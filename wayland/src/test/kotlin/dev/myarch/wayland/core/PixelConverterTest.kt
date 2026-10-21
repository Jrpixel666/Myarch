package dev.myarch.wayland.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The red/blue swap is the kind of bug that produces a working app with
 * wrong-looking windows, so it is pinned down by value here.
 */
class PixelConverterTest {

    private fun source(vararg bytes: Int): ByteBuffer =
        ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (byte in bytes) put(byte.toByte())
            position(0)
        }

    @Test
    fun `swaps red and blue into Android's byte order`() {
        // wl_shm ARGB8888 in memory: B, G, R, A.
        val input = source(0x11, 0x22, 0x33, 0x44)
        val output = ByteBuffer.allocate(4)
        PixelConverter.toAndroidArgb8888(input, 0, 1, 1, 4, ShmFormat.ARGB8888, output)
        output.flip()
        // Android ARGB_8888 in memory: R, G, B, A.
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0x33)
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0x22)
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0x11)
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0x44)
    }

    @Test
    fun `forces alpha opaque for XRGB8888`() {
        // An XRGB buffer's alpha byte is undefined; clients often leave it 0,
        // which would make the whole window invisible if passed through.
        val input = source(0x11, 0x22, 0x33, 0x00)
        val output = ByteBuffer.allocate(4)
        PixelConverter.toAndroidArgb8888(input, 0, 1, 1, 4, ShmFormat.XRGB8888, output)
        output.flip()
        output.position(3)
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0xFF)
    }

    @Test
    fun `drops row padding when the stride is wider than the image`() {
        // Two rows of one pixel, with a stride of 8 bytes: the second four
        // bytes of each row are padding the client never drew into.
        val input = source(
            0x01, 0x02, 0x03, 0xFF, 0xDE, 0xAD, 0xBE, 0xEF,
            0x04, 0x05, 0x06, 0xFF, 0xDE, 0xAD, 0xBE, 0xEF,
        )
        val output = ByteBuffer.allocate(8)
        PixelConverter.toAndroidArgb8888(input, 0, 1, 2, 8, ShmFormat.ARGB8888, output)
        output.flip()
        val bytes = ByteArray(8)
        output.get(bytes)
        assertThat(bytes.map { it.toInt() and 0xFF }).containsExactly(
            0x03, 0x02, 0x01, 0xFF,
            0x06, 0x05, 0x04, 0xFF,
        ).inOrder()
    }

    @Test
    fun `honours the buffer offset so several buffers can share a pool`() {
        val input = source(0, 0, 0, 0, 0x11, 0x22, 0x33, 0xFF)
        val output = ByteBuffer.allocate(4)
        PixelConverter.toAndroidArgb8888(input, 4, 1, 1, 4, ShmFormat.ARGB8888, output)
        output.flip()
        assertThat(output.get().toInt() and 0xFF).isEqualTo(0x33)
    }

    @Test
    fun `rejects a stride that cannot hold the declared width`() {
        val input = source(0, 0, 0, 0)
        val output = ByteBuffer.allocate(16)
        val error = runCatching {
            PixelConverter.toAndroidArgb8888(input, 0, 4, 1, 4, ShmFormat.ARGB8888, output)
        }
        assertThat(error.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects formats we never advertised`() {
        val input = source(0, 0, 0, 0)
        val output = ByteBuffer.allocate(4)
        val error = runCatching {
            PixelConverter.toAndroidArgb8888(input, 0, 1, 1, 4, format = 0x34325258, dest = output)
        }
        assertThat(error.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }
}
