package dev.myarch.app

import android.content.Context
import android.util.Log
import dev.myarch.runtime.MyarchPaths
import java.io.File
import java.io.IOException

/**
 * Unpacks the guest-side files from the APK's assets onto disk.
 *
 * The scripts and the Quickshell configuration have to be real files for proot
 * to bind-mount them into the container, and assets inside an APK are not. They
 * are re-staged whenever the app version changes, so an update ships new
 * scripts without the user reinstalling the container.
 */
internal class AssetStaging(
    private val context: Context,
    private val paths: MyarchPaths,
) {

    fun stageIfNeeded() {
        val stamp = File(paths.scripts, ".staged-version")
        val current = BuildConfig.VERSION_NAME + "-" + BuildConfig.VERSION_CODE
        if (stamp.isFile && stamp.readText().trim() == current) return
        try {
            stage()
            stamp.writeText(current)
            Log.i(TAG, "staged container assets for $current")
        } catch (io: IOException) {
            // The app still works: the installer reports the missing scripts
            // rather than failing here, where there is no UI to tell.
            Log.e(TAG, "could not stage container assets", io)
        }
    }

    private fun stage() {
        paths.scripts.mkdirs()
        copyAssetDirectory("container/scripts", paths.scripts, executable = true)
        copyAssetDirectory("container/quickshell", File(paths.scripts, "quickshell"), executable = false)
        copyAssetDirectory("container/keymap", File(paths.scripts, "keymap"), executable = false)
    }

    private fun copyAssetDirectory(assetPath: String, destination: File, executable: Boolean) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            copyAssetFile(assetPath, destination)
            return
        }
        destination.mkdirs()
        for (child in children) {
            copyAssetDirectory("$assetPath/$child", File(destination, child), executable)
        }
        if (executable) {
            destination.listFiles()?.forEach { file ->
                if (file.isFile) file.setExecutable(true, false)
            }
        }
    }

    private fun copyAssetFile(assetPath: String, destination: File) {
        destination.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private companion object {
        const val TAG = "MyarchAssets"
    }
}
