package dev.myarch.app.ui.console

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.myarch.app.data.MyarchSettings
import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.TargetArch
import dev.myarch.runtime.container.ContainerManager
import dev.myarch.runtime.install.InstallListener
import dev.myarch.runtime.install.InstallLogLine
import dev.myarch.runtime.install.InstallOptions
import dev.myarch.runtime.install.InstallResult
import dev.myarch.runtime.install.InstallStage
import dev.myarch.runtime.install.RootfsInstaller
import dev.myarch.runtime.mirror.DefaultMirrors
import dev.myarch.runtime.mirror.Mirror

/**
 * Drives the installer and exposes its progress to Compose.
 *
 * The install runs on its own thread rather than a coroutine on the IO
 * dispatcher: it is one long, blocking, cancellable job that outlives the
 * screen, and a plain thread with a volatile cancel flag is easier to reason
 * about than structured concurrency spanning an activity's lifecycle.
 */
public class InstallController(
    private val paths: MyarchPaths,
    private val containerManager: ContainerManager,
    private val settings: MyarchSettings,
) {
    public var stage: InstallStage by mutableStateOf(InstallStage.Idle)
        private set

    public val log: MutableList<InstallLogLine> = mutableStateListOf()

    public var selectedMirror: Mirror? by mutableStateOf(
        settings.preferredMirrorId?.let(DefaultMirrors::byId),
    )
        private set

    private var customMirrorState by mutableStateOf(settings.customMirrorUrl ?: "")

    /**
     * A mirror URL the user typed.
     *
     * Persisted on change: an install can take long enough that the app is
     * killed and reopened, and retyping a URL then would be rude. A delegated
     * property cannot carry a custom setter, hence the explicit backing state.
     */
    public var customMirrorUrl: String
        get() = customMirrorState
        set(value) {
            customMirrorState = value
            settings.customMirrorUrl = value.ifBlank { null }
        }

    public var includeExtras: MutableList<String> = mutableStateListOf()

    public var isRunning: Boolean by mutableStateOf(false)
        private set

    @Volatile
    private var cancelled = false

    private var worker: Thread? = null

    /** Mirrors offered in the picker: the bundled list plus any custom URL. */
    public fun mirrors(): List<Mirror> = DefaultMirrors.rootfs

    public fun selectMirror(mirror: Mirror?) {
        selectedMirror = mirror
        settings.preferredMirrorId = mirror?.id
    }

    /** The architecture Myarch will install for, or `null` if unsupported. */
    public fun detectArch(supportedAbis: List<String>): TargetArch? = TargetArch.forAbis(supportedAbis)

    /**
     * Starts the install.
     *
     * @param arch the detected device architecture
     * @param bootstrap whether to run pacman afterwards; turning it off gives a
     *   bare rootfs quickly, which is useful when debugging the container
     */
    public fun start(arch: TargetArch, bootstrap: Boolean = true) {
        if (isRunning) return
        cancelled = false
        isRunning = true
        log.clear()
        stage = InstallStage.Preparing("Starting")

        val pinned = when {
            customMirrorUrl.isNotBlank() -> DefaultMirrors.custom(customMirrorUrl)
            else -> selectedMirror
        }

        val listener = object : InstallListener {
            override fun onStage(newStage: InstallStage) {
                stage = newStage
            }

            override fun onLog(line: InstallLogLine) {
                log.add(line)
                if (log.size > MAX_LOG_LINES) log.removeAt(0)
            }
        }

        val installer = RootfsInstaller(
            paths = paths,
            containerManager = containerManager,
            listener = listener,
            isCancelled = { cancelled },
        )

        worker = Thread({
            val result = installer.install(
                InstallOptions(
                    arch = arch,
                    preferredMirror = pinned,
                    probeMirrors = pinned == null,
                    bootstrapDesktop = bootstrap,
                    extraPackages = includeExtras.toList(),
                ),
            )
            isRunning = false
            if (result is InstallResult.Cancelled) {
                stage = InstallStage.Failed("Cancelled", canRetry = true, canSwitchMirror = false)
            }
        }, "myarch-installer").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
    }

    public fun cancel() {
        cancelled = true
        worker?.interrupt()
    }

    /**
     * Retries with the next mirror.
     *
     * The failed mirror is dropped from the pin so the rotation is free to pick
     * a different one, which is what the user means by "switch mirror".
     */
    public fun retryWithDifferentMirror(arch: TargetArch) {
        val failed = (stage as? InstallStage.Failed)?.mirror
        if (failed != null && failed.id == selectedMirror?.id) {
            selectMirror(null)
        }
        if (failed != null && customMirrorUrl.isNotBlank() &&
            DefaultMirrors.custom(customMirrorUrl).baseUrl == failed.baseUrl
        ) {
            customMirrorUrl = ""
        }
        start(arch)
    }

    private companion object {
        const val MAX_LOG_LINES = 400
    }
}
