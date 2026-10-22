package com.apkforge.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.apkforge.model.InstalledAppInfo
import com.apkforge.model.LoadedApk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Everything that touches the Android package layer: importing an APK into the
 * app's private workspace, reading its metadata, listing installed apps, and
 * installing / launching a package.
 *
 * The workspace lives in app-private storage so the engine can read, decode and
 * rebuild without any storage permission.
 */
class ApkRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    val workspaceDir: File = File(context.filesDir, "workspace").apply { mkdirs() }

    /** The APK currently loaded for inspection / editing. */
    val inputApk: File get() = File(workspaceDir, "input.apk")

    /** Copy a user-picked APK (content:// URI) into the workspace and read it. */
    suspend fun importFromUri(uri: Uri): LoadedApk = withContext(Dispatchers.IO) {
        val dest = inputApk
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Could not open the selected file.")
        readMetadata(dest, fallbackName = queryDisplayName(uri) ?: dest.name)
    }

    /** Copy an installed app's base APK into the workspace and read it. */
    suspend fun importInstalled(packageName: String): LoadedApk = withContext(Dispatchers.IO) {
        val info = pm.getApplicationInfo(packageName, 0)
        val source = File(info.sourceDir)
        source.copyTo(inputApk, overwrite = true)
        readMetadata(inputApk, fallbackName = "$packageName.apk")
    }

    /** Parse an APK file on disk into displayable metadata. */
    fun readMetadata(file: File, fallbackName: String = file.name): LoadedApk {
        val flags = PackageManager.GET_META_DATA
        val pkg = pm.getPackageArchiveInfo(file.absolutePath, flags)
        val appInfo: ApplicationInfo? = pkg?.applicationInfo?.apply {
            // Point the loaders at the archive so icon/label resolve from it.
            sourceDir = file.absolutePath
            publicSourceDir = file.absolutePath
        }

        val label = appInfo?.let { runCatching { pm.getApplicationLabel(it).toString() }.getOrNull() }
        val icon = appInfo?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() }
        val packageName = pkg?.packageName
        val minSdk = if (Build.VERSION.SDK_INT >= 24) appInfo?.minSdkVersion ?: 0 else 0

        return LoadedApk(
            file = file,
            displayName = fallbackName,
            label = label,
            packageName = packageName,
            versionName = pkg?.versionName,
            versionCode = pkg?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L,
            minSdk = minSdk,
            targetSdk = appInfo?.targetSdkVersion ?: 0,
            sizeBytes = file.length(),
            icon = icon,
            installed = packageName != null && isInstalled(packageName),
        )
    }

    fun isInstalled(packageName: String): Boolean = runCatching {
        pm.getPackageInfo(packageName, 0); true
    }.getOrDefault(false)

    /** Launchable, non-ApkForge apps, sorted by label. Used as a load source. */
    suspend fun listInstalledApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .asSequence()
            .filter { it.packageName != context.packageName }
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { info ->
                InstalledAppInfo(
                    label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName),
                    packageName = info.packageName,
                    sourceApk = info.sourceDir,
                    icon = runCatching { pm.getApplicationIcon(info) }.getOrNull(),
                    system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Hand an APK to the system installer. Returns false if it can't start. */
    fun install(file: File): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Whether the OS will let us trigger an install without first bouncing to settings. */
    fun canRequestInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || pm.canRequestPackageInstalls()

    /** Launch an installed package's main activity. Returns false if not launchable. */
    fun launch(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }.getOrNull()
}
