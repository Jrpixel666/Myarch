package dev.myarch.app.ui.console

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.myarch.app.MyarchApplication
import dev.myarch.app.compositor.CompositorService
import dev.myarch.app.compositor.SessionState
import dev.myarch.runtime.container.LogLine
import dev.myarch.runtime.pacman.Pacman
import dev.myarch.runtime.pacman.PackageInfo
import dev.myarch.runtime.pacman.PackageSets
import dev.myarch.runtime.theme.BuiltInThemes
import dev.myarch.runtime.theme.OmarchyTheme
import dev.myarch.runtime.theme.ThemeWriter
import dev.myarch.runtime.widgets.BuildOutcome
import dev.myarch.runtime.widgets.BuildTemplate
import dev.myarch.runtime.widgets.BuiltInTemplates
import dev.myarch.runtime.widgets.WidgetBuilder

/** State shared by the console's screens. */
public class ConsoleState(
    private val context: Context,
    private val app: MyarchApplication,
    public val installer: InstallController,
    public val abis: List<String>,
) {
    public var route: ConsoleRoute by mutableStateOf(ConsoleRoute.OVERVIEW)

    public var theme: OmarchyTheme by mutableStateOf(app.theme)
        private set

    public var service: CompositorService? by mutableStateOf(null)
        private set

    public val isInstalled: Boolean get() = app.paths.isInstalled

    public val readinessProblem: String? get() = app.containerManager.readinessProblem()

    /**
     * The session state, observed rather than sampled.
     *
     * `StateFlow.value` read inside a composable would never recompose, so the
     * flow is collected; before the service binds there is nothing to collect
     * and the session is, accurately, stopped.
     */
    @androidx.compose.runtime.Composable
    public fun sessionState(): SessionState {
        val bound = service ?: return SessionState.Stopped
        return bound.state.collectAsState().value
    }

    /** The session log, likewise collected so new lines appear. */
    @androidx.compose.runtime.Composable
    public fun sessionLog(): List<String> {
        val bound = service ?: return emptyList()
        return bound.sessionLog.collectAsState().value
    }

    // --------------------------------------------------------------- packages

    public val searchResults: MutableList<PackageInfo> = mutableStateListOf()

    public var packageQuery: String by mutableStateOf("")

    public var packageBusy: Boolean by mutableStateOf(false)
        private set

    public val packageLog: MutableList<String> = mutableStateListOf()

    public val suggested: List<dev.myarch.runtime.pacman.SuggestedPackage> = PackageSets.SUGGESTED_EXTRAS

    // ---------------------------------------------------------------- builder

    public val templates: List<BuildTemplate> = BuiltInTemplates.all

    public var buildName: String by mutableStateOf("")

    public var selectedTemplate: BuildTemplate? by mutableStateOf(null)

    public var buildBusy: Boolean by mutableStateOf(false)
        private set

    public val buildLog: MutableList<String> = mutableStateListOf()

    public var lastBuild: BuildOutcome? by mutableStateOf(null)
        private set

    public var missingForTemplate: List<String> by mutableStateOf(emptyList())
        private set

    public fun attach(bound: CompositorService) {
        service = bound
    }

    // ------------------------------------------------------------------ themes

    public val themes: List<OmarchyTheme> = BuiltInThemes.all

    /**
     * Applies a theme to the launcher and to the container at once.
     *
     * Writing the container's files even when the session is not running is
     * deliberate: the next session start then already has the right colours.
     */
    public fun applyTheme(selected: OmarchyTheme) {
        app.settings.themeId = selected.id
        theme = selected
        if (app.paths.rootfs.isDirectory) {
            runCatching { ThemeWriter.apply(selected, app.paths.rootfs) }
                .onFailure { packageLog.add("Could not write theme files: ${it.message}") }
        }
    }

    // ---------------------------------------------------------------- actions

    private fun pacman(): Pacman = Pacman(app.containerManager.session())

    public fun searchPackages() {
        if (packageBusy || !isInstalled) return
        packageBusy = true
        searchResults.clear()
        runOnWorker("package search") {
            val results = pacman().search(packageQuery)
            searchResults.addAll(results.take(MAX_RESULTS))
            if (results.isEmpty()) {
                packageLog.add("No packages matched \"$packageQuery\"")
            }
            packageBusy = false
        }
    }

    public fun installPackages(names: List<String>) {
        if (packageBusy || !isInstalled) return
        packageBusy = true
        packageLog.add("Installing ${names.joinToString(", ")}")
        runOnWorker("package install") {
            val result = pacman().install(names) { line: LogLine -> appendPackageLog(line.text) }
            packageLog.add(
                if (result.isSuccess) "Installed ${names.joinToString(", ")}" else "Failed (exit ${result.exitCode})",
            )
            packageBusy = false
        }
    }

    public fun removePackages(names: List<String>) {
        if (packageBusy || !isInstalled) return
        packageBusy = true
        runOnWorker("package remove") {
            val result = pacman().remove(names) { line: LogLine -> appendPackageLog(line.text) }
            packageLog.add(if (result.isSuccess) "Removed ${names.joinToString(", ")}" else "Removal failed")
            packageBusy = false
        }
    }

    public fun upgradeSystem() {
        if (packageBusy || !isInstalled) return
        packageBusy = true
        packageLog.add("Upgrading every package; this can take a while.")
        runOnWorker("system upgrade") {
            val result = pacman().upgrade { line: LogLine -> appendPackageLog(line.text) }
            packageLog.add(if (result.isSuccess) "System upgraded" else "Upgrade failed (exit ${result.exitCode})")
            packageBusy = false
        }
    }

    public fun selectTemplate(template: BuildTemplate) {
        selectedTemplate = template
        buildName = template.title
        missingForTemplate = emptyList()
        if (!isInstalled) return
        runOnWorker("dependency check") {
            val builder = WidgetBuilder(app.paths, pacman())
            missingForTemplate = builder.missingPackages(template)
        }
    }

    /** Builds the selected template, installing whatever it needs first. */
    public fun build() {
        val template = selectedTemplate ?: return
        if (buildBusy || !isInstalled) return
        buildBusy = true
        buildLog.clear()
        runOnWorker("build ${template.id}") {
            val builder = WidgetBuilder(app.paths, pacman())
            val outcome = builder.build(template, buildName) { line: LogLine ->
                buildLog.add(line.text)
                if (buildLog.size > MAX_LOG) buildLog.removeAt(0)
            }
            lastBuild = outcome
            when (outcome) {
                is BuildOutcome.Built -> {
                    buildLog.add("Created ${outcome.files.size} files.")
                    // Quickshell reloads QML when files change, so a running
                    // session picks the widget up without a restart.
                    buildLog.add("Widgets appear on the home screen once Quickshell reloads.")
                }
                is BuildOutcome.Failed -> buildLog.add("Failed: ${outcome.reason}")
            }
            missingForTemplate = builder.missingPackages(template)
            buildBusy = false
        }
    }

    /** Builds Quickshell from source; the one genuinely long-running job. */
    public fun buildQuickshell() {
        if (packageBusy || !isInstalled) return
        packageBusy = true
        packageLog.add("Building Quickshell from source. Expect 20-40 minutes.")
        runOnWorker("quickshell build") {
            val session = app.containerManager.session()
            val result = session.shell(
                "${dev.myarch.runtime.MyarchPaths.GUEST_SCRIPTS_DIR}/build-quickshell.sh",
                timeoutSeconds = QUICKSHELL_TIMEOUT_SECONDS,
            ) { line: LogLine -> appendPackageLog(line.text) }
            packageLog.add(
                if (result.isSuccess) "Quickshell installed" else "Build failed (exit ${result.exitCode})",
            )
            packageBusy = false
        }
    }

    public fun startSession() {
        CompositorService.start(context)
    }

    public fun stopSession() {
        CompositorService.stop(context)
    }

    private fun appendPackageLog(line: String) {
        packageLog.add(line)
        if (packageLog.size > MAX_LOG) packageLog.removeAt(0)
    }

    /**
     * Runs [work] off the main thread.
     *
     * Compose snapshot state can be written from any thread, so the work can
     * update the UI directly without a dispatcher hop.
     */
    private fun runOnWorker(name: String, work: () -> Unit) {
        Thread({
            runCatching(work).onFailure { error ->
                packageLog.add("$name failed: ${error.message}")
                packageBusy = false
                buildBusy = false
            }
        }, "myarch-$name").apply {
            isDaemon = true
            start()
        }
    }

    private companion object {
        const val MAX_RESULTS = 200
        const val MAX_LOG = 400
        const val QUICKSHELL_TIMEOUT_SECONDS = 60L * 90L
    }
}
