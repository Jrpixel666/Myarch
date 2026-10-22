package com.apkforge.ui.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.apkforge.viewmodel.ForgeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(vm: ForgeViewModel, onBack: () -> Unit) {
    val file = vm.editingFile
    if (file == null) {
        onBack()
        return
    }
    val targetLine = vm.editingLine

    // Load once per file; pre-select the target line so it is highlighted.
    val initial = remember(file) {
        val content = vm.readFile(file)
        val range = targetLine?.let { lineCharRange(content, it) }
        TextFieldValue(content, selection = range?.let { TextRange(it.first, it.last) } ?: TextRange.Zero)
    }
    var field by remember(file) { mutableStateOf(initial) }
    var dirty by remember(file) { mutableStateOf(false) }

    val scroll = rememberScrollState()
    var layout by remember(file) { mutableStateOf<TextLayoutResult?>(null) }
    var didJump by remember(file) { mutableStateOf(targetLine == null) }

    // Once the text has been laid out, scroll the target line into view. Using
    // the layout (not a line-height estimate) keeps it accurate even when long
    // smali/XML lines wrap.
    LaunchedEffect(layout, didJump) {
        val lr = layout
        if (!didJump && lr != null && targetLine != null) {
            val range = lineCharRange(field.text, targetLine)
            if (range != null) {
                val visualLine = lr.getLineForOffset(range.first.coerceIn(0, field.text.length))
                val y = lr.getLineTop(visualLine).toInt()
                scroll.scrollTo((y - 48).coerceAtLeast(0))
            }
            didJump = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        file.name + (targetLine?.let { ":$it" } ?: "") + if (dirty) " •" else "",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (vm.saveFile(file, field.text)) dirty = false },
                    ) { Icon(Icons.Filled.Save, "Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
                .verticalScroll(scroll),
        ) {
            BasicTextField(
                value = field,
                onValueChange = { new ->
                    if (new.text != field.text) dirty = true
                    field = new
                },
                onTextLayout = { layout = it },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Character range [start, end) covering the 1-based [line] in [text] (newline
 * excluded), or null if the file has fewer lines than that.
 */
private fun lineCharRange(text: String, line: Int): IntRange? {
    if (line < 1) return null
    var index = 0
    var current = 1
    while (current < line) {
        val newline = text.indexOf('\n', index)
        if (newline < 0) return null
        index = newline + 1
        current++
    }
    val start = index
    val newline = text.indexOf('\n', start)
    val end = if (newline < 0) text.length else newline
    return start..end
}
