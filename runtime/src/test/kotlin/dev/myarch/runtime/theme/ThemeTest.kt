package dev.myarch.runtime.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Palettes, the files generated from them, and importing an Omarchy theme. */
class ThemeTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `every bundled theme has a complete palette`() {
        assertThat(BuiltInThemes.all).isNotEmpty()
        assertThat(BuiltInThemes.all.map { it.id }).containsNoDuplicates()
        for (theme in BuiltInThemes.all) {
            assertThat(theme.palette.ansi).hasSize(16)
            for (hex in theme.palette.ansi + listOf(theme.palette.background, theme.palette.accent)) {
                assertThat(hex).matches("#[0-9a-fA-F]{6}")
            }
        }
    }

    @Test
    fun `hex colours parse to opaque ARGB`() {
        assertThat(parseHexColor("#1a1b26")).isEqualTo(0xFF1A1B26.toInt())
        assertThat(parseHexColor("1a1b26")).isEqualTo(0xFF1A1B26.toInt())
        assertThat(parseHexColor("#ffffff80")).isEqualTo(0x80FFFFFF.toInt())
    }

    @Test
    fun `an unparseable colour is rejected rather than silently black`() {
        assertThat(runCatching { parseHexColor("blue") }.isFailure).isTrue()
    }

    @Test
    fun `applying a theme writes every tool's config`() {
        val rootfs = folder.newFolder("rootfs")
        val written = ThemeWriter.apply(BuiltInThemes.gruvbox, rootfs)

        val paths = written.map { it.relativeTo(rootfs).path }
        assertThat(paths).containsAtLeast(
            "root/.config/alacritty/colors.toml",
            "root/.config/foot/colors.ini",
            "root/.config/quickshell/myarch/Theme.qml",
            "root/.config/myarch/theme.json",
        )
        assertThat(File(rootfs, "root/.config/myarch/current-theme").readText().trim())
            .isEqualTo("gruvbox")
    }

    @Test
    fun `the Quickshell singleton is valid QML with the palette in it`() {
        val qml = ThemeWriter.quickshellTheme(BuiltInThemes.nord)
        assertThat(qml).contains("pragma Singleton")
        assertThat(qml).contains("import QtQuick")
        assertThat(qml).contains("readonly property color background: \"#2e3440\"")
        // Widgets rely on these shared metrics existing.
        assertThat(qml).contains("readonly property int radius")
        assertThat(qml).contains("readonly property int barHeight")
        assertThat(qml.count { it == '{' }).isEqualTo(qml.count { it == '}' })
    }

    @Test
    fun `foot wants colours without the leading hash`() {
        val ini = ThemeWriter.foot(BuiltInThemes.tokyoNight)
        assertThat(ini).contains("background=1a1b26")
        assertThat(ini).doesNotContain("=#")
    }

    @Test
    fun `alacritty gets normal and bright blocks`() {
        val toml = ThemeWriter.alacritty(BuiltInThemes.tokyoNight)
        assertThat(toml).contains("[colors.primary]")
        assertThat(toml).contains("[colors.normal]")
        assertThat(toml).contains("[colors.bright]")
        // Bright colours come from the second half of the ANSI list.
        assertThat(toml).contains("black = \"#414868\"")
    }

    @Test
    fun `the json form is machine readable and complete`() {
        val json = ThemeWriter.json(BuiltInThemes.catppuccinMocha)
        assertThat(json).contains("\"id\": \"catppuccin\"")
        assertThat(json).contains("\"light\": false")
        assertThat(json).contains("\"accent\": \"#89b4fa\"")
        assertThat(json.count { it == '{' }).isEqualTo(json.count { it == '}' })
    }

    @Test
    fun `imports an Omarchy theme directory from its alacritty colours`() {
        val directory = folder.newFolder("my-theme")
        File(directory, "alacritty.toml").writeText(
            """
            [colors.primary]
            background = "#0f0f17"
            foreground = "#e0e0e0"

            [colors.normal]
            black = "#1a1a1a"
            red = "#ff5555"
            green = "#50fa7b"
            yellow = "#f1fa8c"
            blue = "#6272a4"
            magenta = "#ff79c6"
            cyan = "#8be9fd"
            white = "#bfbfbf"

            [colors.bright]
            black = "#4d4d4d"
            red = "#ff6e67"
            green = "#5af78e"
            yellow = "#f4f99d"
            blue = "#caa9fa"
            magenta = "#ff92d0"
            cyan = "#9aedfe"
            white = "#e6e6e6"
            """.trimIndent(),
        )
        File(directory, "backgrounds").mkdirs()
        File(directory, "backgrounds/wall.png").writeText("not really a png")

        val result = ThemeImporter.import(directory)

        assertThat(result).isInstanceOf(ThemeImporter.Result.Imported::class.java)
        val theme = (result as ThemeImporter.Result.Imported).theme
        assertThat(theme.id).isEqualTo("my-theme")
        assertThat(theme.palette.background).isEqualTo("#0f0f17")
        assertThat(theme.palette.error).isEqualTo("#ff5555")
        assertThat(theme.palette.accent).isEqualTo("#6272a4")
        assertThat(theme.palette.ansi).hasSize(16)
        assertThat(theme.palette.ansi[8]).isEqualTo("#4d4d4d")
        assertThat(theme.isLight).isFalse()
        assertThat(theme.wallpaper).isEqualTo("wall.png")
        assertThat(theme.origin).isEqualTo(OmarchyTheme.Origin.IMPORTED)
    }

    @Test
    fun `importing says why it failed instead of guessing`() {
        val empty = folder.newFolder("empty-theme")
        val result = ThemeImporter.import(empty)
        assertThat(result).isInstanceOf(ThemeImporter.Result.Failed::class.java)
        assertThat((result as ThemeImporter.Result.Failed).reason).contains("alacritty.toml")
    }

    @Test
    fun `light mode is detected from the background when unmarked`() {
        assertThat(ThemeImporter.isLight("#eff1f5")).isTrue()
        assertThat(ThemeImporter.isLight("#1a1b26")).isFalse()
    }

    @Test
    fun `the surface colour derived from a background stays a valid colour`() {
        val lightened = ThemeImporter.lighten("#1a1b26")
        assertThat(lightened).matches("#[0-9a-f]{6}")
        assertThat(lightened).isNotEqualTo("#1a1b26")
    }

    @Test
    fun `the alacritty reader handles tables, quotes and 0x colours`() {
        val values = AlacrittyColors.parse(
            """
            # a comment
            [colors.primary]
            background = '#112233'
            foreground = 0x445566

            [colors.normal]
            red = "#ff0000"  # trailing comment
            not_a_colour = "hello"
            """.trimIndent(),
        )
        assertThat(values["primary.background"]).isEqualTo("#112233")
        assertThat(values["primary.foreground"]).isEqualTo("#445566")
        assertThat(values["normal.red"]).isEqualTo("#ff0000")
        assertThat(values).doesNotContainKey("normal.not_a_colour")
    }
}
