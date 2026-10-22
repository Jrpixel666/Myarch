package com.apkforge.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Drives apktool's own library (`org.apktool:apktool-lib`) to decode and
 * rebuild APKs on-device.
 *
 * It talks to apktool through reflection rather than a compile-time API. The
 * library's public types (`Config`, `ApkDecoder`, `ApkBuilder`) have been
 * reshuffled across 2.7 → 2.10, and reflection lets one code path adapt to the
 * version that actually resolves, failing with a readable log line instead of a
 * link error. Every apktool call is wrapped so a failure surfaces in the UI.
 *
 * Writable-path setup matters on Android: apktool defaults its framework cache
 * to `$HOME/.local/share/apktool` and uses `java.io.tmpdir`, neither of which
 * is writable in an app process until we repoint them at app-private storage.
 */
class ApkToolEngine(private val context: Context) {

    /** A sink the UI subscribes to for streaming build output. */
    fun interface LogSink {
        fun line(text: String)
    }

    private val frameworkDir: File = File(context.filesDir, "apktool/framework").apply { mkdirs() }

    private fun prepareEnvironment() {
        // apktool's default paths assume a desktop home + /tmp; give it ours.
        System.setProperty("user.home", context.filesDir.absolutePath)
        System.setProperty("java.io.tmpdir", context.cacheDir.absolutePath)
    }

