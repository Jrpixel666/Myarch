package dev.myarch.runtime.install

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/**
 * Extracts a rootfs tarball without needing a `tar` binary or root.
 *
 * Android gives us no usable `tar` (the toybox one is missing on older
 * releases and cannot preserve what we need), and the extraction has to happen
 * before any guest binary can run -- so bootstrapping it with a container tool
 * would be circular. This reader handles what Arch's images actually contain:
 * ustar and GNU tar entries, long names, symlinks, hard links and permissions.
 *
 * Two kinds of entry are deliberately skipped:
 *
 * - **Device nodes and FIFOs.** `mknod` requires privileges we do not have.
 *   proot synthesises `/dev` entries itself, so nothing is lost.
 * - **Ownership.** `chown` likewise needs root. proot's `-0` option makes the
 *   guest believe every file is owned by root, which is what the rootfs
 *   expects.
 */
public class TarExtractor(
    private val destination: File,
    /** Reports progress; called with the entry just written. */
    private val onEntry: (name: String, bytes: Long) -> Unit = { _, _ -> },
) {

    /** Counters for the install log, so a short extraction is noticed. */
    public data class Summary(
        val files: Int,
        val directories: Int,
        val symlinks: Int,
        val hardLinks: Int,
        val skipped: Int,
        val totalBytes: Long,
    )

    private val deferredSymlinks = ArrayList<Pair<Path, String>>()
    private val deferredHardLinks = ArrayList<Pair<Path, Path>>()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    public fun extract(input: InputStream): Summary {
        var files = 0
        var directories = 0
        var symlinks = 0
        var hardLinks = 0
        var skipped = 0
        var totalBytes = 0L

        val root = destination.toPath().toAbsolutePath().normalize()
        Files.createDirectories(root)

        val header = ByteArray(BLOCK_SIZE)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var emptyBlocks = 0

        while (true) {
            if (!input.readFully(header)) break
            if (header.all { it == 0.toByte() }) {
                // Two consecutive zero blocks terminate the archive.
                if (++emptyBlocks >= 2) break
                continue
            }
            emptyBlocks = 0

            val entry = TarHeader.parse(header)
            val name = pendingLongName ?: entry.name
            val linkTarget = pendingLongLink ?: entry.linkName
            pendingLongName = null
            pendingLongLink = null

            when (entry.typeFlag) {
                TYPE_GNU_LONG_NAME -> {
                    pendingLongName = input.readString(entry.size)
                    input.skipPadding(entry.size)
                    continue
                }
                TYPE_GNU_LONG_LINK -> {
                    pendingLongLink = input.readString(entry.size)
                    input.skipPadding(entry.size)
                    continue
                }
                TYPE_PAX_NEXT, TYPE_PAX_GLOBAL -> {
                    val attributes = PaxHeader.parse(input.readString(entry.size))
                    input.skipPadding(entry.size)
                    pendingLongName = attributes["path"]
                    pendingLongLink = attributes["linkpath"]
                    continue
                }
            }

            val target = resolveSafely(root, name)
            if (target == null) {
                // A path that escapes the destination is either a broken
                // archive or an attack; either way we do not write it. The
                // padding must still be consumed: skipping it would leave the
                // stream mid-block and every later header would be garbage.
                input.skipEntry(entry.size)
                input.skipPadding(entry.size)
                skipped++
                continue
            }

            when (entry.typeFlag) {
                TYPE_DIRECTORY -> {
                    Files.createDirectories(target)
                    applyMode(target, entry.mode, isDirectory = true)
                    directories++
                }
                TYPE_REGULAR, TYPE_REGULAR_ALT, TYPE_CONTIGUOUS -> {
                    Files.createDirectories(target.parent)
                    // A tarball may legitimately replace a path extracted
                    // earlier (layered images do this), so truncate rather
                    // than fail.
                    Files.deleteIfExists(target)
                    Files.newOutputStream(target).use { out ->
                        input.copyExactly(entry.size, out)
                    }
                    applyMode(target, entry.mode, isDirectory = false)
                    files++
                    totalBytes += entry.size
                    onEntry(name, entry.size)
                }
                TYPE_SYMLINK -> {
                    Files.createDirectories(target.parent)
                    // Symlinks are created last: a link may point at a path
                    // that has not been extracted yet, and on some filesystems
                    // creating it over an existing entry fails.
                    deferredSymlinks.add(target to linkTarget)
                    symlinks++
                }
                TYPE_HARD_LINK -> {
                    val source = resolveSafely(root, linkTarget)
                    if (source == null) {
                        skipped++
                    } else {
                        deferredHardLinks.add(target to source)
                        hardLinks++
                    }
                }
                else -> {
                    // Character/block devices, FIFOs and sockets: see the class
                    // documentation for why these are dropped.
                    input.skipEntry(entry.size)
                    input.skipPadding(entry.size)
                    skipped++
                    continue
                }
            }
            input.skipPadding(entry.size)
        }

        finishLinks()
        return Summary(files, directories, symlinks, hardLinks, skipped, totalBytes)
    }

    private fun finishLinks() {
        for ((link, targetPath) in deferredSymlinks) {
            runCatching {
                Files.deleteIfExists(link)
                Files.createSymbolicLink(link, File(targetPath).toPath())
            }
        }
        for ((link, source) in deferredHardLinks) {
            runCatching {
                Files.deleteIfExists(link)
                if (Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createLink(link, source)
                }
            }.onFailure {
                // Some filesystems refuse hard links; a copy preserves
                // behaviour closely enough for a rootfs.
                runCatching { Files.copy(source, link) }
            }
        }
    }

    /**
     * Resolves [name] inside [root], or returns `null` if it would escape.
     *
     * This is the classic "zip slip" check: `../` components and absolute
     * paths in an archive must never be followed.
     */
    private fun resolveSafely(root: Path, name: String): Path? {
        if (name.isEmpty()) return null
        val cleaned = name.removePrefix("./").trimStart('/')
        if (cleaned.isEmpty()) return null
        val resolved = root.resolve(cleaned).normalize()
        return if (resolved.startsWith(root)) resolved else null
    }

    private fun applyMode(path: Path, mode: Int, isDirectory: Boolean) {
        runCatching {
            Files.setPosixFilePermissions(path, permissionsOf(mode, isDirectory))
        }
    }

    public companion object {
        private const val BLOCK_SIZE = 512

        private const val TYPE_REGULAR = '0'
        private const val TYPE_REGULAR_ALT = '\u0000'
        private const val TYPE_HARD_LINK = '1'
        private const val TYPE_SYMLINK = '2'
        private const val TYPE_DIRECTORY = '5'
        private const val TYPE_CONTIGUOUS = '7'
        private const val TYPE_GNU_LONG_LINK = 'K'
        private const val TYPE_GNU_LONG_NAME = 'L'
        private const val TYPE_PAX_NEXT = 'x'
        private const val TYPE_PAX_GLOBAL = 'g'

        /** Converts a POSIX mode to the permission set Java understands. */
        public fun permissionsOf(mode: Int, isDirectory: Boolean): Set<PosixFilePermission> {
            val effective = if (mode == 0) {
                // Some archives store mode 0; a sane default beats an
                // unreadable rootfs.
                if (isDirectory) 0b111_101_101 else 0b110_100_100
            } else {
                mode
            }
            val permissions = HashSet<PosixFilePermission>()
            if (effective and 0b100_000_000 != 0) permissions.add(PosixFilePermission.OWNER_READ)
            if (effective and 0b010_000_000 != 0) permissions.add(PosixFilePermission.OWNER_WRITE)
            if (effective and 0b001_000_000 != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE)
            if (effective and 0b000_100_000 != 0) permissions.add(PosixFilePermission.GROUP_READ)
            if (effective and 0b000_010_000 != 0) permissions.add(PosixFilePermission.GROUP_WRITE)
            if (effective and 0b000_001_000 != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE)
            if (effective and 0b000_000_100 != 0) permissions.add(PosixFilePermission.OTHERS_READ)
            if (effective and 0b000_000_010 != 0) permissions.add(PosixFilePermission.OTHERS_WRITE)
            if (effective and 0b000_000_001 != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE)
            // The owner must always be able to traverse and read what we wrote,
            // or the next step of the install cannot continue.
            permissions.add(PosixFilePermission.OWNER_READ)
            permissions.add(PosixFilePermission.OWNER_WRITE)
            if (isDirectory) permissions.add(PosixFilePermission.OWNER_EXECUTE)
            return permissions
        }
    }
}

