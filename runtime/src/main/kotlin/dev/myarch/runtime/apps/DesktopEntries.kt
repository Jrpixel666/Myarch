package dev.myarch.runtime.apps

import java.io.File

/** A launchable Linux application, read from a `.desktop` file. */
public data class DesktopEntry(
    val id: String,
    val name: String,
    val comment: String,
    /** The `Exec=` line with its field codes removed. */
    val exec: String,
    val icon: String?,
    val categories: List<String>,
    val terminal: Boolean,
    val noDisplay: Boolean,
) {
    /** A grouping for the app drawer. */
    public val primaryCategory: String
        get() = categories.firstOrNull { it in MAIN_CATEGORIES } ?: "Other"

    private companion object {
        val MAIN_CATEGORIES = setOf(
            "AudioVideo", "Audio", "Video", "Development", "Education", "Game",
            "Graphics", "Network", "Office", "Science", "Settings", "System", "Utility",
        )
    }
}

/**
 * Reads the container's installed applications.
 *
 * The files are read straight off the rootfs rather than by running a command
 * inside the container: the app drawer should open instantly, and starting
 * proot to list files would add a second or more every time. The format is
 * specified (freedesktop Desktop Entry), so parsing it here is safe.
 */
public object DesktopEntries {

    /** The directories a `.desktop` file can live in, in priority order. */
    public val SEARCH_PATHS: List<String> = listOf(
        "root/.local/share/applications",
        "usr/local/share/applications",
        "usr/share/applications",
    )

    /** Scans [rootfs] and returns the entries worth showing in a drawer. */
    public fun scan(rootfs: File, includeHidden: Boolean = false): List<DesktopEntry> {
        val seen = HashMap<String, DesktopEntry>()
        for (relative in SEARCH_PATHS) {
            val directory = File(rootfs, relative)
            val files = directory.listFiles { file -> file.isFile && file.extension == "desktop" }
                ?: continue
            for (file in files) {
                val entry = runCatching { parse(file.readText(), file.nameWithoutExtension) }
                    .getOrNull()
                    ?: continue
                if (!includeHidden && (entry.noDisplay || entry.exec.isBlank())) continue
                // Earlier paths win: a user's own entry overrides the system one.
                seen.putIfAbsent(entry.id, entry)
            }
        }
        return seen.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Parses one desktop file.
     *
     * Only the `[Desktop Entry]` group is read; actions and other groups are
     * ignored, which is what a launcher needs.
     */
    public fun parse(text: String, fallbackId: String): DesktopEntry? {
        var inEntry = false
        val values = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("[")) {
                inEntry = line.equals("[Desktop Entry]", ignoreCase = true)
                continue
            }
            if (!inEntry) continue
            val equals = line.indexOf('=')
            if (equals <= 0) continue
            val key = line.substring(0, equals).trim()
            // Localised keys look like `Name[de]`; the unlocalised one is used.
            if (key.contains('[')) continue
            values[key] = line.substring(equals + 1).trim()
        }
        if (!inEntry && values.isEmpty()) return null
        val type = values["Type"] ?: "Application"
        if (!type.equals("Application", ignoreCase = true)) return null
        return DesktopEntry(
            id = fallbackId,
            name = values["Name"] ?: fallbackId,
            comment = values["GenericName"] ?: values["Comment"] ?: "",
            exec = stripFieldCodes(values["Exec"] ?: ""),
            icon = values["Icon"],
            categories = (values["Categories"] ?: "").split(';').filter { it.isNotBlank() },
            terminal = values["Terminal"].equals("true", ignoreCase = true),
            noDisplay = values["NoDisplay"].equals("true", ignoreCase = true) ||
                values["Hidden"].equals("true", ignoreCase = true),
        )
    }

    /**
     * Removes the `%f`, `%U`, `%i` and friends from an `Exec` line.
     *
     * Passing them through unchanged makes applications open a file literally
     * named `%U`, which is a confusing first impression.
     */
    public fun stripFieldCodes(exec: String): String = exec
        .replace(Regex("%[fFuUdDnNickvm]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
