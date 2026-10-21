package dev.myarch.runtime.install

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * The extractor replaces `tar`, which Android does not usefully provide, so it
 * has to handle what Arch's images actually contain -- and refuse what a
 * malicious archive might.
 */
class TarExtractorTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    /** Minimal ustar writer, enough to build the archives under test. */
    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun file(name: String, content: String, mode: Int = 420): TarBuilder =
            entry(name, content.toByteArray(StandardCharsets.UTF_8), '0', mode, "")

        fun directory(name: String, mode: Int = 493): TarBuilder =
            entry(name, ByteArray(0), '5', mode, "")

        fun symlink(name: String, target: String): TarBuilder =
            entry(name, ByteArray(0), '2', 511, target)

        fun hardLink(name: String, target: String): TarBuilder =
            entry(name, ByteArray(0), '1', 420, target)

        fun deviceNode(name: String): TarBuilder =
            entry(name, ByteArray(0), '3', 420, "")

        fun longName(name: String): TarBuilder {
            entry("././@LongLink", (name + "\u0000").toByteArray(), 'L', 420, "")
            return entry(name.take(100), "long".toByteArray(), '0', 420, "")
        }

        private fun entry(
            name: String,
            content: ByteArray,
            typeFlag: Char,
            mode: Int,
            linkName: String,
        ): TarBuilder {
            val header = ByteArray(BLOCK)
            fun put(value: String, offset: Int, length: Int) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length))
            }
            put(name, 0, 100)
            put("%07o".format(mode), 100, 8)
            put("%07o".format(0), 108, 8)
            put("%07o".format(0), 116, 8)
            put("%011o".format(content.size), 124, 12)
            put("%011o".format(0), 136, 12)
            header[156] = typeFlag.code.toByte()
            put(linkName, 157, 100)
            put("ustar", 257, 6)
            put("00", 263, 2)
            // The checksum is computed with the field blank-filled.
            for (index in 148 until 156) header[index] = ' '.code.toByte()
            val checksum = header.sumOf { it.toInt() and 0xFF }
            put("%06o".format(checksum) + "\u0000 ", 148, 8)

            out.write(header)
            out.write(content)
            val padding = (BLOCK - content.size % BLOCK) % BLOCK
            out.write(ByteArray(padding))
            return this
        }

        fun build(): ByteArray {
            out.write(ByteArray(BLOCK * 2)) // end-of-archive marker
            return out.toByteArray()
        }

        private companion object {
            const val BLOCK = 512
        }
    }

    @Test
    fun `extracts files, directories and symlinks`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder()
            .directory("usr/")
            .directory("usr/bin/")
            .file("usr/bin/hello", "#!/bin/sh\necho hi\n", mode = 493)
            .file("etc/hostname", "arch\n")
            .symlink("usr/bin/sh", "hello")
            .build()

        val summary = TarExtractor(destination).extract(ByteArrayInputStream(archive))

        assertThat(summary.files).isEqualTo(2)
        assertThat(summary.directories).isEqualTo(2)
        assertThat(summary.symlinks).isEqualTo(1)
        assertThat(destination.resolve("etc/hostname").readText()).isEqualTo("arch\n")
        val link = destination.resolve("usr/bin/sh").toPath()
        assertThat(Files.isSymbolicLink(link)).isTrue()
        assertThat(Files.readSymbolicLink(link).toString()).isEqualTo("hello")
    }

    @Test
    fun `preserves the executable bit`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder().file("bin/run", "x", mode = 493).build()
        TarExtractor(destination).extract(ByteArrayInputStream(archive))
        assertThat(destination.resolve("bin/run").canExecute()).isTrue()
    }

    @Test
    fun `refuses paths that escape the destination`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder()
            .file("../escaped", "nope")
            .file("/absolute", "also nope")
            .file("ok", "yes")
            .build()

        val summary = TarExtractor(destination).extract(ByteArrayInputStream(archive))

        assertThat(destination.parentFile.resolve("escaped").exists()).isFalse()
        // An absolute path is written relative to the root, not at /.
        assertThat(destination.resolve("absolute").exists()).isTrue()
        assertThat(destination.resolve("ok").readText()).isEqualTo("yes")
        assertThat(summary.skipped).isEqualTo(1)
    }

    @Test
    fun `skips device nodes instead of failing`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder()
            .deviceNode("dev/null")
            .file("etc/fstab", "")
            .build()
        val summary = TarExtractor(destination).extract(ByteArrayInputStream(archive))
        assertThat(summary.skipped).isEqualTo(1)
        assertThat(summary.files).isEqualTo(1)
    }

    @Test
    fun `follows GNU long name headers`() {
        val destination = folder.newFolder("rootfs")
        val longName = "usr/share/" + "deeply/".repeat(20) + "file.txt"
        val archive = TarBuilder().longName(longName).build()
        TarExtractor(destination).extract(ByteArrayInputStream(archive))
        assertThat(destination.resolve(longName).exists()).isTrue()
    }

    @Test
    fun `creates hard links as links or copies`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder()
            .file("usr/bin/busybox", "binary")
            .hardLink("usr/bin/ls", "usr/bin/busybox")
            .build()
        val summary = TarExtractor(destination).extract(ByteArrayInputStream(archive))
        assertThat(summary.hardLinks).isEqualTo(1)
        val link = destination.resolve("usr/bin/ls")
        assertThat(link.exists()).isTrue()
        assertThat(link.readText()).isEqualTo("binary")
    }

    @Test
    fun `reports progress for each file`() {
        val destination = folder.newFolder("rootfs")
        val seen = ArrayList<String>()
        val archive = TarBuilder().file("a", "1").file("b", "22").build()
        val summary = TarExtractor(destination) { name, _ -> seen.add(name) }
            .extract(ByteArrayInputStream(archive))
        assertThat(seen).containsExactly("a", "b").inOrder()
        assertThat(summary.totalBytes).isEqualTo(3)
    }

    @Test
    fun `overwrites a path that appears twice`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder().file("etc/conf", "first").file("etc/conf", "second").build()
        TarExtractor(destination).extract(ByteArrayInputStream(archive))
        assertThat(destination.resolve("etc/conf").readText()).isEqualTo("second")
    }

    @Test
    fun `mode zero still yields a readable file`() {
        val destination = folder.newFolder("rootfs")
        val archive = TarBuilder().file("etc/odd", "x", mode = 0).build()
        TarExtractor(destination).extract(ByteArrayInputStream(archive))
        val path = destination.resolve("etc/odd").toPath()
        assertThat(Files.isReadable(path)).isTrue()
        assertThat(Files.exists(path, LinkOption.NOFOLLOW_LINKS)).isTrue()
    }
}
