package dev.myarch.app.ui.console

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.myarch.app.MyarchApplication
import dev.myarch.app.compositor.CompositorConnection
import dev.myarch.app.compositor.CompositorService
import dev.myarch.app.ui.theme.MyarchTheme

/** The screens the console can show. */
public enum class ConsoleRoute(public val title: String) {
    OVERVIEW("Myarch"),
    INSTALL("Install"),
    PACKAGES("Packages"),
    BUILD("Build"),
    THEMES("Themes"),
    DIAGNOSTICS("Diagnostics"),
    SETTINGS("Settings"),
    ;

    public companion object {
        public fun fromKey(key: String?): ConsoleRoute = when (key) {
            "install" -> INSTALL
            "packages" -> PACKAGES
            "build" -> BUILD
            "themes" -> THEMES
            "diagnostics" -> DIAGNOSTICS
            "settings" -> SETTINGS
            else -> OVERVIEW
        }
    }
}

/**
 * Everything that is not the home screen: the installer, the package browser,
 * the widget and application builder, themes, diagnostics and settings.
 *
 * It is a separate activity from the launcher so that it appears in recents as
 * its own task and can be opened from another launcher -- Myarch has to be
 * usable before the user makes it their home screen.
 */
public class ConsoleActivity : ComponentActivity() {

    private lateinit var connection: CompositorConnection
    private var service by mutableStateOf<CompositorService?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = MyarchApplication.of(this)
        val installController = InstallController(app.paths, app.containerManager, app.settings)
        val state = ConsoleState(
            context = this,
            app = app,
            installer = installController,
            abis = supportedAbis(),
        )
        state.route = ConsoleRoute.fromKey(intent.getStringExtra(EXTRA_ROUTE))

        connection = CompositorConnection(
            context = this,
            onConnected = { bound ->
                service = bound
                state.attach(bound)
            },
            onDisconnected = { service = null },
        )

        setContent {
            MyarchTheme(state.theme) {
                ConsoleScreen(state = state)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind without starting a session: opening the console should not boot
        // the container behind the user's back.
        connection.bind(startSession = false)
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }

    @Suppress("DEPRECATION")
    private fun supportedAbis(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOfNotNull(Build.CPU_ABI, Build.CPU_ABI2)
        }

    public companion object {
        /** Opens the console directly on one screen; see [ConsoleRoute.fromKey]. */
        public const val EXTRA_ROUTE: String = "dev.myarch.extra.ROUTE"
    }
}
