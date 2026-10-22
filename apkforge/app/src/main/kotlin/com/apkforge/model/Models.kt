package com.apkforge.model

import android.graphics.drawable.Drawable
import java.io.File

/** Metadata about the APK the user has loaded into the workspace. */
data class LoadedApk(
    val file: File,
    val displayName: String,
    val label: String?,
    val packageName: String?,
    val versionName: String?,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val sizeBytes: Long,
    val icon: Drawable?,
    /** True when a package with [packageName] is already installed on-device. */
    val installed: Boolean,
)

/** An installed app offered as a load source on the home screen. */
data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val sourceApk: String,
    val icon: Drawable?,
    val system: Boolean,
)

/** How much of the APK apktool should take apart. */
enum class DecodeMode(val label: String, val detail: String) {
    FULL(
        "Full decode",
        "Resources become editable XML. Rebuilding needs a native aapt2 (see README).",
    ),
    SOURCES_ONLY(
        "Sources only",
        "Smali is editable; resources stay as-is. Rebuilds on-device without aapt2.",
    ),
}

/** A file or directory inside the decoded apktool project, for the tree view. */
data class FileNode(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val depth: Int,
) {
    val sizeBytes: Long get() = if (isDirectory) 0 else file.length()
}
