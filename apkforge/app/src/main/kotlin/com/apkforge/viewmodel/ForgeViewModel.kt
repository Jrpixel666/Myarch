package com.apkforge.viewmodel

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.apkforge.data.ApkRepository
import com.apkforge.engine.ApkToolEngine
import com.apkforge.engine.ApkSigner
import com.apkforge.model.DecodeMode
import com.apkforge.model.FileNode
import com.apkforge.model.InstalledAppInfo
import com.apkforge.model.LoadedApk
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Single source of truth for the UI. Holds the loaded APK, the decoded project,
 * the streaming engine log, and transient flags, and exposes the coarse actions
 * the screens call: import, decode, build, sign, install, launch, read / save a
 * file.
 */
class ForgeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ApkRepository(app)
    private val engine = ApkToolEngine(app)
    private val signer = ApkSigner(app)

    var loadedApk by mutableStateOf<LoadedApk?>(null)
        private set
    var installedApps by mutableStateOf<List<InstalledAppInfo>>(emptyList())
        private set
    var decodedDir by mutableStateOf<File?>(null)
        private set
    var lastSignedApk by mutableStateOf<File?>(null)
        private set
    var decodeMode by mutableStateOf(DecodeMode.SOURCES_ONLY)
    var busy by mutableStateOf(false)
        private set
    var busyLabel by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)

    /** The file currently open in the editor screen, if any. */
    var editingFile by mutableStateOf<File?>(null)
        private set

    fun openInEditor(file: File) { editingFile = file }

    /** Streaming output from decode / build / sign. */
    val log = mutableStateListOf<String>()

    private val expanded = mutableStateListOf<String>()

    private val projectDir: File get() = File(repo.workspaceDir, "decoded")

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // Engine runs on an IO dispatcher; funnel its lines onto the main thread
    // where the snapshot-backed log list is observed by Compose.
    private val sink = ApkToolEngine.LogSink { line ->
        mainHandler.post { log.add(line) }
    }

    // ---- loading ------------------------------------------------------------

    fun importFromUri(uri: Uri) = busyJob("Reading APK…") {
        setLoaded(repo.importFromUri(uri))
    }

    fun importInstalled(packageName: String) = busyJob("Copying app…") {
        setLoaded(repo.importInstalled(packageName))
    }

    fun refreshInstalledApps() = busyJob("Listing apps…") {
        installedApps = repo.listInstalledApps()
    }

    private fun setLoaded(apk: LoadedApk) {
        loadedApk = apk
        // A freshly loaded APK invalidates any previous decode / build.
        decodedDir = null
        lastSignedApk = null
        expanded.clear()
        log.clear()
        message = "Loaded ${apk.label ?: apk.displayName}"
    }

    // ---- run page -----------------------------------------------------------

    val canRequestInstalls: Boolean get() = repo.canRequestInstalls()

    fun launchLoaded() {
        val pkg = loadedApk?.packageName
        if (pkg == null) {
            message = "No package name found in this APK."
            return
        }
        if (!repo.isInstalled(pkg)) {
            message = "Not installed yet — install it first."
            return
        }
        if (!repo.launch(pkg)) message = "This app has no launchable activity."
    }

    fun installLoaded() {
        val file = loadedApk?.file ?: return
        if (!repo.install(file)) message = "Could not open the installer."
    }

    fun installFile(file: File) {
        if (!repo.install(file)) message = "Could not open the installer."
    }

    fun refreshInstalledState() {
        val apk = loadedApk ?: return
        loadedApk = apk.copy(installed = apk.packageName?.let { repo.isInstalled(it) } ?: false)
    }

    // ---- apktool decode / build --------------------------------------------

    fun decode() {
        val apk = loadedApk?.file ?: run { message = "Load an APK first."; return }
        busyJob("Decoding…") {
            log.add("— decode —")
            val result = engine.decode(apk, projectDir, decodeMode == DecodeMode.SOURCES_ONLY, sink)
            result.onSuccess {
                decodedDir = it
                expanded.clear()
                expanded.add(it.absolutePath)
                message = "Decoded into ${it.name}/"
            }.onFailure { message = "Decode failed — see the log." }
        }
    }

    fun buildAndSign() {
        val dir = decodedDir ?: run { message = "Decode first."; return }
        busyJob("Rebuilding…") {
            log.add("— build —")
            val unsigned = File(repo.workspaceDir, "rebuilt-unsigned.apk")
            val signed = File(repo.workspaceDir, "rebuilt-signed.apk")
            engine.build(dir, unsigned, sink)
                .onFailure { message = "Build failed — see the log." }
                .onSuccess {
                    signer.sign(unsigned, signed, sink)
                        .onSuccess { out -> lastSignedApk = out; message = "Rebuilt & signed: ${out.name}" }
                        .onFailure { message = "Signing failed — see the log." }
                }
        }
    }

    // ---- file tree + editor -------------------------------------------------

    /** Flattened, expansion-aware view of the decoded project for the tree UI. */
    fun visibleNodes(): List<FileNode> {
        val root = decodedDir ?: return emptyList()
        val out = ArrayList<FileNode>()
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles()?.sortedWith(
                compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() },
            ) ?: return
            for (child in children) {
                out.add(FileNode(child, child.name, child.isDirectory, depth))
                if (child.isDirectory && expanded.contains(child.absolutePath)) walk(child, depth + 1)
            }
        }
        out.add(FileNode(root, root.name, true, 0))
        if (expanded.contains(root.absolutePath)) walk(root, 1)
        return out
    }

    fun isExpanded(node: FileNode): Boolean = expanded.contains(node.file.absolutePath)

    fun toggle(node: FileNode) {
        val path = node.file.absolutePath
        if (expanded.contains(path)) expanded.remove(path) else expanded.add(path)
    }

    fun readFile(file: File): String =
        runCatching { file.readText(StandardCharsets.UTF_8) }.getOrElse { "[unable to read: ${it.message}]" }

    fun isProbablyText(file: File): Boolean {
        if (file.length() > 2_000_000) return false
        val head = runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(4096)
                val read = input.read(buf)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            }
        }.getOrDefault(ByteArray(0))
        if (head.isEmpty()) return true
        // A NUL byte in the first block is a reliable "this is binary" signal.
        return head.none { it.toInt() == 0 }
    }

    fun saveFile(file: File, text: String): Boolean =
        runCatching { file.writeText(text, StandardCharsets.UTF_8); true }.getOrDefault(false)
            .also { message = if (it) "Saved ${file.name}" else "Could not save ${file.name}" }

    // ---- helpers ------------------------------------------------------------

    private fun busyJob(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        busyLabel = label
        viewModelScope.launch {
            try {
                block()
            } catch (t: Throwable) {
                message = t.message ?: t.javaClass.simpleName
                log.add("ERROR: ${t.message}")
            } finally {
                busy = false
                busyLabel = ""
            }
        }
    }
}
