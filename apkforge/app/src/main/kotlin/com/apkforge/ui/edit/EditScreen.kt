package com.apkforge.ui.edit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Transform
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apkforge.model.DecodeMode
import com.apkforge.model.FileNode
import com.apkforge.ui.BusyBar
import com.apkforge.ui.humanSize
import com.apkforge.viewmodel.ForgeViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(vm: ForgeViewModel, onOpenFile: (File) -> Unit) {
    var tab by remember { mutableStateOf(EditTab.FILES) }
    val decoded = vm.decodedDir != null

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Edit with apktool", style = MaterialTheme.typography.headlineSmall)

        if (vm.loadedApk == null) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Load an APK from the Load tab first.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        Spacer(Modifier.height(12.dp))

        // Decode mode.
        Text("Decode mode", style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DecodeMode.entries.forEach { mode ->
                FilterChip(
                    selected = vm.decodeMode == mode,
                    onClick = { vm.decodeMode = mode },
                    label = { Text(mode.label) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            vm.decodeMode.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.decode() }, enabled = !vm.busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Transform, null)
                Spacer(Modifier.size(6.dp))
                Text("Decode")
            }
            Button(
                onClick = { vm.buildAndSign() },
                enabled = !vm.busy && decoded,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Build, null)
                Spacer(Modifier.size(6.dp))
                Text("Rebuild & sign")
            }
        }
        if (vm.lastSignedApk != null) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { vm.lastSignedApk?.let { vm.installFile(it) } },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Download, null)
                Spacer(Modifier.size(6.dp))
                Text("Install rebuilt APK")
            }
        }

        BusyBar(vm.busy, vm.busyLabel)
        Spacer(Modifier.height(8.dp))

        // Files / Search / Log switch.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            EditTab.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = tab == entry,
                    onClick = { tab = entry },
                    shape = SegmentedButtonDefaults.itemShape(index, EditTab.entries.size),
                ) {
                    Text(
                        when (entry) {
                            EditTab.FILES -> "Files"
                            EditTab.SEARCH -> "Search"
                            EditTab.LOG -> "Log (${vm.log.size})"
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            when (tab) {
                EditTab.FILES -> FileTree(vm, onOpenFile)
                EditTab.SEARCH -> SearchPanel(vm, onOpenFile)
                EditTab.LOG -> LogView(vm)
            }
        }
    }
}

private enum class EditTab { FILES, SEARCH, LOG }

@Composable
private fun FileTree(vm: ForgeViewModel, onOpenFile: (File) -> Unit) {
    val nodes = vm.visibleNodes()
    if (nodes.isEmpty()) {
        Text(
            "Nothing decoded yet. Press Decode to run apktool.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(nodes, key = { it.file.absolutePath }) { node ->
            TreeRow(
                node = node,
                expanded = vm.isExpanded(node),
                onClick = {
                    if (node.isDirectory) {
                        vm.toggle(node)
                    } else if (vm.isProbablyText(node.file)) {
                        onOpenFile(node.file)
                    } else {
                        vm.message = "${node.name} is binary — open a text file (XML, smali, …)."
                    }
                },
            )
        }
    }
}

@Composable
private fun TreeRow(node: FileNode, expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (8 + node.depth * 16).dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                node.isDirectory && expanded -> Icons.Filled.FolderOpen
                node.isDirectory -> Icons.Filled.Folder
                else -> Icons.Filled.Description
            },
            contentDescription = null,
            tint = if (node.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            node.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!node.isDirectory) {
            Text(
                humanSize(node.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SearchPanel(vm: ForgeViewModel, onOpenFile: (File) -> Unit) {
    if (vm.decodedDir == null) {
        Text(
            "Decode an APK first, then search its files.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search decoded project") },
            trailingIcon = {
                IconButton(onClick = { vm.runSearch() }) {
                    Icon(Icons.Filled.Search, "Search")
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.runSearch() }),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !vm.searchContents,
                onClick = { vm.searchContents = false },
                label = { Text("Names") },
            )
            FilterChip(
                selected = vm.searchContents,
                onClick = { vm.searchContents = true },
                label = { Text("Names + contents") },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (vm.searching) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (vm.searchResults.isNotEmpty() || vm.searchQuery.isNotBlank()) {
            Text(
                buildString {
                    append("${vm.searchResults.size} result")
                    if (vm.searchResults.size != 1) append("s")
                    if (vm.searchTruncated) append(" (capped)")
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.searchResults, key = { "${it.relativePath}:${it.line ?: -1}" }) { hit ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onOpenFile(hit.file) }
                        .padding(vertical = 6.dp),
                ) {
                    Text(
                        hit.relativePath + (hit.line?.let { ":$it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (hit.line != null) hit.preview else "matches file name",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun LogView(vm: ForgeViewModel) {
    if (vm.log.isEmpty()) {
        Text("No output yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val state = rememberLazyListState()
    LazyColumn(Modifier.fillMaxSize(), state = state) {
        items(vm.log) { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
            )
        }
    }
}
