package dev.myarch.runtime.widgets

import dev.myarch.runtime.MyarchPaths
import dev.myarch.runtime.container.LogLine
import dev.myarch.runtime.pacman.Pacman
import java.io.File

/** What the builder did, or why it could not. */
public sealed interface BuildOutcome {
    public data class Built(
        val template: BuildTemplate,
        val instanceName: String,
        val files: List<String>,
        val installedPackages: List<String>,
    ) : BuildOutcome

    public data class Failed(val reason: String) : BuildOutcome
}

/** One widget or application the user has created. */
public data class BuiltItem(
    val id: String,
    val name: String,
    val templateId: String,
    val kind: TemplateKind,
    val files: List<String>,
)

/**
 * Turns a template into working files inside the container.
 *
 * The sequence is the same one a person would follow by hand -- work out what
 * is missing, install it, write the code, register it with the shell -- with
 * the dependency step done for them, because "widget does not load" with no
 * explanation is the usual outcome of a missing Qt module.
 */
public class WidgetBuilder(
    private val paths: MyarchPaths,
    private val pacman: Pacman,
) {

    /** Packages the template needs that are not installed yet. */
    public fun missingPackages(template: BuildTemplate): List<String> =
        pacman.areInstalled(template.requiredPackages)
            .filterValues { installed -> !installed }
            .keys
            .toList()

    /**
     * Scaffolds [template] under the name the user typed.
     *
     * @param installMissing install dependencies; when false, a missing package
     *   fails the build instead, which is what the "review first" path in the
     *   builder UI uses
     */
    public fun build(
        template: BuildTemplate,
        instanceName: String,
        installMissing: Boolean = true,
        onLog: (LogLine) -> Unit = {},
    ): BuildOutcome {
        if (instanceName.isBlank()) {
            return BuildOutcome.Failed("Give it a name first.")
        }
        if (!paths.rootfs.isDirectory) {
            return BuildOutcome.Failed("The Arch container is not installed yet.")
        }

        val missing = missingPackages(template)
        if (missing.isNotEmpty()) {
            if (!installMissing) {
                return BuildOutcome.Failed("Needs ${missing.joinToString(", ")}, which is not installed.")
            }
            onLog(LogLine("Installing ${missing.joinToString(", ")}"))
            val result = pacman.install(missing, onLog)
            if (!result.isSuccess) {
                return BuildOutcome.Failed(
                    "Could not install ${missing.joinToString(", ")} " +
                        "(pacman exit ${result.exitCode}). ${result.output.takeLast(TAIL_LINES).joinToString(" ")}",
                )
            }
        }

        val rendered = template.render(instanceName)
        val written = ArrayList<String>()
        for (file in rendered) {
            val target = File(paths.rootfs, file.path)
            if (target.exists()) {
                // Overwriting someone's edited widget without asking would be
                // worse than refusing: the file is their code now.
                return BuildOutcome.Failed(
                    "${file.path} already exists. Pick another name, or delete the old one first.",
                )
            }
            target.parentFile?.mkdirs()
            target.writeText(file.content)
            if (file.executable) {
                target.setExecutable(true, false)
            }
            written.add(file.path)
            onLog(LogLine("wrote ${file.path}"))
        }

        if (template.registerInShell) {
            val registryFile = registerWidget(instanceName, template)
            written.add(registryFile)
            onLog(LogLine("registered the widget with Quickshell"))
        }

        template.postInstall?.let { script ->
            val command = script
                .replace("{{NAME}}", instanceName)
                .replace("{{ID}}", template.idFor(instanceName))
                .replace("{{CLASS}}", template.classNameFor(instanceName))
            onLog(LogLine("running post-install step"))
            postInstall(command, onLog)
        }

        return BuildOutcome.Built(
            template = template,
            instanceName = instanceName,
            files = written,
            installedPackages = missing,
        )
    }

    private fun postInstall(command: String, onLog: (LogLine) -> Unit) {
        // The post-install steps are chmod-style fixes; pacman's session is the
        // same container session, so reuse it rather than building another.
        val result = pacman.runShell(command, onLog)
        if (!result.isSuccess) {
            onLog(LogLine("post-install step failed: ${result.text()}", isError = true))
        }
    }

    /**
     * Adds the widget to the generated QML registry the shell reads.
     *
     * Quickshell has no directory scanning, so the list of widgets has to exist
     * as QML. Keeping it generated (rather than asking the user to edit
     * `shell.qml`) means the builder can add and remove entries safely.
     */
    private fun registerWidget(instanceName: String, template: BuildTemplate): String {
        val registryPath = "root/.config/quickshell/myarch/WidgetRegistry.qml"
        val registry = File(paths.rootfs, registryPath)
        val entries = readRegistryEntries(registry).toMutableList()
        val className = template.classNameFor(instanceName)
        if (entries.none { it.className == className }) {
            entries.add(RegistryEntry(className, instanceName))
        }
        registry.parentFile?.mkdirs()
        registry.writeText(renderRegistry(entries))
        return registryPath
    }

    /** Removes a built item's files and its registry entry. */
    public fun remove(item: BuiltItem): BuildOutcome {
        for (path in item.files) {
            if (path.endsWith("WidgetRegistry.qml")) continue
            File(paths.rootfs, path).delete()
        }
        val registry = File(paths.rootfs, "root/.config/quickshell/myarch/WidgetRegistry.qml")
        if (registry.exists()) {
            val remaining = readRegistryEntries(registry).filter { it.instanceName != item.name }
            registry.writeText(renderRegistry(remaining))
        }
        return BuildOutcome.Built(
            template = BuiltInTemplates.byId(item.templateId) ?: return BuildOutcome.Failed("unknown template"),
            instanceName = item.name,
            files = emptyList(),
            installedPackages = emptyList(),
        )
    }

    private data class RegistryEntry(val className: String, val instanceName: String)

    private fun readRegistryEntries(registry: File): List<RegistryEntry> {
        if (!registry.isFile) return emptyList()
        // Entries are written in a fixed shape, so a line match is enough and
        // avoids needing a QML parser here.
        val pattern = Regex("""\{\s*component:\s*"([A-Za-z0-9_]+)",\s*name:\s*"([^"]*)"\s*\}""")
        return pattern.findAll(registry.readText()).map { match ->
            RegistryEntry(match.groupValues[1], match.groupValues[2])
        }.toList()
    }

    private fun renderRegistry(entries: List<RegistryEntry>): String = buildString {
        appendLine("// Generated by the Myarch widget builder. Edited by the app;")
        appendLine("// add widgets from Build > Widgets rather than editing this file.")
        appendLine("pragma Singleton")
        appendLine()
        appendLine("import QtQuick")
        appendLine()
        appendLine("QtObject {")
        appendLine("    readonly property var widgets: [")
        for (entry in entries) {
            appendLine("""        { component: "${entry.className}", name: "${entry.instanceName}" },""")
        }
        appendLine("    ]")
        appendLine("}")
    }

    private companion object {
        const val TAIL_LINES = 5
    }
}
