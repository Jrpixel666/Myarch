package dev.myarch.runtime.apps

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Reading the container's `.desktop` files, which is how the drawer is built. */
class DesktopEntriesTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    @Test
    fun `parses the fields a launcher needs`() {
        val entry = DesktopEntries.parse(
            """
            [Desktop Entry]
            Type=Application
            Name=Foot
            Name[de]=Fuss
            GenericName=Terminal
            Exec=foot %U
            Icon=foot
            Terminal=false
            Categories=System;TerminalEmulator;
            """.trimIndent(),
            fallbackId = "foot",
        )
        assertThat(entry).isNotNull()
        assertThat(entry!!.name).isEqualTo("Foot")
        assertThat(entry.comment).isEqualTo("Terminal")
        assertThat(entry.exec).isEqualTo("foot")
        assertThat(entry.icon).isEqualTo("foot")
        assertThat(entry.categories).containsExactly("System", "TerminalEmulator").inOrder()
        assertThat(entry.primaryCategory).isEqualTo("System")
        assertThat(entry.terminal).isFalse()
    }

    @Test
    fun `strips field codes so apps do not open a file called percent U`() {
        assertThat(DesktopEntries.stripFieldCodes("gimp %U")).isEqualTo("gimp")
        assertThat(DesktopEntries.stripFieldCodes("env FOO=1 app %f --flag")).isEqualTo("env FOO=1 app --flag")
        assertThat(DesktopEntries.stripFieldCodes("app")).isEqualTo("app")
    }

    @Test
    fun `ignores entries that are not applications`() {
        val link = DesktopEntries.parse("[Desktop Entry]\nType=Link\nName=Web\nURL=https://example\n", "web")
        assertThat(link).isNull()
    }

    @Test
    fun `reads only the Desktop Entry group`() {
        val entry = DesktopEntries.parse(
            """
            [Desktop Entry]
            Type=Application
            Name=Editor
            Exec=editor

            [Desktop Action new-window]
            Name=New Window
            Exec=editor --new-window
            """.trimIndent(),
            "editor",
        )
        assertThat(entry!!.exec).isEqualTo("editor")
    }

    @Test
    fun `scans the standard directories with user entries winning`() {
        val rootfs = folder.newFolder("rootfs")
        File(rootfs, "usr/share/applications").mkdirs()
        File(rootfs, "root/.local/share/applications").mkdirs()
        File(rootfs, "usr/share/applications/btop.desktop")
            .writeText("[Desktop Entry]\nType=Application\nName=btop\nExec=btop\n")
        File(rootfs, "usr/share/applications/hidden.desktop")
            .writeText("[Desktop Entry]\nType=Application\nName=Hidden\nExec=x\nNoDisplay=true\n")
        // Same id in a higher-priority directory, with a different name.
        File(rootfs, "root/.local/share/applications/btop.desktop")
            .writeText("[Desktop Entry]\nType=Application\nName=btop (mine)\nExec=btop --utf-force\n")

        val entries = DesktopEntries.scan(rootfs)

        assertThat(entries.map { it.name }).containsExactly("btop (mine)")
        assertThat(entries.single().exec).isEqualTo("btop --utf-force")

        val withHidden = DesktopEntries.scan(rootfs, includeHidden = true)
        assertThat(withHidden.map { it.name }).contains("Hidden")
    }

    @Test
    fun `a missing rootfs yields an empty list rather than an error`() {
        assertThat(DesktopEntries.scan(File(folder.root, "nope"))).isEmpty()
    }
}