/** One parsed 512 byte tar header. */
internal data class TarHeader(
    val name: String,
    val mode: Int,
    val size: Long,
    val typeFlag: Char,
    val linkName: String,
    val prefix: String,
) {
    companion object {
        fun parse(block: ByteArray): TarHeader {
            val name = block.string(0, 100)
            val mode = block.octal(100, 8).toInt()
            val size = block.size(124, 12)
            val typeFlag = block[156].toInt().toChar()
            val linkName = block.string(157, 100)
            val prefix = block.string(345, 155)
            val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name
            return TarHeader(fullName, mode, size, typeFlag, linkName, prefix)
        }

        private fun ByteArray.string(offset: Int, length: Int): String {
            var end = offset
            val limit = offset + length
            while (end < limit && this[end] != 0.toByte()) end++
            return String(this, offset, end - offset, StandardCharsets.UTF_8)
        }

        private fun ByteArray.octal(offset: Int, length: Int): Long {
            var value = 0L
            for (index in offset until offset + length) {
                val byte = this[index].toInt()
                if (byte == 0 || byte == ' '.code) continue
                if (byte < '0'.code || byte > '7'.code) break
                value = value * 8 + (byte - '0'.code)
            }
            return value
        }

        /**
         * Reads a size field, handling GNU's base-256 encoding.
         *
         * Files over 8 GiB cannot be written in octal, so GNU tar sets the high
         * bit of the first byte and stores a big-endian integer instead.
         */
        private fun ByteArray.size(offset: Int, length: Int): Long {
            if (this[offset].toInt() and 0x80 != 0) {
                var value = (this[offset].toLong() and 0x7F)
                for (index in offset + 1 until offset + length) {
                    value = (value shl 8) or (this[index].toLong() and 0xFF)
                }
                return value
            }
            return octal(offset, length)
        }
    }
}

