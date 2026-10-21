package dev.myarch.app.ui.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.myarch.app.compositor.SessionState
import dev.myarch.app.data.HomeItem
import dev.myarch.app.data.HomePage
import dev.myarch.wayland.protocol.ZwlrLayerShellV1

/**
 * The home screen: swipeable pages, Omarchy panels on top, a dock at the
 * bottom.
 *
 * The layering is the whole idea. The launcher's own pages hold Android
 * shortcuts and widgets; the compositor's layer-shell surfaces (the Quickshell
 * bar, Omarchy widgets) are drawn over them; and open Linux windows live in
 * their own Android tasks, reachable from the dock's switcher.
 */
@Composable
public fun HomeScreen(state: HomeState) {
    val pagerState = rememberPagerState(
        initialPage = state.currentPage.coerceIn(0, maxOf(0, state.layout.pages.lastIndex)),
        pageCount = { state.layout.pages.size },
    )

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page -> state.currentPage = page }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Background and bottom layer-shell surfaces go behind the icon grid:
        // that is where a Quickshell wallpaper or desktop widget belongs.
        PanelLayer(
            state = state,
            modifier = Modifier.fillMaxSize(),
            layers = ZwlrLayerShellV1.Layer.BACKGROUND..ZwlrLayerShellV1.Layer.BOTTOM,
        )

        // Pages sit above the background layers.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            val page = state.layout.pages.getOrNull(pageIndex)
            if (page != null) {
                HomePageContent(state = state, page = page)
            }
        }

        // Bars and widget panels are drawn over the pages.
        PanelLayer(
            state = state,
            modifier = Modifier.fillMaxSize(),
            layers = ZwlrLayerShellV1.Layer.TOP..ZwlrLayerShellV1.Layer.OVERLAY,
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SessionBanner(state)
            PageIndicator(
                count = state.layout.pages.size,
                current = pagerState.currentPage,
            )
            Spacer(Modifier.height(8.dp))
            Dock(state)
            Spacer(Modifier.height(18.dp))
        }

        when (state.overlay) {
            HomeOverlay.APP_DRAWER -> AppDrawer(state)
            HomeOverlay.WINDOW_SWITCHER -> WindowSwitcher(state)
            HomeOverlay.PAGE_MENU -> PageMenu(state)
            HomeOverlay.NONE -> Unit
        }
    }
}

/** One page: a grid of shortcuts and hosted Android widgets. */
@Composable
private fun HomePageContent(state: HomeState, page: HomePage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Leave room for the Quickshell bar at the top and the dock below.
            .padding(top = 64.dp, bottom = 120.dp, start = 12.dp, end = 12.dp),
    ) {
        Text(
            text = page.title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
        )

        if (page.items.isEmpty()) {
            EmptyPageHint(state)
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 84.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(page.items, key = { it.key }) { item ->
                HomeItemCell(
                    state = state,
                    pageId = page.id,
                    item = item,
                )
            }
        }
    }
}

@Composable
private fun EmptyPageHint(state: HomeState) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "This page is empty",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Add Android apps, Android widgets or Omarchy widgets",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DockButton(
                    icon = Icons.Filled.Add,
                    label = "Add",
                    onClick = { state.show(HomeOverlay.PAGE_MENU) },
                )
                DockButton(
                    icon = Icons.Filled.Dashboard,
                    label = "Build",
                    onClick = { state.openConsoleAt("build") },
                )
            }
        }
    }
}

/** Tells the user what the session is doing when it is not simply running. */
@Composable
private fun SessionBanner(state: HomeState) {
    val message = when (val session = state.sessionState) {
        is SessionState.Failed -> session.reason
        SessionState.Starting -> "Starting the Arch session..."
        SessionState.Stopped -> if (state.isContainerInstalled) {
            "Arch session stopped — tap to start"
        } else {
            "No container installed yet — tap to set up Myarch"
        }
        is SessionState.Running -> null
    } ?: return

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { index ->
            Box(
                modifier = Modifier
                    .size(if (index == current) 8.dp else 6.dp)
                    .background(
                        color = if (index == current) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        shape = CircleShape,
                    )
                    .alpha(if (index == current) 1f else 0.5f),
            )
        }
    }
}

/** The dock: app drawer, window switcher, builder, console. */
@Composable
private fun Dock(state: HomeState) {
    val openWindows = state.windows.size
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = Modifier.padding(horizontal = 24.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DockButton(
                icon = Icons.Filled.Apps,
                label = "Apps",
                onClick = { state.show(HomeOverlay.APP_DRAWER) },
            )
            DockButton(
                icon = Icons.Filled.Dashboard,
                label = if (openWindows > 0) "$openWindows open" else "Windows",
                onClick = { state.show(HomeOverlay.WINDOW_SWITCHER) },
            )
            DockButton(
                icon = Icons.Filled.Add,
                label = "Add",
                onClick = { state.show(HomeOverlay.PAGE_MENU) },
            )
            DockButton(
                icon = Icons.Filled.Terminal,
                label = "Myarch",
                onClick = state::openConsole,
            )
        }
    }
}

@Composable
internal fun DockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp),
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A shortcut or a hosted Android widget on a page. */
@Composable
private fun HomeItemCell(state: HomeState, pageId: String, item: HomeItem) {
    when (item) {
        is HomeItem.AppShortcut -> {
            val app = state.androidApps.firstOrNull {
                it.packageName == item.packageName && it.activityName == item.activityName
            }
            ShortcutCell(
                label = app?.label ?: item.label,
                icon = app?.icon,
                fallbackColor = MaterialTheme.colorScheme.primary,
                onClick = { app?.let(state::launch) },
                onLongClick = { state.removeItem(pageId, item.key) },
            )
        }
        is HomeItem.LinuxShortcut -> {
            val entry = state.linuxApps.firstOrNull { it.id == item.desktopId }
            ShortcutCell(
                label = entry?.name ?: item.label,
                icon = null,
                fallbackColor = MaterialTheme.colorScheme.secondary,
                onClick = { entry?.let(state::launch) },
                onLongClick = { state.removeItem(pageId, item.key) },
            )
        }
        is HomeItem.Widget -> AndroidWidgetCell(
            state = state,
            item = item,
            onRemove = { state.removeItem(pageId, item.key) },
        )
    }
}