    /**
     * Decode [apk] into [outDir] (which is replaced if it already exists).
     * [sourcesOnly] keeps resources untouched so the rebuild needs no aapt2.
     */
    suspend fun decode(
        apk: File,
        outDir: File,
        sourcesOnly: Boolean,
        log: LogSink,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            prepareEnvironment()
            if (outDir.exists()) outDir.deleteRecursively()
            outDir.parentFile?.mkdirs()

            capturingApktoolLog(log) {
                log.line("apktool: decoding ${apk.name} (${if (sourcesOnly) "sources only" else "full"})")
                val config = buildConfig(decodeMode = true, sourcesOnly = sourcesOnly, aapt = null, log = log)
                val extFile = newExtFile(apk)
                val decoder = construct(
                    className = "brut.androlib.ApkDecoder",
                    args = mapOf(ExtFileType to extFile, config.first to config.second),
                )
                invoke(decoder, "decode", File::class.java to outDir)
                log.line("apktool: decode finished → ${outDir.absolutePath}")
            }
            outDir
        }.onFailure { logFailure(log, it) }
    }

    /**
     * Rebuild the apktool project at [projectDir] into an unsigned APK at [outApk].
     * Points apktool at a native aapt2 when one is bundled (see [Aapt2]).
     */
    suspend fun build(
        projectDir: File,
        outApk: File,
        log: LogSink,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            prepareEnvironment()
            outApk.parentFile?.mkdirs()
            if (outApk.exists()) outApk.delete()

            val aapt = Aapt2.locate(context)
            if (aapt != null) {
                log.line("apktool: using ${if (aapt.isAapt2) "aapt2" else "aapt"} at ${aapt.file.name}")
            } else {
                log.line("apktool: no bundled aapt2 — relying on the no-recompile path (works for 'sources only' projects)")
            }

            capturingApktoolLog(log) {
                log.line("apktool: building ${projectDir.name}")
                val config = buildConfig(decodeMode = false, sourcesOnly = false, aapt = aapt, log = log)
                val extFile = newExtFile(projectDir)
                val builder = construct(
                    className = "brut.androlib.ApkBuilder",
                    args = mapOf(ExtFileType to extFile, config.first to config.second),
                )
                invoke(builder, "build", File::class.java to outApk)
                log.line("apktool: build finished → ${outApk.absolutePath}")
            }
            outApk
        }.onFailure { logFailure(log, it) }
    }

    // ---- apktool reflection plumbing ---------------------------------------

    private val ExtFileType: Class<*> by lazy { Class.forName("brut.directory.ExtFile") }
    private val ConfigType: Class<*> by lazy { Class.forName("brut.androlib.Config") }

    private fun newExtFile(file: File): Any =
        ExtFileType.getConstructor(File::class.java).newInstance(file)

    /**
     * Build a configured apktool [Config]. Returns the [Class] it belongs to and
     * the instance, so the caller can feed both to a constructor by type.
     */
    private fun buildConfig(
        decodeMode: Boolean,
        sourcesOnly: Boolean,
        aapt: Aapt2.Found?,
        log: LogSink,
    ): Pair<Class<*>, Any> {
        val config = runCatching {
            ConfigType.getMethod("getDefaultConfig").invoke(null)
        }.getOrElse { ConfigType.getDeclaredConstructor().newInstance() }

        setConfig(config, "setFrameworkDirectory", String::class.java, frameworkDir.absolutePath)
        setConfig(config, "setForceDelete", Boolean::class.javaPrimitiveType!!, true)

        if (decodeMode) {
            lookupShort("DECODE_SOURCES_SMALI")?.let {
                setConfig(config, "setDecodeSources", Short::class.javaPrimitiveType!!, it)
            }
            if (sourcesOnly) {
                val none = lookupShort("DECODE_RESOURCES_NONE")
                if (none != null) {
                    setConfig(config, "setDecodeResources", Short::class.javaPrimitiveType!!, none)
                } else {
                    log.line("apktool: could not select 'no resources' — rebuild may need aapt2")
                }
            }
        } else if (aapt != null) {
            // Newer apktool wants a File; set both the binary and the version.
            setConfig(config, "setAaptBinary", File::class.java, aapt.file)
            setConfig(config, "setAaptVersion", Int::class.javaPrimitiveType!!, if (aapt.isAapt2) 2 else 1)
            setConfig(config, "setUseAapt2", Boolean::class.javaPrimitiveType!!, aapt.isAapt2)
        }
        return ConfigType to config
    }

    /** Set an apktool Config value by setter, falling back to a public field. */
    private fun setConfig(config: Any, setter: String, type: Class<*>, value: Any) {
        val ok = runCatching {
            ConfigType.getMethod(setter, type).invoke(config, value); true
        }.getOrDefault(false)
        if (ok) return
        // Fall back to the equivalent public field (older apktool exposed them).
        val field = setter.removePrefix("set").replaceFirstChar { it.lowercase() }
        runCatching { ConfigType.getField(field).set(config, value) }
    }

    /** apktool kept these decode constants on Config in 2.9+, on ApkDecoder before. */
    private fun lookupShort(name: String): Short? {
        for (cls in listOf("brut.androlib.Config", "brut.androlib.ApkDecoder", "brut.androlib.options.BuildOptions")) {
            runCatching { Class.forName(cls).getField(name).getShort(null) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** Construct a class, matching each arg to a constructor parameter by type. */
    private fun construct(className: String, args: Map<Class<*>, Any>): Any {
        val type = Class.forName(className)
        for (ctor in type.constructors) {
            val params = ctor.parameterTypes
            if (params.size != args.size) continue
            val ordered = arrayOfNulls<Any>(params.size)
            var matched = true
            for (i in params.indices) {
                val value = args.entries.firstOrNull { params[i].isAssignableFrom(it.key) }?.value
                if (value == null) { matched = false; break }
                ordered[i] = value
            }
            if (matched) return ctor.newInstance(*ordered)
        }
        error("No matching constructor for $className with ${args.keys.map { it.simpleName }}")
    }

    private fun invoke(target: Any, method: String, arg: Pair<Class<*>, Any>) {
        target.javaClass.getMethod(method, arg.first).invoke(target, arg.second)
    }

    // ---- logging ------------------------------------------------------------

    /** Routes apktool's java.util.logging output into [log] for the run. */
    private inline fun capturingApktoolLog(log: LogSink, block: () -> Unit) {
        val root = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                if (record.level.intValue() >= Level.INFO.intValue()) {
                    log.line("  ${record.message}")
                }
            }
            override fun flush() {}
            override fun close() {}
        }
        root.addHandler(handler)
        try {
            block()
        } finally {
            root.removeHandler(handler)
        }
    }

    private fun logFailure(log: LogSink, t: Throwable) {
        log.line("ERROR: ${t.javaClass.simpleName}: ${t.message ?: "(no message)"}")
        t.cause?.let { log.line("  caused by ${it.javaClass.simpleName}: ${it.message}") }
    }
}
