package dev.myarch.app.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable

/** An installed Android application the drawer can launch. */
public data class AndroidApp(
    val packageName: String,
    val activityName: String,
    val label: String,
    val icon: Drawable?,
) {
    /** The intent that launches it. */
    public fun launchIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
        setClassName(packageName, activityName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/**
 * Lists the Android apps for the drawer.
 *
 * Icons are loaded eagerly, which is what keeps scrolling smooth; the list is
 * a few hundred entries at most and is refreshed only when packages change.
 */
public object AndroidApps {

    @Suppress("DEPRECATION")
    public fun load(context: Context): List<AndroidApp> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)
        return resolved.asSequence()
            // Myarch itself is the home screen; listing it in its own drawer
            // would just be a way to restart the launcher.
            .filter { it.activityInfo.packageName != context.packageName }
            .map { info ->
                AndroidApp(
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    label = info.loadLabel(packageManager).toString(),
                    icon = runCatching { info.loadIcon(packageManager) }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** True when the app can still be launched; packages do get uninstalled. */
    public fun exists(context: Context, app: AndroidApp): Boolean = runCatching {
        context.packageManager.getActivityInfo(
            android.content.ComponentName(app.packageName, app.activityName),
            PackageManager.GET_META_DATA,
        )
        true
    }.getOrDefault(false)
}