/** Parses the `length key=value\n` records of a pax extended header. */
internal object PaxHeader {
    fun parse(text: String): Map<String, String> {
        val result = HashMap<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val declaredLength = text.substring(index, space).toIntOrNull() ?: break
            if (declaredLength <= 0 || index + declaredLength > text.length) break
            val record = text.substring(space + 1, index + declaredLength).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) {
                result[record.substring(0, equals)] = record.substring(equals + 1)
            }
            index += declaredLength
        }
        return result
    }
}

private const val TAR_BLOCK = 512

private fun InputStream.readFully(buffer: ByteArray): Boolean {
    var read = 0
    while (read < buffer.size) {
        val count = read(buffer, read, buffer.size - read)
        if (count < 0) return read != 0 && fail(read)
        read += count
    }
    return true
}

private fun fail(read: Int): Nothing = throw IOException("tar stream ended mid-header after $read bytes")

private fun InputStream.readString(size: Long): String {
    val bytes = ByteArray(size.toInt())
    if (!readFully(bytes)) throw IOException("tar stream ended inside an extended header")
    return String(bytes, StandardCharsets.UTF_8).trimEnd('\u0000')
}

private fun InputStream.copyExactly(size: Long, out: java.io.OutputStream) {
    val chunk = ByteArray(COPY_CHUNK)
    var left = size
    while (left > 0) {
        val want = minOf(left, chunk.size.toLong()).toInt()
        val read = read(chunk, 0, want)
        if (read < 0) throw IOException("tar stream ended with $left bytes of an entry left")
        out.write(chunk, 0, read)
        left -= read
    }
}

private fun InputStream.skipEntry(size: Long) {
    var left = size
    val chunk = ByteArray(COPY_CHUNK)
    while (left > 0) {
        val read = read(chunk, 0, minOf(left, chunk.size.toLong()).toInt())
        if (read < 0) return
        left -= read
    }
}

/** Entries are padded to a 512 byte boundary. */
private fun InputStream.skipPadding(size: Long) {
    val remainder = (size % TAR_BLOCK).toInt()
    if (remainder == 0) return
    skipEntry((TAR_BLOCK - remainder).toLong())
}

private const val COPY_CHUNK = 1 shl 16
