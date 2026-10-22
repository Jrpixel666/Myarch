package com.apkforge.engine

import android.content.Context
import java.io.File

/**
 * Locates a native aapt2 (or aapt) binary that apktool can call when it has to
 * recompile resources during a rebuild.
 *
 * Android only lets a non-root app execute binaries out of its native library
 * directory, and only when they were packaged as `lib*.so`. So the way to ship
 * aapt2 is to drop the right-ABI binary into
 * `app/src/main/jniLibs/<abi>/libaapt2.so`; the installer extracts it to
 * [android.content.pm.ApplicationInfo.nativeLibraryDir] with the execute bit
 * set. We don't bundle one (it is ABI-specific and several MB) — this finds it
 * if the user added it. See the README.
 */
object Aapt2 {

    private val CANDIDATES = listOf("libaapt2.so", "libaapt.so")

    data class Found(val file: File, val isAapt2: Boolean)

    fun locate(context: Context): Found? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        for (name in CANDIDATES) {
            val f = File(nativeDir, name)
            if (f.exists() && f.canExecute()) {
                return Found(f, isAapt2 = name.contains("aapt2"))
            }
        }
        return null
    }
}
