package dev.myarch.runtime.pacman

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Parsing pacman's real output, and quoting what we pass back to it. */
class PacmanTest {

    @Test
    fun `parses pacman -Ss output including the installed marker`() {
        val output = """
            extra/alacritty 0.13.2-1 [installed]
                A cross-platform, GPU-accelerated terminal emulator
            extra/foot 1.16.2-1
                Wayland terminal emulator
            community/kitty 0.32.2-2 [installed: 0.31.0-1]
                A modern, hackable terminal emulator
        """.trimIndent().lines()

        val packages = Pacman.parseSearchOutput(output)

        assertThat(packages).hasSize(3)
        assertThat(packages[0].repository).isEqualTo("extra")
        assertThat(packages[0].name).isEqualTo("alacritty")
        assertThat(packages[0].version).isEqualTo("0.13.2-1")
        assertThat(packages[0].isInstalled).isTrue()
        assertThat(packages[0].description).startsWith("A cross-platform")
        assertThat(packages[1].isInstalled).isFalse()
        // "[installed: <older version>]" also means installed.
        assertThat(packages[2].isInstalled).isTrue()
    }

    @Test
    fun `tolerates a package with no description line`() {
        val output = listOf("extra/lonely 1.0-1")
        val packages = Pacman.parseSearchOutput(output)
        assertThat(packages).hasSize(1)
        assertThat(packages.single().description).isEmpty()
    }

    @Test
    fun `ignores blank lines and pacman warnings`() {
        val output = listOf("", "extra/foot 1.0-1", "    desc", "")
        assertThat(Pacman.parseSearchOutput(output)).hasSize(1)
    }

    @Test
    fun `parses pacman -Q output`() {
        val output = listOf("bash 5.2.026-2", "coreutils 9.5-1", "error: package 'nope' was not found")
        val installed = Pacman.parseQueryOutput(output)
        assertThat(installed.map { it.name }).containsExactly("bash", "coreutils").inOrder()
        assertThat(installed.all { it.isInstalled }).isTrue()
    }

    @Test
    fun `quotes package names safely for the shell`() {
        // Package names reach a `bash -c` command line, and the search box is
        // user input, so quoting is a correctness requirement not a nicety.
        assertThat(Pacman.shellQuote("foot")).isEqualTo("'foot'")
        assertThat(Pacman.shellQuote("a b")).isEqualTo("'a b'")
        assertThat(Pacman.shellQuote("it's")).isEqualTo("'it'\\''s'")
        assertThat(Pacman.shellQuote("; rm -rf /")).isEqualTo("'; rm -rf /'")
    }

    @Test
    fun `the Omarchy package set is well formed`() {
        assertThat(PackageSets.OMARCHY_CORE).containsNoDuplicates()
        assertThat(PackageSets.OMARCHY_CORE).contains("qt6-declarative")
        // Without a font package every window renders boxes.
        assertThat(PackageSets.OMARCHY_CORE.any { it.contains("ttf") }).isTrue()
        // Quickshell is built from source, so it must not be listed as a package.
        assertThat(PackageSets.OMARCHY_CORE).doesNotContain("quickshell")
        for (name in PackageSets.OMARCHY_CORE + PackageSets.QUICKSHELL_BUILD) {
            assertThat(name).matches("[a-z0-9][a-z0-9._+-]*")
        }
    }

    @Test
    fun `suggested packages all carry an explanation`() {
        for (suggestion in PackageSets.SUGGESTED_EXTRAS) {
            assertThat(suggestion.title).isNotEmpty()
            assertThat(suggestion.description).isNotEmpty()
        }
        assertThat(PackageSets.SUGGESTED_EXTRAS.map { it.name }).containsNoDuplicates()
    }
}
