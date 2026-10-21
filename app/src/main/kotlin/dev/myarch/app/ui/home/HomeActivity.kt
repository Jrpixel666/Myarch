package dev.myarch.app.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.myarch.app.MyarchApplication
import dev.myarch.app.compositor.CompositorConnection
import dev.myarch.app.compositor.CompositorService
import dev.myarch.app.data.HomeItem
import dev.myarch.app.data.HomeLayoutStore
import dev.myarch.app.launcher.WidgetHostController
import dev.myarch.app.ui.theme.MyarchTheme

/**
 * The launcher.
 *
 * A home activity is started by the system in unusual conditions (before the
 * user has unlocked the device, during boot), so it declares
 * `stateNotNeeded` and every configuration change it can handle itself: being
 * recreated while acting as the home screen is visible and jarring.
 */
public class HomeActivity : ComponentActivity() {

    private lateinit var widgetHost: WidgetHostController
    private lateinit var connection: CompositorConnection
    private lateinit var state: HomeState

    private var serviceRevision by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = MyarchApplication.of(this)
        widgetHost = WidgetHostController(this)
        state = HomeState(
            context = this,
            settings = app.settings,
            layoutStore = HomeLayoutStore(app.settings),
            paths = app.paths,
            widgetHost = widgetHost,
        )
        connection = CompositorConnection(
            context = this,
            onConnected = { service ->
                state.attach(service, lifecycleScope)
                serviceRevision++
            },
            onDisconnected = { state.detach() },
        )

        setContent {
            // Read the revision so the tree recomposes once the service binds.
            @Suppress("UNUSED_EXPRESSION")
            serviceRevision
            MyarchTheme(state.theme) {
                HomeScreen(state = state)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgetHost.start()
        connection.bind(startSession = MyarchApplication.of(this).settings.autoStartSession)
    }

    override fun onResume() {
        super.onResume()
        state.refreshApps()
    }

    override fun onStop() {
        widgetHost.stop()
        connection.unbind()
        state.detach()
        super.onStop()
    }

    @Deprecated("Back on a home screen closes overlays; there is nothing to navigate back to.")
    override fun onBackPressed() {
        // Never leave the launcher: close whatever is open instead, which is
        // what every Android home screen does.
        if (!state.dismissTopmostOverlay()) {
            state.closeAllOverlays()
        }
    }

    /**
     * Places the widget the user picked.
     *
     * Two results are involved: the picker's, and the provider's own
     * configuration activity when it has one. A cancel at either step releases
     * the reserved id, or the host would hold it until the app is reinstalled.
     */
    private val pickWidgetLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) {
                releaseReservedWidget()
                return@registerForActivityResult
            }
            when (val outcome = widgetHost.onPickResult(result.data)) {
                is WidgetHostController.PickOutcome.Ready -> addWidget(outcome.appWidgetId)
                is WidgetHostController.PickOutcome.NeedsConfiguration -> {
                    reservedWidgetId = outcome.appWidgetId
                    runCatching { configureWidgetLauncher.launch(outcome.intent) }
                        .onFailure {
                            // A provider whose configuration activity cannot be
                            // started is still usable with its defaults.
                            addWidget(outcome.appWidgetId)
                        }
                }
                null -> releaseReservedWidget()
            }
        }

    private val configureWidgetLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val id = reservedWidgetId
            reservedWidgetId = null
            if (result.resultCode == RESULT_OK && id != null) {
                addWidget(id)
            } else if (id != null) {
                widgetHost.deleteId(id)
            }
        }

    private var reservedWidgetId: Int? = null

    private fun releaseReservedWidget() {
        reservedWidgetId?.let(widgetHost::deleteId)
        reservedWidgetId = null
    }

    /** Puts a bound widget on the page the user is looking at. */
    private fun addWidget(appWidgetId: Int) {
        reservedWidgetId = null
        val (width, height) = widgetHost.defaultSizeDp(appWidgetId)
        state.addItemToCurrentPage(HomeItem.Widget(appWidgetId, width, height))
    }

    /** Opens the system widget picker for the current page. */
    public fun pickWidget() {
        val (intent, reservedId) = widgetHost.pickWidgetIntent()
        reservedWidgetId = reservedId
        runCatching { pickWidgetLauncher.launch(intent) }
            .onFailure {
                // Some devices ship no widget picker at all.
                releaseReservedWidget()
            }
    }

    /** Starts or restarts the Linux session. */
    public fun restartSession() {
        startService(
            Intent(this, CompositorService::class.java).setAction(CompositorService.ACTION_RESTART),
        )
    }
}
