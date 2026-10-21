package dev.myarch.runtime.install

import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.TargetArch
import dev.myarch.runtime.container.ContainerManager
import dev.myarch.runtime.container.LogLine
import dev.myarch.runtime.download.DownloadOutcome
import dev.myarch.runtime.download.Downloader
import dev.myarch.runtime.download.HttpSource
import dev.myarch.runtime.download.UrlHttpSource
import dev.myarch.runtime.mirror.DefaultMirrors
import dev.myarch.runtime.mirror.Mirror
import dev.myarch.runtime.mirror.MirrorProbe
import dev.myarch.runtime.mirror.MirrorRotation
import dev.myarch.runtime.mirror.ProbeResult
import dev.myarch.runtime.pacman.PackageSets
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

/** How the install should be performed. */
public data class InstallOptions(
    val arch: TargetArch,
    /** A mirror the user chose explicitly; `null` means "decide for me". */
    val preferredMirror: Mirror? = null,
    /** Probe mirrors before downloading. Off when the user pinned one. */
    val probeMirrors: Boolean = true,
    /** Run `pacman -Syu` and install the Omarchy package set after extraction. */
    val bootstrapDesktop: Boolean = true,
    /** Extra packages to install alongside the defaults. */
    val extraPackages: List<String> = emptyList(),
    /** Keep the downloaded tarball so a reinstall does not re-download it. */
    val keepArchive: Boolean = false,
)

/** The outcome of a whole install run. */
public sealed interface InstallResult {
    public data object Success : InstallResult

    public data class Failure(val reason: String, val stage: InstallStage.Failed) : InstallResult

    public data object Cancelled : InstallResult
}

/**
 * Downloads, extracts and bootstraps the Arch container.
 *
 * The flow is deliberately restartable at every step: a half-downloaded
 * tarball is resumed, an extracted rootfs is not re-extracted unless it is
 * incomplete, and bootstrapping can be re-run. Installing a distribution over
 * a phone connection fails often enough that "start again from the beginning"
 * is not an acceptable recovery story.
 */
