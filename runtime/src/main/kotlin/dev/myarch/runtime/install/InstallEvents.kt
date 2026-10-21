package dev.myarch.runtime.install

import dev.myarch.runtime.download.DownloadProgress
import dev.myarch.runtime.mirror.Mirror

/** Where the installer has got to. Drives the installer UI. */
public sealed interface InstallStage {
    public data object Idle : InstallStage

    public data class Preparing(val detail: String) : InstallStage

    /** Testing mirrors before committing to a large download. */
    public data class CheckingMirrors(val tried: Int, val total: Int) : InstallStage

    public data class Downloading(
        val mirror: Mirror,
        val progress: DownloadProgress,
    ) : InstallStage

    public data class Extracting(val entries: Int, val bytes: Long) : InstallStage

    public data class Configuring(val detail: String) : InstallStage

    /** Running pacman inside the new container. */
    public data class Bootstrapping(val step: String, val line: String) : InstallStage

    public data object Complete : InstallStage

    /**
     * The install stopped.
     *
     * @param canSwitchMirror true when another mirror is worth trying, which
     *   is what the installer's "switch mirror" button acts on
     */
    public data class Failed(
        val reason: String,
        val canRetry: Boolean,
        val canSwitchMirror: Boolean,
        val mirror: Mirror? = null,
    ) : InstallStage
}

/** A line for the installer's scrolling log. */
public data class InstallLogLine(
    val text: String,
    val level: Level = Level.INFO,
) {
    public enum class Level { INFO, WARN, ERROR, SUCCESS }
}

/** What the installer reports as it works. */
public interface InstallListener {
    public fun onStage(stage: InstallStage)

    public fun onLog(line: InstallLogLine)
}
