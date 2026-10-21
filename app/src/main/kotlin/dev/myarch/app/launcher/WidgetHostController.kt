package dev.myarch.app.launcher

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.util.Log

/**
 * Hosts Android app widgets on Myarch's home screen pages.
 *
 * A launcher that cannot show the user's existing widgets is not really a
 * launcher, so Myarch hosts both: Android widgets through `AppWidgetHost`, and
 * Omarchy's Quickshell widgets through the layer-shell surfaces the compositor
 * already receives. They coexist on the same page.
 */
public class WidgetHostController(private val context: Context) {

    private val host = AppWidgetHost(context, HOST_ID)
    private val manager = AppWidgetManager.getInstance(context)

    /** Starts listening for widget updates. Call from `onStart`. */
    public fun start() {
        runCatching { host.startListening() }
            .onFailure { Log.w(TAG, "could not start the widget host", it) }
    }

    /** Stops listening. Call from `onStop`. */
    public fun stop() {
        runCatching { host.stopListening() }
    }

    /** Allocates an id for a new widget; the user then picks a provider. */
    public fun allocateId(): Int = host.allocateAppWidgetId()

    public fun deleteId(appWidgetId: Int) {
        runCatching { host.deleteAppWidgetId(appWidgetId) }
    }

    /** Builds the view for an already-bound widget, or `null` if it is gone. */
    public fun createView(appWidgetId: Int): AppWidgetHostView? {
        val info: AppWidgetProviderInfo = manager.getAppWidgetInfo(appWidgetId) ?: return null
        return runCatching { host.createView(context, appWidgetId, info) }
            .onFailure { Log.w(TAG, "widget $appWidgetId failed to inflate", it) }
            .getOrNull()
    }

    /**
     * Builds the intent that opens the system widget picker.
     *
     * The picker is the system's, not ours: a launcher may not bind an
     * arbitrary widget without the user choosing it, and reimplementing the
     * picker would need `BIND_APPWIDGET`, which only a system-signed app can
     * hold. The caller launches this through the Activity Result API and hands
     * the result back to [onPickResult].
     *
     * @return the intent, and the widget id reserved for it
     */
    public fun pickWidgetIntent(): Pair<Intent, Int> {
        val appWidgetId = allocateId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Empty extras tell the picker we contribute no custom entries of
            // our own; without them some OEM pickers refuse to open.
            putExtra(AppWidgetManager.EXTRA_CUSTOM_INFO, ArrayList<AppWidgetProviderInfo>())
            putExtra(AppWidgetManager.EXTRA_CUSTOM_EXTRAS, ArrayList<Bundle>())
        }
        return intent to appWidgetId
    }

    /**
     * Inspects a picker result.
     *
     * @return [PickOutcome.Ready] when the widget can be placed immediately,
     *   [PickOutcome.NeedsConfiguration] when the provider has its own setup
     *   activity to run first, or `null` when nothing usable came back
     */
    public fun onPickResult(data: Intent?): PickOutcome? {
        val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
        if (appWidgetId == -1) return null
        val info = manager.getAppWidgetInfo(appWidgetId)
        val configure = info?.configure
        return if (configure != null) {
            PickOutcome.NeedsConfiguration(
                appWidgetId = appWidgetId,
                intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                    component = configure
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                },
            )
        } else {
            PickOutcome.Ready(appWidgetId)
        }
    }

    /** Default size hint for a freshly added widget, in dp. */
    public fun defaultSizeDp(appWidgetId: Int): Pair<Int, Int> {
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return DEFAULT_SIZE_DP to DEFAULT_SIZE_DP
        val density = context.resources.displayMetrics.density
        val width = (info.minWidth / density).toInt().coerceAtLeast(MIN_SIZE_DP)
        val height = (info.minHeight / density).toInt().coerceAtLeast(MIN_SIZE_DP)
        return width to height
    }

    /** Tells a hosted widget what size it has, so it can pick a layout. */
    @Suppress("DEPRECATION")
    public fun updateSize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12 widgets choose a layout from the exact sizes offered.
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
        } else {
            view.updateAppWidgetSize(null, widthDp, heightDp, widthDp, heightDp)
        }
    }

    /** What to do after the system picker returns. */
    public sealed interface PickOutcome {
        /** The widget is bound and can be placed on a page. */
        public data class Ready(val appWidgetId: Int) : PickOutcome

        /** The provider wants to run its own configuration activity first. */
        public data class NeedsConfiguration(val appWidgetId: Int, val intent: Intent) : PickOutcome
    }

    private companion object {
        const val TAG = "MyarchWidgets"

        /** Any constant works; it only has to be stable across restarts. */
        const val HOST_ID = 0x4D59 // "MY"
        const val DEFAULT_SIZE_DP = 180
        const val MIN_SIZE_DP = 60
    }
}
