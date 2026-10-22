package com.apkforge.ui.home

import android.graphics.drawable.Drawable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.apkforge.model.LoadedApk
import com.apkforge.ui.BusyBar
import com.apkforge.ui.SectionLabel
import com.apkforge.ui.humanSize
import com.apkforge.viewmodel.ForgeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: ForgeViewModel, onLoaded: () -> Unit) {
    var showApps by remember { mutableStateOf(false) }
    var appFilter by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { vm.importFromUri(it) } }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("ApkForge", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Load an APK, run it, and take it apart with apktool.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { SectionLabel("Load an APK") }
        item {
            Button(
                onClick = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FolderOpen, null)
                Spacer(Modifier.size(8.dp))
                Text("Open APK file")
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    showApps = !showApps
                    if (showApps && vm.installedApps.isEmpty()) vm.refreshInstalledApps()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Smartphone, null)
                Spacer(Modifier.size(8.dp))
                Text(if (showApps) "Hide installed apps" else "Pick an installed app")
            }
        }

        item { BusyBar(vm.busy, vm.busyLabel) }

        if (showApps) {
            val query = appFilter.trim()
            val filtered = if (query.isEmpty()) vm.installedApps else vm.installedApps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            item { SectionLabel("Installed apps (${filtered.size}/${vm.installedApps.size})") }
            item {
                OutlinedTextField(
                    value = appFilter,
                    onValueChange = { appFilter = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Filter by name or package") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
            }
            items(filtered, key = { it.packageName }) { app ->
                Card(
                    onClick = { vm.importInstalled(app.packageName); showApps = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppIcon(app.icon, 36.dp)
                        Column(Modifier.weight(1f)) {
                            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                app.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (app.system) Text("system", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        vm.loadedApk?.let { apk ->
            item { SectionLabel("Loaded") }
            item { LoadedCard(apk, onRun = onLoaded) }
        }
    }
}

@Composable
private fun LoadedCard(apk: LoadedApk, onRun: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AppIcon(apk.icon, 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        apk.label ?: apk.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    Text(
                        apk.packageName ?: "(no package name)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Meta("Version", "${apk.versionName ?: "?"} (${apk.versionCode})")
            Meta("SDK", "min ${apk.minSdk} · target ${apk.targetSdk}")
            Meta("Size", humanSize(apk.sizeBytes))
            Meta("Installed", if (apk.installed) "yes" else "no")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRun, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Run ▸")
            }
        }
    }
}

@Composable
private fun Meta(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AppIcon(drawable: Drawable?, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        if (drawable != null) {
            val bmp = remember(drawable) {
                runCatching { drawable.toBitmap(96, 96).asImageBitmap() }.getOrNull()
            }
            if (bmp != null) {
                Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(size))
            } else {
                Icon(Icons.Filled.Android, null, modifier = Modifier.size(size))
            }
        } else {
            Icon(Icons.Filled.Android, null, modifier = Modifier.size(size))
        }
    }
}
