package dev.myarch.wayland.core

import java.nio.ByteBuffer

/** The `wl_shm` formats Myarch accepts. */
public object ShmFormat {
    /** 32 bit ARGB, little endian: bytes arrive as B, G, R, A. */
    public const val ARGB8888: Int = 0

    /** As [ARGB8888] with the alpha byte ignored. */
    public const val XRGB8888: Int = 1

    public fun isSupported(format: Int): Boolean = format == ARGB8888 || format == XRGB8888

    public fun name(format: Int): String = when (format) {
        ARGB8888 -> "ARGB8888"
        XRGB8888 -> "XRGB8888"
        else -> "0x${format.toString(16)}"
    }
}

/**
 * One frame of client content, converted to the layout
 * `android.graphics.Bitmap.Config.ARGB_8888` expects.
 *
 * The buffer holds `width * height` 32-bit pixels with no row padding, so it
 * can be handed straight to `Bitmap.copyPixelsFromBuffer`.
 */
public class FrameBuffer(
    public val width: Int,
    public val height: Int,
    public val pixels: ByteBuffer,
) {
    public val sizeBytes: Int get() = width * height * BYTES_PER_PIXEL

    public companion object {
        public const val BYTES_PER_PIXEL: Int = 4
    }
}

/**
 * Converts `wl_shm` pixels into the byte order Android's `ARGB_8888` bitmaps
 * use.
 *
 * A Wayland `ARGB8888` buffer is a little-endian 32-bit word `0xAARRGGBB`, so
 * in memory the bytes run B, G, R, A. An Android `ARGB_8888` bitmap stores
 * R, G, B, A. The conversion is therefore a red/blue swap, plus forcing alpha
 * to opaque for the `XRGB8888` variant (whose alpha byte is undefined and, in
 * practice, often zero -- which would make the whole window invisible).
 *
 * Row padding is dropped at the same time: clients are free to use a stride
 * wider than the visible width, which bitmaps cannot express.
 */
public object PixelConverter {

    /**
     * @param source the client's mapped buffer, positioned anywhere; the
     *   absolute [offset] is used so the position is left untouched
     * @param dest a buffer with room for `width * height * 4` bytes
     */
    @Suppress("LongParameterList")
    public fun toAndroidArgb8888(
        source: ByteBuffer,
        offset: Int,
        width: Int,
        height: Int,
        stride: Int,
        format: Int,
        dest: ByteBuffer,
    ) {
        require(ShmFormat.isSupported(format)) { "unsupported format ${ShmFormat.name(format)}" }
        require(width >= 0 && height >= 0) { "negative frame size ${width}x$height" }
        require(stride >= width * FrameBuffer.BYTES_PER_PIXEL) {
            "stride $stride is too small for width $width"
        }
        val opaque = format == ShmFormat.XRGB8888
        var out = dest.position()
        for (y in 0 until height) {
            var inIndex = offset + y * stride
            for (x in 0 until width) {
                val b = source.get(inIndex)
                val g = source.get(inIndex + 1)
                val r = source.get(inIndex + 2)
                val a = source.get(inIndex + 3)
                dest.put(out, r)
                dest.put(out + 1, g)
                dest.put(out + 2, b)
                dest.put(out + 3, if (opaque) OPAQUE else a)
                inIndex += FrameBuffer.BYTES_PER_PIXEL
                out += FrameBuffer.BYTES_PER_PIXEL
            }
        }
        dest.position(out)
    }

    private const val OPAQUE: Byte = -1 // 0xFF
}
