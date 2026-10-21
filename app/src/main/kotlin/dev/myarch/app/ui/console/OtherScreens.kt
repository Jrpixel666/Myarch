package dev.myarch.app.ui.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.myarch.app.compositor.SessionState
import dev.myarch.app.ui.theme.color
import dev.myarch.runtime.theme.OmarchyTheme
import dev.myarch.runtime.widgets.BuildOutcome
import dev.myarch.runtime.widgets.TemplateKind

/** Status, and the things a user most often wants to do next. */
@Composable
internal fun OverviewScreen(state: ConsoleState) {
    val sessionState = state.sessionState()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleCard(
            title = "Session",
            subtitle = when (val session = sessionState) {
                SessionState.Stopped -> "Stopped"
                SessionState.Starting -> "Starting"
                is SessionState.Running -> "Running · ${session.windows} window(s)"
                is SessionState.Failed -> "Failed"
            },
        ) {
            (sessionState as? SessionState.Failed)?.let { failed ->
                Text(
                    text = failed.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::startSession, enabled = state.isInstalled) { Text("Start") }
                OutlinedButton(onClick = state::stopSession) { Text("Stop") }
            }
            if (!state.isInstalled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Install the container first.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        ConsoleCard(title = "Next steps") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StepRow("1", "Install the Arch container", state.isInstalled) {
                    state.route = ConsoleRoute.INSTALL
                }
                StepRow("2", "Build Quickshell (the Omarchy shell)", false) {
                    state.route = ConsoleRoute.PACKAGES
                }
                StepRow("3", "Add widgets and applications", false) {
                    state.route = ConsoleRoute.BUILD
                }
                StepRow("4", "Pick a theme", false) {
                    state.route = ConsoleRoute.THEMES
                }
            }
        }

        ConsoleCard(
            title = "How this works",
            subtitle = "So nothing here is a surprise",
        ) {
            Text(
                text = "Myarch runs Arch Linux under proot — no root, no kernel modules. " +
                    "The launcher is itself a Wayland compositor: each Linux window becomes " +
                    "its own Android task, and Quickshell's bars and widgets are drawn onto " +
                    "the home screen. Rendering is in software for now, so expect modest " +
                    "performance from heavy applications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StepRow(number: String, label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (done) "✓" else number,
                style = MaterialTheme.typography.labelSmall,
                color = if (done) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.size(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Search, install and remove packages; and build Quickshell. */
@Composable
internal fun PackagesScreen(state: ConsoleState) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConsoleCard(
            title = "Quickshell",
            subtitle = "Not in Arch's repositories, so it is compiled from source",
        ) {
            Text(
                text = "Takes 20-40 minutes and needs about 3 GB free. Until it is built, " +
                    "Myarch falls back to waybar or a terminal so the session is not empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = state::buildQuickshell,
                enabled = state.isInstalled && !state.packageBusy,
            ) { Text("Build Quickshell") }
        }

        ConsoleCard(title = "Packages") {
            OutlinedTextField(
                value = state.packageQuery,
                onValueChange = { state.packageQuery = it },
                label = { Text("Search pacman") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = state::searchPackages,
                    enabled = state.isInstalled && !state.packageBusy,
                ) { Text("Search") }
                OutlinedButton(
                    onClick = state::upgradeSystem,
                    enabled = state.isInstalled && !state.packageBusy,
                ) { Text("Upgrade all") }
            }
            if (state.packageBusy) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.searchResults.isEmpty()) {
            ConsoleCard(title = "Suggested", subtitle = "Common additions, one tap each") {
                Column {
                    for (suggestion in state.suggested) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${suggestion.title}  (${suggestion.name})",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = suggestion.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            AssistChip(
                                onClick = { state.installPackages(listOf(suggestion.name)) },
                                label = { Text("Install") },
                                enabled = state.isInstalled && !state.packageBusy,
                            )
                        }
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.searchResults, key = { "${it.repository}/${it.name}" }) { info ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${info.name} ${info.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = info.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (info.isInstalled) {
                            OutlinedButton(
                                onClick = { state.removePackages(listOf(info.name)) },
                                enabled = !state.packageBusy,
                            ) { Text("Remove") }
                        } else {
                            Button(
                                onClick = { state.installPackages(listOf(info.name)) },
                                enabled = !state.packageBusy,
                            ) { Text("Install") }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                }
            }
        }

        if (state.packageLog.isNotEmpty()) {
            ConsoleCard(title = "pacman output") {
                LogPane(state.packageLog)
            }
        }
    }
}

/**
 * The builder menu: pick a template, see what it needs, build it.
 *
 * "Find what's needed and install" is the whole point of this screen -- each
 * template declares its packages, Myarch checks which are missing, and the
 * build installs them before writing any code.
 */
@Composable
internal fun BuildScreen(state: ConsoleState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        for (kind in TemplateKind.entries) {
            val templates = state.templates.filter { it.kind == kind }
            if (templates.isEmpty()) continue
            ConsoleCard(
                title = when (kind) {
                    TemplateKind.WIDGET -> "Home screen widgets"
                    TemplateKind.PANEL -> "Panels and bars"
                    TemplateKind.APPLICATION -> "Applications"
                    TemplateKind.SCRIPT -> "Terminal tools"
                },
                subtitle = when (kind) {
                    TemplateKind.WIDGET -> "Quickshell items drawn on the Android home screen"
                    TemplateKind.PANEL -> "Layer-shell surfaces anchored to a screen edge"
                    TemplateKind.APPLICATION -> "Each one opens as its own Android task"
                    TemplateKind.SCRIPT -> "A script plus a drawer entry"
                },
            ) {
                Column {
                    for (template in templates) {
                        val selected = state.selectedTemplate?.id == template.id
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { state.selectTemplate(template) }
                                .border(
                                    width = if (selected) 1.dp else 0.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.Transparent
                                    },
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .padding(8.dp),
                        ) {
                            Text(
                                text = template.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = template.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "needs: ${template.requiredPackages.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (template.notes.isNotEmpty()) {
                                Text(
                                    text = template.notes,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        state.selectedTemplate?.let { template ->
            ConsoleCard(title = "Create ${template.title}") {
                OutlinedTextField(
                    value = state.buildName,
                    onValueChange = { state.buildName = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (state.missingForTemplate.isEmpty()) {
                        "Every dependency is already installed."
                    } else {
                        "Will install: ${state.missingForTemplate.joinToString(", ")}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = state::build,
                    enabled = state.isInstalled && !state.buildBusy && state.buildName.isNotBlank(),
                ) { Text(if (state.buildBusy) "Building..." else "Build it") }

                (state.lastBuild as? BuildOutcome.Built)?.let { built ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Created:\n" + built.files.joinToString("\n") { "  /$it" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                (state.lastBuild as? BuildOutcome.Failed)?.let { failed ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = failed.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (state.buildLog.isNotEmpty()) {
                ConsoleCard(title = "Build log") { LogPane(state.buildLog) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Theme picker; applies to the launcher and the container together. */
@Composable
internal fun ThemesScreen(state: ConsoleState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleCard(
            title = "Themes",
            subtitle = "One palette for the launcher, the terminal, btop and Quickshell",
        ) {
            Text(
                text = "Choosing a theme rewrites the config files inside the container, " +
                    "so the Linux side changes colour at the same time as the launcher.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (theme in state.themes) {
            ThemeRow(
                theme = theme,
                selected = theme.id == state.theme.id,
                onSelect = { state.applyTheme(theme) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ThemeRow(theme: OmarchyTheme, selected: Boolean, onSelect: () -> Unit) {
    ConsoleCard(
        title = theme.name + if (selected) "  ·  active" else "",
        subtitle = if (theme.isLight) "Light" else "Dark",
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f),
            ) {
                for (hex in theme.palette.ansi.take(SWATCH_COUNT)) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .background(theme.color(hex), RoundedCornerShape(4.dp)),
                    )
                }
            }
            if (!selected) {
                OutlinedButton(onClick = onSelect) { Text("Use") }
            }
        }
    }
}

/** Logs and environment facts, for when something does not work. */
@Composable
internal fun DiagnosticsScreen(state: ConsoleState) {
    val service = state.service
    val sessionLog = state.sessionLog()
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleCard(title = "Environment") {
            Column {
                DiagRow("ABIs", state.abis.joinToString(", "))
                DiagRow("Container installed", state.isInstalled.toString())
                DiagRow("Readiness", state.readinessProblem ?: "ready")
                DiagRow("Compositor", if (service?.compositor != null) "listening" else "not started")
                DiagRow("Open windows", service?.surfaces?.windowIds()?.size?.toString() ?: "0")
                DiagRow("Panels", service?.surfaces?.panelSnapshots()?.size?.toString() ?: "0")
            }
        }
        ConsoleCard(title = "Session log") {
            if (sessionLog.isEmpty()) {
                Text(
                    text = "Nothing logged yet. Start the session to see output here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LogPane(sessionLog)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.6f),
        )
    }
}

/** Settings: scaling, keyboard layout, autostart. */
@Composable
internal fun SettingsScreen(state: ConsoleState) {
    val app = dev.myarch.app.MyarchApplication.of(androidx.compose.ui.platform.LocalContext.current)
    var uiScale by remember { mutableStateOf(app.settings.uiScale) }
    var autoStart by remember { mutableStateOf(app.settings.autoStartSession) }
    var showLinuxApps by remember { mutableStateOf(app.settings.showLinuxApps) }
    var layout by remember { mutableStateOf(app.settings.keyboardLayout) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ConsoleCard(
            title = "Window scale",
            subtitle = "How large Linux windows render: $uiScale×",
        ) {
            Text(
                text = "Higher is easier to read on a phone. 1× gives a desktop-sized " +
                    "workspace, which suits an external screen or a keyboard. " +
                    "Takes effect for windows opened after a session restart.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in 1..3) {
                    OutlinedButton(
                        onClick = {
                            uiScale = option
                            app.settings.uiScale = option
                        },
                    ) { Text("${option}×") }
                }
            }
        }

        ConsoleCard(title = "Keyboard layout", subtitle = "Passed to xkbcli on session start") {
            OutlinedTextField(
                value = layout,
                onValueChange = {
                    layout = it
                    app.settings.keyboardLayout = it
                },
                label = { Text("XKB layout") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ConsoleCard(title = "Behaviour") {
            SettingSwitch(
                label = "Start the session with the launcher",
                checked = autoStart,
            ) {
                autoStart = it
                app.settings.autoStartSession = it
            }
            SettingSwitch(
                label = "Show Linux apps in the drawer",
                checked = showLinuxApps,
            ) {
                showLinuxApps = it
                app.settings.showLinuxApps = it
            }
        }

        ConsoleCard(title = "About") {
            Text(
                text = "Myarch ${dev.myarch.app.BuildConfig.VERSION_NAME}\n" +
                    "A launcher, a Wayland compositor and an Arch container.\n" +
                    "No root required; everything runs under proot.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private const val SWATCH_COUNT = 8
