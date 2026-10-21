package dev.myarch.runtime.theme

import java.io.File

/**
 * Imports a theme directory laid out the way Omarchy's themes are.
 *
 * Omarchy's themes are per-tool config files rather than a single palette, so
 * there is no one file to read. The Alacritty colours are the most reliable
 * source -- every Omarchy theme has them and the format is stable -- with the
 * rest of the palette derived from the ANSI colours when the theme does not
 * state it.
 */
public object ThemeImporter {

    /** What went wrong, or the theme that was read. */
    public sealed interface Result {
        public data class Imported(val theme: OmarchyTheme) : Result

        public data class Failed(val reason: String) : Result
    }

    public fun import(directory: File): Result {
        if (!directory.isDirectory) {
            return Result.Failed("${directory.name} is not a directory")
        }
        val alacritty = listOf("alacritty.toml", "colors.toml", "alacritty.yml")
            .map { File(directory, it) }
            .firstOrNull { it.isFile }
            ?: return Result.Failed(
                "No alacritty.toml in ${directory.name}. " +
                    "Myarch reads the palette from that file; add one or build the theme in the app.",
            )

        val colors = AlacrittyColors.parse(alacritty.readText())
        val background = colors["primary.background"]
            ?: return Result.Failed("alacritty.toml has no [colors.primary] background")
        val foreground = colors["primary.foreground"] ?: "#ffffff"
        val ansi = (0 until Palette.ANSI_COUNT).map { index ->
            val group = if (index < ANSI_BRIGHT_OFFSET) "normal" else "bright"
            val name = ANSI_NAMES[index % ANSI_BRIGHT_OFFSET]
            colors["$group.$name"] ?: fallbackAnsi(index, background, foreground)
        }

        val wallpaper = File(directory, "backgrounds").listFiles()
            ?.firstOrNull { it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.name

        return Result.Imported(
            OmarchyTheme(
                id = directory.name.lowercase().replace(Regex("[^a-z0-9-]"), "-"),
                name = directory.name.replace('-', ' ').replaceFirstChar { it.uppercase() },
                isLight = File(directory, "light.mode").exists() || isLight(background),
                palette = Palette(
                    background = background,
                    surface = colors["primary.dim_background"] ?: lighten(background),
                    foreground = foreground,
                    muted = ansi[ANSI_BRIGHT_BLACK],
                    accent = colors["primary.accent"] ?: ansi[ANSI_BLUE],
                    accentAlt = ansi[ANSI_MAGENTA],
                    warning = ansi[ANSI_YELLOW],
                    error = ansi[ANSI_RED],
                    success = ansi[ANSI_GREEN],
                    ansi = ansi,
                ),
                wallpaper = wallpaper,
                origin = OmarchyTheme.Origin.IMPORTED,
            ),
        )
    }

    /** A rough brightness test, used when a theme has no `light.mode` marker. */
    public fun isLight(background: String): Boolean {
        val color = parseHexColor(background)
        val red = (color shr RED_SHIFT) and BYTE_MASK
        val green = (color shr GREEN_SHIFT) and BYTE_MASK
        val blue = color and BYTE_MASK
        // Rec. 601 luma, which tracks perceived brightness closely enough here.
        val luma = (RED_WEIGHT * red + GREEN_WEIGHT * green + BLUE_WEIGHT * blue) / WEIGHT_TOTAL
        return luma > LIGHT_THRESHOLD
    }

    /** Nudges a colour towards mid grey, for deriving a surface colour. */
    public fun lighten(hex: String, amount: Float = 0.08f): String {
        val color = parseHexColor(hex)
        fun channel(shift: Int): Int {
            val value = (color shr shift) and BYTE_MASK
            val target = if (isLight(hex)) 0 else BYTE_MASK
            return (value + ((target - value) * amount)).toInt().coerceIn(0, BYTE_MASK)
        }
        return "#%02x%02x%02x".format(channel(RED_SHIFT), channel(GREEN_SHIFT), channel(BLUE_SHIFT))
    }

    private fun fallbackAnsi(index: Int, background: String, foreground: String): String =
        if (index % ANSI_BRIGHT_OFFSET == 0) background else foreground

    private val ANSI_NAMES = listOf("black", "red", "green", "yellow", "blue", "magenta", "cyan", "white")
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    private const val ANSI_BRIGHT_OFFSET = 8
    private const val ANSI_RED = 1
    private const val ANSI_GREEN = 2
    private const val ANSI_YELLOW = 3
    private const val ANSI_BLUE = 4
    private const val ANSI_MAGENTA = 5
    private const val ANSI_BRIGHT_BLACK = 8

    private const val BYTE_MASK = 0xFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
    private const val BLUE_SHIFT = 0
    private const val RED_WEIGHT = 299
    private const val GREEN_WEIGHT = 587
    private const val BLUE_WEIGHT = 114
    private const val WEIGHT_TOTAL = 1000
    private const val LIGHT_THRESHOLD = 128
}

/**
 * A very small TOML reader for Alacritty colour files.
 *
 * Pulling in a TOML library to read eight key/value pairs out of a file with a
 * fixed shape would be a poor trade. This handles the subset Alacritty themes
 * use -- `[colors.normal]` tables with quoted hex values, and the YAML-era
 * two-space indentation -- and ignores everything else.
 */
public object AlacrittyColors {

    /** Returns a map like `"normal.red" -> "#ff0000"`. */
    public fun parse(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var table = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            // Only a leading `#` starts a comment. Stripping from any `#` would
            // also eat the colours, which are written `"#ff0000"`.
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                table = line.trim('[', ']').removePrefix("colors.").removePrefix("colors")
                continue
            }
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim().trim('"', '\'')
            val value = readValue(line.substring(equals + 1).trim()) ?: continue
            if (!value.startsWith("#") && !value.startsWith("0x")) continue
            val normalised = if (value.startsWith("0x")) "#" + value.removePrefix("0x") else value
            result[if (table.isEmpty()) key else "$table.$key"] = normalised
        }
        return result
    }

    /**
     * Reads one TOML scalar, discarding a trailing comment.
     *
     * A quoted value ends at its closing quote; an unquoted one ends at the
     * first whitespace or comma. Either way whatever follows is not part of it.
     */
    private fun readValue(raw: String): String? {
        if (raw.isEmpty()) return null
        val quote = raw.first()
        if (quote == '"' || quote == '\'') {
            val end = raw.indexOf(quote, startIndex = 1)
            return if (end > 0) raw.substring(1, end) else null
        }
        return raw.takeWhile { !it.isWhitespace() && it != ',' }.ifEmpty { null }
    }
}
