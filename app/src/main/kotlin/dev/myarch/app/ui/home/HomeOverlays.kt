package dev.myarch.app.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.myarch.app.data.HomeItem

/** The app drawer: Android and Linux applications, searchable together. */
@Composable
internal fun AppDrawer(state: HomeState) {
    var query by remember { mutableStateOf("") }
    val androidMatches = state.androidApps.filter { it.label.contains(query, ignoreCase = true) }
    val linuxMatches = state.linuxApps.filter {
        it.name.contains(query, ignoreCase = true) || it.exec.contains(query, ignoreCase = true)
    }

    OverlaySheet(title = "Applications", onClose = state::closeAllOverlays) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 86.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (linuxMatches.isNotEmpty()) {
                items(linuxMatches, key = { "linux-${it.id}" }) { entry ->
                    ShortcutCell(
                        label = entry.name,
                        icon = null,
                        fallbackColor = MaterialTheme.colorScheme.secondary,
                        onClick = { state.launch(entry) },
                        onLongClick = {
                            state.addItemToCurrentPage(
                                HomeItem.LinuxShortcut(entry.id, entry.name, entry.exec),
                            )
                            state.closeAllOverlays()
                        },
                    )
                }
            }
            items(androidMatches, key = { "android-${it.packageName}/${it.activityName}" }) { app ->
                ShortcutCell(
                    label = app.label,
                    icon = app.icon,
                    fallbackColor = MaterialTheme.colorScheme.primary,
                    onClick = { state.launch(app) },
                    onLongClick = {
                        // Long-press adds to the current page: the same gesture
                        // that removes an item from a page puts one on it.
                        state.addItemToCurrentPage(
                            HomeItem.AppShortcut(app.packageName, app.activityName, app.label),
                        )
                        state.closeAllOverlays()
                    },
                )
            }
        }
    }
}

/** Open Linux windows, each of which is also an Android task. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WindowSwitcher(state: HomeState) {
    OverlaySheet(title = "Linux windows", onClose = state::closeAllOverlays) {
        if (state.windows.isEmpty()) {
            Text(
                text = if (state.isContainerInstalled) {
                    "Nothing is open. Start an app from the drawer and its window " +
                        "will appear here and in Android's recents."
                } else {
                    "No container is installed yet."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            return@OverlaySheet
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.windows, key = { it.windowId }) { window ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { state.focus(window) },
                            onLongClick = { state.closeWindow(window) },
                        )
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = window.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(window.appId.ifEmpty { "unknown app" })
                            append("  ")
                            append(window.width)
                            append("x")
                            append(window.height)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            }
        }
        Text(
            text = "Tap to switch, long-press to close",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

/** What you can add to, or do with, the current page. */
@Composable
internal fun PageMenu(state: HomeState) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? HomeActivity
    OverlaySheet(title = "Add to this page", onClose = state::closeAllOverlays) {
        MenuRow(
            title = "Android widget",
            subtitle = "Pick from the widgets your installed apps provide",
        ) {
            state.closeAllOverlays()
            activity?.pickWidget()
        }
        MenuRow(
            title = "App shortcut",
            subtitle = "Long-press any app in the drawer to place it here",
        ) {
            state.show(HomeOverlay.APP_DRAWER)
        }
        MenuRow(
            title = "Omarchy widget",
            subtitle = "Build a Quickshell widget that draws on the home screen",
        ) {
            state.openConsoleAt("build")
        }
        MenuRow(
            title = "New page",
            subtitle = "Swipe right to reach it",
        ) {
            state.addPage()
            state.closeAllOverlays()
        }
        MenuRow(
            title = "Remove this page",
            subtitle = "Its widgets are released; shortcuts are only unpinned",
        ) {
            state.removeCurrentPage()
            state.closeAllOverlays()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        MenuRow(
            title = "Themes",
            subtitle = "Change the Omarchy palette for the launcher and the session",
        ) {
            state.openConsoleAt("themes")
        }
        MenuRow(
            title = "Restart the Arch session",
            subtitle = "Closes every Linux window and starts the shell again",
        ) {
            activity?.restartSession()
            state.closeAllOverlays()
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The shared chrome for the launcher's full-screen overlays. */
@Composable
private fun OverlaySheet(
    title: String,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
            // Swallow taps so they do not reach the page behind the sheet.
            .clickable(enabled = false, onClick = {}),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = RoundedCornerShape(topStart = 0.dp, topEnd = 0.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, top = 18.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                content()
            }
        }
    }
}
