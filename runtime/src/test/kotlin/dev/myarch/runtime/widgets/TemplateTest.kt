package dev.myarch.runtime.widgets

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The builder's templates: naming, substitution and sanity of the output. */
class TemplateTest {

    @Test
    fun `names become slugs and QML class names`() {
        val template = BuiltInTemplates.byId("clock")!!
        assertThat(template.idFor("My Clock")).isEqualTo("my-clock")
        assertThat(template.classNameFor("My Clock")).isEqualTo("MyClock")
        assertThat(template.idFor("  weird!!name  ")).isEqualTo("weird-name")
        // An unusable name falls back to the template's own id rather than
        // producing an empty path.
        assertThat(template.idFor("!!!")).isEqualTo("clock")
    }

    @Test
    fun `rendering substitutes into both paths and contents`() {
        val template = BuiltInTemplates.byId("clock")!!
        val files = template.render("Desk Clock")
        assertThat(files).hasSize(1)
        val file = files.single()
        assertThat(file.path).endsWith("widgets/DeskClock.qml")
        assertThat(file.content).contains("// Desk Clock --")
        assertThat(file.content).doesNotContain("{{")
    }

    @Test
    fun `every template declares dependencies and leaves no placeholders`() {
        for (template in BuiltInTemplates.all) {
            assertThat(template.requiredPackages).isNotEmpty()
            assertThat(template.title).isNotEmpty()
            assertThat(template.description).isNotEmpty()
            for (file in template.render("Example Name")) {
                assertThat(file.content).doesNotContain("{{NAME}}")
                assertThat(file.content).doesNotContain("{{ID}}")
                assertThat(file.content).doesNotContain("{{CLASS}}")
                assertThat(file.path).doesNotContain("{{")
                // Nothing may be written outside the container's filesystem.
                assertThat(file.path).doesNotContain("..")
                assertThat(file.path.startsWith("/")).isFalse()
            }
        }
    }

    @Test
    fun `template ids are unique and every kind is represented`() {
        assertThat(BuiltInTemplates.all.map { it.id }).containsNoDuplicates()
        for (kind in TemplateKind.entries) {
            assertThat(BuiltInTemplates.ofKind(kind)).isNotEmpty()
        }
    }

    @Test
    fun `widget QML imports the shared theme and balances its braces`() {
        for (template in BuiltInTemplates.all) {
            for (file in template.render("Example")) {
                if (!file.path.endsWith(".qml")) continue
                assertThat(file.content).contains("import QtQuick")
                assertThat(file.content.count { it == '{' }).isEqualTo(file.content.count { it == '}' })
            }
        }
    }

    @Test
    fun `widgets land in the Quickshell config and are registered`() {
        val widget = BuiltInTemplates.byId("system-monitor")!!
        assertThat(widget.registerInShell).isTrue()
        assertThat(widget.render("Monitor").single().path)
            .isEqualTo("root/.config/quickshell/myarch/widgets/Monitor.qml")
    }

    @Test
    fun `applications ship a desktop entry so they appear in the drawer`() {
        for (template in BuiltInTemplates.ofKind(TemplateKind.APPLICATION) +
            BuiltInTemplates.ofKind(TemplateKind.SCRIPT)
        ) {
            val paths = template.render("Thing").map { it.path }
            assertThat(paths.any { it.contains(".local/share/applications") && it.endsWith(".desktop") })
                .isTrue()
        }
    }

    @Test
    fun `scripts are marked executable`() {
        val script = BuiltInTemplates.byId("shell-script")!!
        val rendered = script.render("Tool")
        assertThat(rendered.first { it.path.contains(".local/bin") }.executable).isTrue()
    }

    @Test
    fun `panels declare the layer-shell namespace Myarch draws them under`() {
        val bar = BuiltInTemplates.byId("top-bar")!!
        val qml = bar.render("Bar").single().content
        assertThat(qml).contains("PanelWindow")
        assertThat(qml).contains("WlrLayershell.namespace")
        assertThat(qml).contains("import Quickshell.Wayland")
    }

    @Test
    fun `the python template is syntactically plausible python`() {
        val python = BuiltInTemplates.byId("python-app")!!
            .render("Notes")
            .first { it.path.endsWith(".py") }
            .content
        assertThat(python).startsWith("#!/usr/bin/env python3")
        assertThat(python).contains("def main()")
        assertThat(python).contains("if __name__ ==")
        // Backslash escapes inside a Kotlin raw string are literal, so a
        // docstring written that way would leak backslashes into the file.
        assertThat(python).doesNotContain("\\\"")
    }
}
