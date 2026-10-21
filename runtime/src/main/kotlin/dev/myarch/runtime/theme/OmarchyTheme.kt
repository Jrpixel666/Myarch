package dev.myarch.runtime.theme

/**
 * A colour palette, in the shape Omarchy themes use.
 *
 * Omarchy keeps one palette and renders it into each tool's own config format.
 * Myarch does the same, with one addition: the launcher's own Compose UI reads
 * this palette too, so the Android side and the Linux side cannot drift apart.
 */
public data class Palette(
    val background: String,
    val surface: String,
    val foreground: String,
    val muted: String,
    val accent: String,
    val accentAlt: String,
    val warning: String,
    val error: String,
    val success: String,
    /** The 16 ANSI colours, normal 0-7 then bright 8-15. */
    val ansi: List<String>,
) {
    init {
        require(ansi.size == ANSI_COUNT) { "a palette needs $ANSI_COUNT ANSI colours, got ${ansi.size}" }
    }

    public companion object {
        public const val ANSI_COUNT: Int = 16
    }
}

/** One installed theme. */
public data class OmarchyTheme(
    val id: String,
    val name: String,
    val isLight: Boolean,
    val palette: Palette,
    /** Wallpaper file name inside the theme's `backgrounds/` directory. */
    val wallpaper: String? = null,
    /** Where the theme came from, shown in the theme picker. */
    val origin: Origin = Origin.BUILT_IN,
) {
    public enum class Origin { BUILT_IN, IMPORTED, USER }
}

/** Parses `#rrggbb` into the 0xAARRGGBB integer Android wants. */
public fun parseHexColor(value: String): Int {
    val hex = value.trim().removePrefix("#").removePrefix("0x")
    require(hex.length == RGB_LENGTH || hex.length == RGBA_LENGTH) { "not a colour: $value" }
    val rgb = hex.substring(0, RGB_LENGTH).toLong(HEX_RADIX).toInt()
    val alpha = if (hex.length == RGBA_LENGTH) {
        hex.substring(RGB_LENGTH).toInt(HEX_RADIX)
    } else {
        OPAQUE_ALPHA
    }
    return (alpha shl ALPHA_SHIFT) or rgb
}

private const val RGB_LENGTH = 6
private const val RGBA_LENGTH = 8
private const val HEX_RADIX = 16
private const val OPAQUE_ALPHA = 0xFF
private const val ALPHA_SHIFT = 24
