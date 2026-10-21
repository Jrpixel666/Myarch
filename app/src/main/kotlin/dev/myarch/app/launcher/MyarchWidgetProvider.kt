package dev.myarch.app.launcher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.myarch.app.MyarchApplication
import dev.myarch.app.R
import dev.myarch.app.ui.console.ConsoleActivity

/**
 * Myarch's own app widget: a shortcut into the Arch session.
 *
 * It exists so the container is still reachable when Myarch is *not* the active
 * home screen -- plenty of people will want to try this without replacing their
 * launcher, and an app that only works as the home screen would be a hard sell.
 */
public class MyarchWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val installed = MyarchApplication.of(context).paths.isInstalled
        val status = if (installed) {
            context.getString(R.string.compositor_running)
        } else {
            "Not installed yet"
        }
        val views = RemoteViews(context.packageName, R.layout.widget_myarch_shortcut).apply {
            setTextViewText(R.id.widget_status, status)
            setOnClickPendingIntent(R.id.widget_root, openConsole(context))
        }
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    private fun openConsole(context: Context): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, ConsoleActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    public companion object {
        /** Asks the system to refresh every instance of this widget. */
        public fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MyarchWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(component)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
