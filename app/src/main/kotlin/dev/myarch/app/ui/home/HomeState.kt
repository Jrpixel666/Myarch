package dev.myarch.app.ui.home

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.myarch.app.compositor.CompositorService
import dev.myarch.app.compositor.PanelSnapshot
import dev.myarch.app.compositor.SessionState
import dev.myarch.app.data.HomeItem
import dev.myarch.app.data.HomeLayout
import dev.myarch.app.data.HomeLayoutStore
import dev.myarch.app.data.MyarchSettings
import dev.myarch.app.launcher.AndroidApp
import dev.myarch.app.launcher.AndroidApps
import dev.myarch.app.launcher.WidgetHostController
import dev.myarch.app.ui.window.LinuxWindowActivity
import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.apps.DesktopEntries
import dev.myarch.runtime.apps.DesktopEntry
import dev.myarch.runtime.theme.BuiltInThemes
import dev.myarch.runtime.theme.OmarchyTheme
import dev.myarch.wayland.core.WindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Which full-screen overlay is showing, if any. */
public enum class HomeOverlay { NONE, APP_DRAWER, WINDOW_SWITCHER, PAGE_MENU }

/**
 * The launcher's observable state.
 *
 * A plain class rather than a `ViewModel`: `HomeActivity` is a long-lived
 * singleton task that is never recreated for configuration changes (it declares
 * them all in the manifest), so there is no state to survive beyond it.
 */
