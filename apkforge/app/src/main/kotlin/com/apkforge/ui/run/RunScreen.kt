package com.apkforge.ui.run

import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import com.apkforge.viewmodel.ForgeViewModel

@Composable
fun RunScreen(vm: ForgeViewModel) {
    val apk = vm.loadedApk

    // Re-check install state whenever we come back (e.g. after the installer).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshInstalledState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Run", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))

        DeviceFrame {
            if (apk == null) {
                ScreenMessage("No APK loaded.\nLoad one from the Load tab.")
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AppGlyph(apk.icon)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        apk.label ?: apk.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        apk.packageName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF9AA7B4),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (apk.installed) "Installed · ready to launch"
                        else "Not installed yet",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (apk.installed) Color(0xFF7CF2C8) else Color(0xFFFFC46B),
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (apk != null) {
            if (apk.installed) {
                Button(
                    onClick = { vm.launchLoaded() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.PlayArrow, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Launch app")
                }
            } else {
                Button(
                    onClick = { vm.installLoaded() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Install APK")
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.refreshInstalledState() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.size(8.dp))
                Text("Refresh state")
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "The window above is ApkForge's own frame. Android does not let one " +
                "app render another app's UI inside itself, so “Launch” opens the " +
                "app full-screen as its own task — the honest equivalent of running it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (apk != null && !apk.installed && !vm.canRequestInstalls) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Tip: Android will ask you to allow ApkForge to install unknown apps the first time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A phone-shaped bezel with a notch, holding whatever "screen" content. */
@Composable
private fun DeviceFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth(0.72f)
            .aspectRatio(0.49f)
            .clip(RoundedCornerShape(34.dp))
            .background(Color(0xFF05080B))
            .border(3.dp, Color(0xFF263241), RoundedCornerShape(34.dp))
            .padding(10.dp),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF11161C)),
            contentAlignment = Alignment.Center,
        ) {
            content()
            // Notch.
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 6.dp)
                    .size(width = 56.dp, height = 14.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF05080B)),
            )
        }
    }
}

@Composable
private fun ScreenMessage(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF9AA7B4),
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(24.dp),
    )
}

@Composable
private fun AppGlyph(drawable: Drawable?) {
    val bmp = remember(drawable) {
        drawable?.let { runCatching { it.toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    Box(
        Modifier.size(64.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1E2630)),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(64.dp))
        } else {
            Icon(Icons.Filled.Android, null, tint = Color(0xFF7CF2C8), modifier = Modifier.size(40.dp))
        }
    }
}
