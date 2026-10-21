package dev.myarch.runtime.pacman

import dev.myarch.runtime.container.ContainerSession
import dev.myarch.runtime.container.ExecResult
import dev.myarch.runtime.container.LogLine

/** One result from a package search. */
public data class PackageInfo(
    val repository: String,
    val name: String,
    val version: String,
    val description: String,
    val isInstalled: Boolean,
    /** Installed size as pacman reports it, when known. */
    val installedSize: String? = null,
)

/**
 * Runs pacman inside the container.
 *
 * Package management is wrapped rather than exposed as a terminal because the
 * app needs structured results -- the widget builder asks "is `qt6-declarative`
 * installed?" and the browser needs a list it can render. Output parsing is a
 * pure function so it can be tested against real pacman output.
 */
public class Pacman(private val session: ContainerSession) {

    /** Refreshes the package databases. */
    public fun sync(onLine: (LogLine) -> Unit = {}): ExecResult =
        session.shell("pacman -Sy --noconfirm", timeoutSeconds = SYNC_TIMEOUT, onLine = onLine)

    /** Full system upgrade. Slow, and worth warning the user about. */
    public fun upgrade(onLine: (LogLine) -> Unit = {}): ExecResult =
        session.shell("pacman -Syu --noconfirm", timeoutSeconds = INSTALL_TIMEOUT, onLine = onLine)

    /**
     * Installs packages.
     *
     * `--needed` keeps a re-run cheap, which matters because the widget builder
     * installs a dependency set every time it scaffolds something.
     */
    public fun install(packages: List<String>, onLine: (LogLine) -> Unit = {}): ExecResult {
        if (packages.isEmpty()) return ExecResult(0, listOf("nothing to install"))
        val quoted = packages.joinToString(" ") { shellQuote(it) }
        return session.shell(
            "pacman -S --needed --noconfirm $quoted",
            timeoutSeconds = INSTALL_TIMEOUT,
            onLine = onLine,
        )
    }

    /** Removes packages and their now-unneeded dependencies. */
    public fun remove(packages: List<String>, onLine: (LogLine) -> Unit = {}): ExecResult {
        if (packages.isEmpty()) return ExecResult(0, emptyList())
        val quoted = packages.joinToString(" ") { shellQuote(it) }
        return session.shell("pacman -Rns --noconfirm $quoted", timeoutSeconds = INSTALL_TIMEOUT, onLine = onLine)
    }

    /** Searches the repositories. */
    public fun search(query: String): List<PackageInfo> {
        if (query.isBlank()) return emptyList()
        val result = session.shell("pacman -Ss ${shellQuote(query)}", timeoutSeconds = QUERY_TIMEOUT)
        return parseSearchOutput(result.output)
    }

    /** Lists explicitly installed packages. */
    public fun installedExplicitly(): List<PackageInfo> {
        val result = session.shell("pacman -Qe", timeoutSeconds = QUERY_TIMEOUT)
        return parseQueryOutput(result.output)
    }

    /** True when every name in [packages] is installed. */
    public fun areInstalled(packages: List<String>): Map<String, Boolean> {
        if (packages.isEmpty()) return emptyMap()
        val quoted = packages.joinToString(" ") { shellQuote(it) }
        val result = session.shell("pacman -Q $quoted 2>/dev/null || true", timeoutSeconds = QUERY_TIMEOUT)
        val present = parseQueryOutput(result.output).map { it.name }.toSet()
        return packages.associateWith { it in present }
    }

    /**
     * Runs an arbitrary command in the same container session.
     *
     * Exposed here because the builder's post-install steps (a `chmod`, a cache
     * refresh) belong to the same unit of work as the package install, and
     * opening a second session for them would be wasteful.
     */
    public fun runShell(
        script: String,
        onLine: (LogLine) -> Unit = {},
        timeoutSeconds: Long = QUERY_TIMEOUT,
    ): ExecResult = session.shell(script, timeoutSeconds = timeoutSeconds, onLine = onLine)

    /** Which package owns a path; useful when a build complains about a header. */
    public fun owner(path: String): String? {
        val result = session.shell("pacman -Qoq ${shellQuote(path)} 2>/dev/null || true", timeoutSeconds = QUERY_TIMEOUT)
        return result.output.firstOrNull { it.isNotBlank() }?.trim()
    }

    public companion object {
        private const val SYNC_TIMEOUT = 300L
        private const val QUERY_TIMEOUT = 120L
        private const val INSTALL_TIMEOUT = 60L * 60L

        /**
         * Quotes a value for a `bash -c` command line.
         *
         * Package names are user input (the search box, the widget builder's
         * dependency list), and they end up in a shell command, so they get
         * single-quoted with embedded quotes escaped.
         */
        public fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

        /**
         * Parses `pacman -Ss` output.
         *
         * The format is two lines per package:
         * ```
         * extra/alacritty 0.13.2-1 [installed]
         *     A cross-platform, GPU-accelerated terminal emulator
         * ```
         */
        public fun parseSearchOutput(lines: List<String>): List<PackageInfo> {
            val results = ArrayList<PackageInfo>()
            var index = 0
            while (index < lines.size) {
                val header = lines[index]
                index++
                if (header.isBlank() || header.startsWith(" ")) continue
                val slash = header.indexOf('/')
                if (slash <= 0) continue
                val repository = header.substring(0, slash)
                val rest = header.substring(slash + 1).trim()
                val parts = rest.split(' ').filter { it.isNotBlank() }
                if (parts.isEmpty()) continue
                val name = parts[0]
                val version = parts.getOrElse(1) { "" }
                val installed = rest.contains("[installed")
                val description = lines.getOrNull(index)
                    ?.takeIf { it.startsWith(" ") || it.startsWith("\t") }
                    ?.trim()
                    ?.also { index++ }
                    ?: ""
                results.add(PackageInfo(repository, name, version, description, installed))
            }
            return results
        }

        /** Parses `pacman -Q`/`-Qe` output: one `name version` per line. */
        public fun parseQueryOutput(lines: List<String>): List<PackageInfo> = lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("error")) return@mapNotNull null
            val parts = trimmed.split(' ').filter { it.isNotBlank() }
            if (parts.size < 2) return@mapNotNull null
            PackageInfo(
                repository = "local",
                name = parts[0],
                version = parts[1],
                description = "",
                isInstalled = true,
            )
        }
    }
}