public class HomeState(
    private val context: Context,
    private val settings: MyarchSettings,
    private val layoutStore: HomeLayoutStore,
    private val paths: MyarchPaths,
    private val widgetHost: WidgetHostController,
) {
    public var layout: HomeLayout by mutableStateOf(layoutStore.load())
        private set

    public var currentPage: Int by mutableIntStateOf(0)

    public var overlay: HomeOverlay by mutableStateOf(HomeOverlay.NONE)
        private set

    public var theme: OmarchyTheme by mutableStateOf(
        BuiltInThemes.byId(settings.themeId) ?: BuiltInThemes.default,
    )
        private set

    /** Installed Android apps, for the drawer. */
    public val androidApps: MutableList<AndroidApp> = mutableStateListOf()

    /** Installed Linux apps, read from the container's desktop entries. */
    public val linuxApps: MutableList<DesktopEntry> = mutableStateListOf()

    /** Open Linux windows, for the switcher. */
    public val windows: MutableList<WindowInfo> = mutableStateListOf()

    /** Quickshell panels and widgets, drawn over the page. */
    public val panels: MutableList<PanelSnapshot> = mutableStateListOf()

    public var sessionState: SessionState by mutableStateOf(SessionState.Stopped)
        private set

    public var service: CompositorService? by mutableStateOf(null)
        private set

    /** Bumped whenever panel pixels change, to drive recomposition. */
    public var frameRevision: Int by mutableIntStateOf(0)
        private set

    private var unobserve: (() -> Unit)? = null
    private var stateCollector: Job? = null

    public val isContainerInstalled: Boolean get() = paths.isInstalled

    // ------------------------------------------------------------- lifecycle

    /**
     * Starts following a bound service.
     *
     * @param scope the activity's lifecycle scope; the session state is a flow
     *   and has to be collected for the home screen's banner to stay true
     */
    public fun attach(service: CompositorService, scope: CoroutineScope) {
        detach()
        this.service = service
        stateCollector = scope.launch {
            service.state.collect { newState -> sessionState = newState }
        }
        unobserve = service.surfaces.observeAll {
            // Called from the compositor thread; Compose snapshot state is
            // safe to write from any thread.
            windows.clear()
            windows.addAll(service.surfaces.windowInfos())
            panels.clear()
            panels.addAll(service.surfaces.panelSnapshots())
            frameRevision++
        }
        // Seed the lists, since the first change notification may be a while
        // away and the window switcher should be right immediately.
        windows.clear()
        windows.addAll(service.surfaces.windowInfos())
        panels.clear()
        panels.addAll(service.surfaces.panelSnapshots())
    }

    public fun detach() {
        unobserve?.invoke()
        unobserve = null
        // The activity's scope outlives one bind/unbind cycle, so the collector
        // has to be cancelled explicitly or each rebind adds another.
        stateCollector?.cancel()
        stateCollector = null
        service = null
    }

    // ----------------------------------------------------------------- apps

    public fun refreshApps() {
        theme = BuiltInThemes.byId(settings.themeId) ?: BuiltInThemes.default
        androidApps.clear()
        androidApps.addAll(AndroidApps.load(context))
        linuxApps.clear()
        if (settings.showLinuxApps && paths.isInstalled) {
            linuxApps.addAll(DesktopEntries.scan(paths.rootfs))
        }
    }

    public fun launch(app: AndroidApp) {
        closeAllOverlays()
        runCatching { context.startActivity(app.launchIntent()) }
    }

    /**
     * Runs a Linux application.
     *
     * Its window arrives back through the compositor a moment later and gets
     * its own Android task, so nothing here has to wait for it.
     */
    public fun launch(entry: DesktopEntry) {
        closeAllOverlays()
        val manager = dev.myarch.app.MyarchApplication.of(context).containerManager
        val command = if (entry.terminal) "foot -e ${entry.exec}" else entry.exec
        runCatching { manager.launchApplication(command) }
    }

    public fun focus(window: WindowInfo) {
        closeAllOverlays()
        LinuxWindowActivity.focus(context, window)
    }

    public fun closeWindow(window: WindowInfo) {
        service?.compositor?.closeWindow(window.windowId)
    }

    // ---------------------------------------------------------------- pages

    public fun addItemToCurrentPage(item: HomeItem) {
        val page = layout.pages.getOrNull(currentPage) ?: return
        update(layout.withItem(page.id, item))
    }

    public fun removeItem(pageId: String, key: String) {
        val item = layout.pages.firstOrNull { it.id == pageId }?.items?.firstOrNull { it.key == key }
        if (item is HomeItem.Widget) {
            // Let the host release the widget, or it stays bound forever.
            widgetHost.deleteId(item.appWidgetId)
        }
        update(layout.withoutItem(pageId, key))
    }

    public fun addPage() {
        update(layout.withNewPage("Page ${layout.pages.size + 1}"))
        currentPage = layout.pages.lastIndex
    }

    public fun removeCurrentPage() {
        val page = layout.pages.getOrNull(currentPage) ?: return
        for (item in page.items) {
            if (item is HomeItem.Widget) widgetHost.deleteId(item.appWidgetId)
        }
        update(layout.withoutPage(page.id))
        currentPage = currentPage.coerceAtMost(layout.pages.lastIndex)
    }

    private fun update(newLayout: HomeLayout) {
        layout = newLayout
        layoutStore.save(newLayout)
    }

    // ------------------------------------------------------------- overlays

    public fun show(target: HomeOverlay) {
        overlay = target
    }

    /** Closes the topmost thing; returns false when nothing was open. */
    public fun dismissTopmostOverlay(): Boolean {
        // A Linux popup (a menu in a Quickshell widget) is "on top" of the
        // launcher's own overlays as far as the user is concerned.
        if (service?.compositor?.dismissPopups() == true) return true
        if (overlay != HomeOverlay.NONE) {
            overlay = HomeOverlay.NONE
            return true
        }
        return false
    }

    public fun closeAllOverlays() {
        overlay = HomeOverlay.NONE
    }

    public fun openConsole() {
        closeAllOverlays()
        context.startActivity(
            Intent(context, dev.myarch.app.ui.console.ConsoleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    public fun openConsoleAt(route: String) {
        closeAllOverlays()
        context.startActivity(
            Intent(context, dev.myarch.app.ui.console.ConsoleActivity::class.java)
                .putExtra(dev.myarch.app.ui.console.ConsoleActivity.EXTRA_ROUTE, route)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hosts an Android widget view for a page item. */
    public fun widgetView(item: HomeItem.Widget): android.view.View? = widgetHost.createView(item.appWidgetId)
}
