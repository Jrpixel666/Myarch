package dev.myarch.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The console's shell: a row of screen chips and the selected screen. */
@Composable
public fun ConsoleScreen(state: ConsoleState) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Text(
                text = "Myarch",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(start = 18.dp, top = 14.dp),
            )
            Text(
                text = "Arch Linux and Omarchy, on your phone",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp, bottom = 10.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (route in ConsoleRoute.entries) {
                    FilterChip(
                        selected = state.route == route,
                        onClick = { state.route = route },
                        label = { Text(route.title) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Box(modifier = Modifier.fillMaxSize()) {
                when (state.route) {
                    ConsoleRoute.OVERVIEW -> OverviewScreen(state)
                    ConsoleRoute.INSTALL -> InstallScreen(state)
                    ConsoleRoute.PACKAGES -> PackagesScreen(state)
                    ConsoleRoute.BUILD -> BuildScreen(state)
                    ConsoleRoute.THEMES -> ThemesScreen(state)
                    ConsoleRoute.DIAGNOSTICS -> DiagnosticsScreen(state)
                    ConsoleRoute.SETTINGS -> SettingsScreen(state)
                }
            }
        }
    }
}

/** A titled card, the console's one layout primitive. */
@Composable
internal fun ConsoleCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

/** A fixed-height scrolling log view, used by several screens. */
@Composable
internal fun LogPane(lines: List<String>, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    androidx.compose.runtime.LaunchedEffect(lines.size) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .background(
                MaterialTheme.colorScheme.background,
                RoundedCornerShape(10.dp),
            )
            .padding(8.dp),
    ) {
        Column(modifier = Modifier.verticalScroll(scroll)) {
            for (line in lines) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
