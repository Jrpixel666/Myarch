package dev.myarch.app.ui.console

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.myarch.runtime.install.InstallStage
import dev.myarch.runtime.mirror.Mirror

/**
 * The installer.
 *
 * Downloading a Linux distribution over a phone connection fails in ways a
 * desktop installer never has to think about, so the mirror switcher is a
 * first-class part of this screen rather than a hidden setting: Myarch probes
 * the mirrors, shows what it found, switches automatically when one fails, and
 * still lets the user pin one or paste their own URL.
 */
@Composable
internal fun InstallScreen(state: ConsoleState) {
    val installer = state.installer
    val arch = installer.detectArch(state.abis)
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
    ) {
        if (arch == null) {
            ConsoleCard(
                title = "Unsupported CPU",
                subtitle = state.abis.joinToString(", "),
            ) {
                Text(
                    text = "Myarch needs an arm64, armv7 or x86_64 device. " +
                        "This one reports only the ABIs above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        ConsoleCard(
            title = if (state.isInstalled) "Container installed" else "Install Arch Linux",
            subtitle = "${arch.archName} · from Arch Linux ARM",
        ) {
            Text(
                text = if (state.isInstalled) {
                    "A container is already installed. Running the installer again " +
                        "extracts a fresh rootfs over it, which resets installed packages."
                } else {
                    "About 600 MB to download and roughly 2 GB on disk once the desktop " +
                        "packages are installed. You can leave this screen; the install keeps going."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.readinessProblem?.let { problem ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { installer.start(arch) },
                    enabled = !installer.isRunning,
                ) {
                    Text(if (state.isInstalled) "Reinstall" else "Install")
                }
                if (installer.isRunning) {
                    OutlinedButton(onClick = installer::cancel) { Text("Cancel") }
                }
            }
        }

        MirrorPicker(state)

        StageCard(state)

        if (installer.log.isNotEmpty()) {
            ConsoleCard(title = "Install log") {
                LogPane(installer.log.map { line -> line.text })
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Mirror selection: automatic, pinned, or a URL the user pastes. */
@Composable
private fun MirrorPicker(state: ConsoleState) {
    val installer = state.installer
    ConsoleCard(
        title = "Mirror",
        subtitle = "Myarch tests these before downloading and switches if one fails",
    ) {
        MirrorRow(
            label = "Choose automatically",
            detail = "Probe every mirror and use the fastest that answers",
            selected = installer.selectedMirror == null && installer.customMirrorUrl.isBlank(),
            onSelect = {
                installer.selectMirror(null)
                installer.customMirrorUrl = ""
            },
        )
        for (mirror in installer.mirrors()) {
            MirrorRow(
                label = mirror.label,
                detail = buildString {
                    append(mirror.region)
                    if (mirror.verified) append(" · known good")
                    if (mirror.note.isNotEmpty()) {
                        append(" · ")
                        append(mirror.note)
                    }
                },
                selected = installer.selectedMirror?.id == mirror.id && installer.customMirrorUrl.isBlank(),
                onSelect = {
                    installer.selectMirror(mirror)
                    installer.customMirrorUrl = ""
                },
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = installer.customMirrorUrl,
            onValueChange = { installer.customMirrorUrl = it },
            label = { Text("Or paste a mirror URL") },
            placeholder = { Text("https://mirror.example.org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "The image is fetched from <mirror>/os/ArchLinuxARM-<arch>-latest.tar.gz",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun MirrorRow(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Progress for whatever the installer is doing right now. */
@Composable
private fun StageCard(state: ConsoleState) {
    val installer = state.installer
    when (val stage = installer.stage) {
        InstallStage.Idle -> Unit

        is InstallStage.Preparing -> ConsoleCard(title = "Preparing") {
            Text(stage.detail, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is InstallStage.CheckingMirrors -> ConsoleCard(
            title = "Testing mirrors",
            subtitle = "${stage.tried} of ${stage.total}",
        ) {
            LinearProgressIndicator(
                progress = { if (stage.total == 0) 0f else stage.tried.toFloat() / stage.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is InstallStage.Downloading -> ConsoleCard(
            title = "Downloading",
            subtitle = stage.mirror.label,
        ) {
            val progress = stage.progress
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append(progress.bytesDownloaded / MIB)
                    append(" MiB")
                    if (progress.totalBytes > 0) {
                        append(" of ")
                        append(progress.totalBytes / MIB)
                        append(" MiB")
                    }
                    if (progress.bytesPerSecond > 0) {
                        append("  ·  ")
                        append(progress.bytesPerSecond / KIB)
                        append(" KiB/s")
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        is InstallStage.Extracting -> ConsoleCard(
            title = "Extracting",
            subtitle = "${stage.entries} files · ${stage.bytes / MIB} MiB",
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        is InstallStage.Configuring -> ConsoleCard(title = "Configuring") {
            Text(stage.detail, style = MaterialTheme.typography.bodySmall)
        }

        is InstallStage.Bootstrapping -> ConsoleCard(
            title = stage.step,
            subtitle = "pacman is working inside the container",
        ) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Text(
                text = stage.line.take(MAX_LINE),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        InstallStage.Complete -> ConsoleCard(
            title = "Ready",
            subtitle = "The container is installed",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = state::startSession) { Text("Start the session") }
                OutlinedButton(onClick = { state.route = ConsoleRoute.BUILD }) { Text("Build a widget") }
            }
        }

        is InstallStage.Failed -> ConsoleCard(
            title = "Install failed",
            subtitle = stage.mirror?.label,
        ) {
            Text(
                text = stage.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val arch = installer.detectArch(state.abis)
                if (stage.canSwitchMirror && arch != null) {
                    Button(onClick = { installer.retryWithDifferentMirror(arch) }) {
                        Text("Try another mirror")
                    }
                }
                if (stage.canRetry && arch != null) {
                    OutlinedButton(onClick = { installer.start(arch) }) { Text("Retry") }
                }
            }
            Spacer(Modifier.height(6.dp))
            AssistChip(
                onClick = { state.route = ConsoleRoute.DIAGNOSTICS },
                label = { Text("Open diagnostics") },
            )
        }
    }
}

private const val MIB = 1024L * 1024L
private const val KIB = 1024L
private const val MAX_LINE = 160
