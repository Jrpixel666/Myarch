package dev.myarch.runtime.widgets

/** What a template produces. */
public enum class TemplateKind {
    /** A Quickshell item placed on the home screen's widget layer. */
    WIDGET,

    /** A Quickshell panel anchored to a screen edge, e.g. a bar. */
    PANEL,

    /** A windowed application, which becomes its own Android task. */
    APPLICATION,

    /** A script plus a desktop entry, launchable from the drawer. */
    SCRIPT,
}

/** One file a template writes, with `{{PLACEHOLDER}}` substitution. */
public data class TemplateFile(
    /** Path relative to the container root, e.g. `root/.config/...`. */
    val path: String,
    val content: String,
    /** Make the file executable after writing. */
    val executable: Boolean = false,
)

/**
 * A recipe the builder menu can turn into working code.
 *
 * Templates carry their own dependency list, which is the point: the builder
 * can tell the user "this needs `qt6-multimedia`, shall I install it?" and then
 * do it, rather than scaffolding something that fails to start.
 */
public data class BuildTemplate(
    val id: String,
    val title: String,
    val description: String,
    val kind: TemplateKind,
    /** pacman packages that must be present. */
    val requiredPackages: List<String>,
    val files: List<TemplateFile>,
    /** Shell run inside the container after the files are written. */
    val postInstall: String? = null,
    /** Add the component to the Quickshell widget registry. */
    val registerInShell: Boolean = false,
    /** Notes shown in the builder, e.g. known limitations. */
    val notes: String = "",
) {
    /** The slug derived from a user-supplied name: `My Clock` -> `my-clock`. */
    public fun idFor(instanceName: String): String =
        instanceName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { id }

    /** The QML component name: `my-clock` -> `MyClock`. */
    public fun classNameFor(instanceName: String): String =
        idFor(instanceName).split('-').joinToString("") { part -> part.replaceFirstChar { it.uppercase() } }

    /** Fills in `{{NAME}}`, `{{ID}}` and `{{CLASS}}`. */
    public fun render(instanceName: String): List<TemplateFile> {
        val id = idFor(instanceName)
        val className = classNameFor(instanceName)
        return files.map { file ->
            file.copy(
                path = file.path.substitute(instanceName, id, className),
                content = file.content.substitute(instanceName, id, className),
            )
        }
    }

    private fun String.substitute(name: String, id: String, className: String): String = this
        .replace("{{NAME}}", name)
        .replace("{{ID}}", id)
        .replace("{{CLASS}}", className)
}