public class RootfsInstaller(
    private val paths: MyarchPaths,
    private val containerManager: ContainerManager,
    private val listener: InstallListener,
    private val http: HttpSource = UrlHttpSource(),
    private val downloader: Downloader = Downloader(http),
    private val mirrors: List<Mirror> = DefaultMirrors.rootfs,
    private val isCancelled: () -> Boolean = { false },
) {

    @Suppress("ReturnCount")
    public fun install(options: InstallOptions): InstallResult {
        paths.prepare()
        listener.onStage(InstallStage.Preparing("Checking the bundled proot helper"))

        containerManager.readinessProblem()
            ?.takeIf { !paths.isInstalled }
            ?.let { problem ->
                // A missing proot is not a mirror problem and retrying will not
                // fix it, so say so plainly instead of starting a download.
                if (problem.contains("libproot")) {
                    return fail(problem, canRetry = false, canSwitchMirror = false)
                }
            }

        val rotation = MirrorRotation(mirrors, options.preferredMirror)
        if (options.probeMirrors && options.preferredMirror == null) {
            probeMirrors(rotation, options.arch)
        }

        val archive = downloadWithFallback(rotation, options)
            ?: return lastFailure ?: fail("No mirror could provide a rootfs image", true, true)

        if (isCancelled()) return InstallResult.Cancelled

        when (val extraction = extract(archive)) {
            is ExtractOutcome.Failed ->
                return fail(extraction.reason, canRetry = true, canSwitchMirror = true)
            is ExtractOutcome.Done ->
                listener.onLog(
                    InstallLogLine(
                        "Extracted ${extraction.summary.files} files, " +
                            "${extraction.summary.directories} directories, " +
                            "${extraction.summary.symlinks} symlinks",
                        InstallLogLine.Level.SUCCESS,
                    ),
                )
        }

        if (!options.keepArchive && archive.delete()) {
            listener.onLog(InstallLogLine("Removed the downloaded archive to free space"))
        }

        configure(rotation.current ?: mirrors.first(), options.arch)

        if (options.bootstrapDesktop) {
            val bootstrapped = bootstrap(options)
            if (!bootstrapped) {
                // The rootfs is on disk and usable; only the package step
                // failed, so the marker is still written and the user can retry
                // just the bootstrap from the app.
                markInstalled()
                return lastFailure ?: fail("Package bootstrap failed", canRetry = true, canSwitchMirror = true)
            }
        }

        markInstalled()
        listener.onStage(InstallStage.Complete)
        listener.onLog(InstallLogLine("Myarch container ready", InstallLogLine.Level.SUCCESS))
        return InstallResult.Success
    }

    // ------------------------------------------------------------- mirrors

    private fun probeMirrors(rotation: MirrorRotation, arch: TargetArch) {
        val pending = rotation.pending()
        listener.onStage(InstallStage.CheckingMirrors(0, pending.size))
        var index = 0
        val probe = MirrorProbe { url ->
            val started = System.nanoTime()
            val response = http.probe(url, PROBE_TIMEOUT_MILLIS)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (response.isSuccess || response.statusCode in 200..299) {
                ProbeResult.Reachable(millis, response.contentLength.takeIf { it > 0 })
            } else {
                ProbeResult.Unreachable(response.errorMessage ?: "HTTP ${response.statusCode}")
            }
        }
        // Probing is sequential on purpose: a phone on a weak connection does
        // itself no favours opening nine sockets at once.
        val results = pending.associate { mirror ->
            listener.onStage(InstallStage.CheckingMirrors(++index, pending.size))
            val result = probe.probe(mirror.rootfsUrl(arch))
            when (result) {
                is ProbeResult.Reachable ->
                    listener.onLog(InstallLogLine("${mirror.label}: ${result.latencyMillis} ms"))
                is ProbeResult.Unreachable ->
                    listener.onLog(
                        InstallLogLine("${mirror.label}: ${result.reason}", InstallLogLine.Level.WARN),
                    )
            }
            mirror.id to result
        }
        rotation.applyProbeResults(results)
        val best = rotation.current
        if (best != null) {
            listener.onLog(InstallLogLine("Using ${best.label}", InstallLogLine.Level.SUCCESS))
        }
    }

    private var lastFailure: InstallResult.Failure? = null

    /**
     * Downloads the image, moving to the next mirror on each failure.
     *
     * This is the automatic half of the mirror switcher: the user is only asked
     * to intervene once every candidate has been tried.
     */
    private fun downloadWithFallback(rotation: MirrorRotation, options: InstallOptions): File? {
        val destination = File(paths.downloads, "ArchLinuxARM-${options.arch.archName}.tar.gz")
        while (true) {
            if (isCancelled()) return null
            val mirror = rotation.current ?: return null
            val url = mirror.rootfsUrl(options.arch)
            listener.onLog(InstallLogLine("Downloading from ${mirror.label} ($url)"))
            val outcome = downloader.download(
                url = url,
                destination = destination,
                isCancelled = isCancelled,
            ) { progress ->
                listener.onStage(InstallStage.Downloading(mirror, progress))
            }
            when (outcome) {
                is DownloadOutcome.Success -> {
                    listener.onLog(
                        InstallLogLine(
                            "Downloaded ${outcome.bytes / BYTES_PER_MIB} MiB (sha256 ${outcome.sha256.take(12)}...)",
                            InstallLogLine.Level.SUCCESS,
                        ),
                    )
                    return outcome.file
                }
                DownloadOutcome.Cancelled -> return null
                is DownloadOutcome.Failed -> {
                    listener.onLog(
                        InstallLogLine("${mirror.label} failed: ${outcome.reason}", InstallLogLine.Level.ERROR),
                    )
                    val next = rotation.onFailure(mirror, outcome.reason)
                    if (next == null || next.id == mirror.id) {
                        lastFailure = failureOf(
                            "Every mirror failed. Last error from ${mirror.label}: ${outcome.reason}",
                            canRetry = true,
                            canSwitchMirror = true,
                            mirror = mirror,
                        )
                        return null
                    }
                    listener.onLog(InstallLogLine("Switching to ${next.label}", InstallLogLine.Level.WARN))
                }
            }
        }
    }

    // ------------------------------------------------------------ extraction

    private sealed interface ExtractOutcome {
        data class Done(val summary: TarExtractor.Summary) : ExtractOutcome

        data class Failed(val reason: String) : ExtractOutcome
    }

    private fun extract(archive: File): ExtractOutcome {
        var entries = 0
        var bytes = 0L
        val extractor = TarExtractor(paths.rootfs) { name, size ->
            entries++
            bytes += size
            if (entries % EXTRACT_REPORT_EVERY == 0) {
                listener.onStage(InstallStage.Extracting(entries, bytes))
                listener.onLog(InstallLogLine("  $name"))
            }
        }
        listener.onStage(InstallStage.Extracting(0, 0))
        return try {
            openCompressed(archive).use { stream ->
                val summary = extractor.extract(stream)
                if (summary.files < MINIMUM_PLAUSIBLE_FILES) {
                    ExtractOutcome.Failed(
                        "The archive only held ${summary.files} files, so it is not a complete rootfs. " +
                            "The download was probably truncated or an error page.",
                    )
                } else {
                    ExtractOutcome.Done(summary)
                }
            }
        } catch (io: IOException) {
            ExtractOutcome.Failed("Could not extract the archive: ${io.message}")
        } catch (illegal: IllegalArgumentException) {
            ExtractOutcome.Failed("The archive is not in a format Myarch can read: ${illegal.message}")
        }
    }

    /**
     * Opens a tarball, detecting the compression from its magic bytes.
     *
     * The file name is not trusted: mirrors serve `.tar.gz` that is really
     * uncompressed, and a captive portal's HTML error page named `.tar.gz` is
     * the single most common "corrupt download" report.
     */
    private fun openCompressed(archive: File): InputStream {
        val header = ByteArray(MAGIC_BYTES)
        archive.inputStream().use { it.read(header) }
        val stream = BufferedInputStream(archive.inputStream(), STREAM_BUFFER)
        return when {
            header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() -> GZIPInputStream(stream, STREAM_BUFFER)
            header[0] == 0xFD.toByte() && header[1] == '7'.code.toByte() -> XZInputStream(stream)
            header[0] == 0x28.toByte() && header[1] == 0xB5.toByte() ->
                throw IllegalArgumentException(
                    "zstd images are not supported; use a .tar.gz or .tar.xz rootfs",
                )
            header.startsWith("<!DOCTYPE") || header.startsWith("<html") ->
                throw IllegalArgumentException(
                    "the mirror returned a web page instead of an image; switch mirrors",
                )
            else -> stream
        }
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && String(this, 0, prefix.length).equals(prefix, ignoreCase = true)

    // ----------------------------------------------------------- configuration

    /**
     * Writes the files a fresh Arch rootfs needs before pacman will work.
     *
     * None of this can be done from inside the container, because pacman cannot
     * resolve names until `resolv.conf` exists.
     */
    private fun configure(mirror: Mirror, arch: TargetArch) {
        listener.onStage(InstallStage.Configuring("Writing container configuration"))

        write("etc/resolv.conf", DNS_SERVERS.joinToString("\n") { "nameserver $it" } + "\n")
        write("etc/hosts", "127.0.0.1 localhost\n::1 localhost\n127.0.0.1 myarch\n")
        write("etc/hostname", "myarch\n")
        write("etc/locale.conf", "LANG=C.UTF-8\n")
        write(
            "etc/pacman.d/mirrorlist",
            buildString {
                appendLine("# Written by Myarch. Preferred mirror first.")
                appendLine(mirror.pacmanServerLine())
                for (other in DefaultMirrors.pacman.filter { it.id != mirror.id }) {
                    appendLine("#${other.pacmanServerLine()}   # ${other.label}")
                }
            },
        )
        // proot cannot honour an ld.so.preload, and a stale one breaks every
        // guest binary with a confusing loader error.
        File(paths.rootfs, "etc/ld.so.preload").delete()

        for (dir in listOf("run/user/0", "tmp", "dev/shm", "proc", "sys", "opt/myarch")) {
            File(paths.rootfs, dir).mkdirs()
        }
        listener.onLog(InstallLogLine("Configured DNS, hostname and the pacman mirrorlist for ${arch.archName}"))
    }

    private fun write(relativePath: String, content: String) {
        val file = File(paths.rootfs, relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    // ------------------------------------------------------------ bootstrap

    /** Runs the guest-side first-boot script and installs the desktop. */
    private fun bootstrap(options: InstallOptions): Boolean {
        val session = containerManager.session()
        val packages = (PackageSets.OMARCHY_CORE + options.extraPackages).distinct()

        val steps = listOf(
            "Initialising the package keyring" to
                "${MyarchPaths.GUEST_SCRIPTS_DIR}/first-boot.sh",
            "Installing the Omarchy desktop" to
                "${MyarchPaths.GUEST_SCRIPTS_DIR}/install-desktop.sh ${packages.joinToString(" ")}",
        )

        for ((label, script) in steps) {
            if (isCancelled()) return false
            listener.onStage(InstallStage.Bootstrapping(label, ""))
            listener.onLog(InstallLogLine(label))
            val result = session.shell(
                script,
                timeoutSeconds = BOOTSTRAP_TIMEOUT_SECONDS,
            ) { line: LogLine ->
                listener.onStage(InstallStage.Bootstrapping(label, line.text))
                listener.onLog(
                    InstallLogLine(
                        line.text,
                        if (line.isError) InstallLogLine.Level.WARN else InstallLogLine.Level.INFO,
                    ),
                )
            }
            if (!result.isSuccess) {
                lastFailure = failureOf(
                    "$label failed (exit ${result.exitCode}). Last output:\n" +
                        result.output.takeLast(TAIL_LINES).joinToString("\n"),
                    canRetry = true,
                    canSwitchMirror = true,
                )
                return false
            }
        }
        return true
    }

    private fun markInstalled() {
        paths.installMarker.writeText(
            "installed=${System.currentTimeMillis()}\nrootfs=${paths.rootfs.absolutePath}\n",
        )
    }

    private fun fail(reason: String, canRetry: Boolean, canSwitchMirror: Boolean): InstallResult.Failure =
        failureOf(reason, canRetry, canSwitchMirror).also {
            listener.onStage(it.stage)
            listener.onLog(InstallLogLine(reason, InstallLogLine.Level.ERROR))
        }

    private fun failureOf(
        reason: String,
        canRetry: Boolean,
        canSwitchMirror: Boolean,
        mirror: Mirror? = null,
    ): InstallResult.Failure = InstallResult.Failure(
        reason,
        InstallStage.Failed(reason, canRetry, canSwitchMirror, mirror),
    )

    public companion object {
        private const val PROBE_TIMEOUT_MILLIS = 8_000
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val EXTRACT_REPORT_EVERY = 250
        private const val STREAM_BUFFER = 1 shl 16
        private const val MAGIC_BYTES = 16
        private const val TAIL_LINES = 20

        /** An Arch rootfs has tens of thousands of files; 500 means truncated. */
        private const val MINIMUM_PLAUSIBLE_FILES = 500

        private const val BOOTSTRAP_TIMEOUT_SECONDS = 60L * 45L

        /**
         * proot containers inherit no DNS configuration, and Android does not
         * expose the active resolvers to apps, so public resolvers are the
         * pragmatic default. The file is plain text; a user can change it.
         */
        private val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9")
    }
}
