package dev.myarch.runtime.container

import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One line of output from a container process. */
public data class LogLine(val text: String, val isError: Boolean = false)

/** The result of running a command to completion inside the container. */
public data class ExecResult(
    val exitCode: Int,
    val output: List<String>,
) {
    public val isSuccess: Boolean get() = exitCode == 0

    /** Output as one string, for error messages and logs. */
    public fun text(): String = output.joinToString("\n")
}

/**
 * Runs commands inside the proot container.
 *
 * The class holds no state beyond the spec it was built with, so the same
 * instance can start the long-lived desktop session and also run one-off
 * commands like `pacman -S`.
 */
public class ContainerSession(
    private val baseSpec: ProotSpec,
    private val prootTmpDir: File,
    private val loaderPath: String?,
    /**
     * The directory holding the bundled `lib*.so` helpers.
     *
     * proot links against libtalloc and libandroid-shmem, and the binary we
     * ship carries Termux's `RUNPATH` which does not exist on this device, so
     * the linker has to be told where ours are.
     */
    private val nativeLibraryDir: File? = null,
) {
    /**
     * Starts a process and returns it without waiting.
     *
     * @param command what to run, e.g. `listOf("/bin/bash", "-lc", "quickshell")`
     */
    public fun start(
        command: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
    ): Process {
        val spec = baseSpec.copy(
            command = command,
            environment = baseSpec.environment + extraEnvironment,
        )
        val builder = ProcessBuilder(ProotCommand.build(spec))
        builder.redirectErrorStream(true)
        // proot itself reads these from the host environment, before it has
        // cleared anything for the guest.
        builder.environment().apply {
            nativeLibraryDir?.let { dir ->
                put("LD_LIBRARY_PATH", dir.absolutePath)
            }
            put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
            if (loaderPath != null) {
                // Without the loader, proot cannot start a guest process at all,
                // and the error it prints does not say so.
                put("PROOT_LOADER", loaderPath)
            }
            put("PROOT_NO_SECCOMP", "1")
        }
        return builder.start()
    }

    /**
     * Runs [command] to completion, streaming output to [onLine].
     *
     * @param timeoutSeconds 0 waits forever; package installs legitimately take
     *   minutes, while a status query should not hang the UI
     */
    public fun exec(
        command: List<String>,
        extraEnvironment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 0,
        onLine: (LogLine) -> Unit = {},
    ): ExecResult {
        val process = try {
            start(command, extraEnvironment)
        } catch (io: IOException) {
            return ExecResult(EXIT_COULD_NOT_START, listOf("could not start proot: ${io.message}"))
        }
        val collected = ArrayList<String>()
        val reader = process.inputStream.bufferedReader()
        val pump = Thread({ pump(reader, collected, onLine) }, "myarch-container-output").apply {
            isDaemon = true
            start()
        }
        val finished = if (timeoutSeconds > 0) {
            process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        } else {
            process.waitFor()
            true
        }
        if (!finished) {
            process.destroyForcibly()
            pump.join(PUMP_JOIN_MILLIS)
            return ExecResult(EXIT_TIMEOUT, collected + "timed out after ${timeoutSeconds}s")
        }
        pump.join(PUMP_JOIN_MILLIS)
        return ExecResult(process.exitValue(), collected)
    }

    /** Runs a shell snippet through a login shell, which is what scripts expect. */
    public fun shell(
        script: String,
        extraEnvironment: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 0,
        onLine: (LogLine) -> Unit = {},
    ): ExecResult = exec(
        listOf("/bin/bash", "--login", "-c", script),
        extraEnvironment,
        timeoutSeconds,
        onLine,
    )

    private fun pump(reader: BufferedReader, collected: MutableList<String>, onLine: (LogLine) -> Unit) {
        runCatching {
            reader.useLines { lines ->
                for (line in lines) {
                    synchronized(collected) {
                        if (collected.size < MAX_COLLECTED_LINES) collected.add(line)
                    }
                    onLine(LogLine(line, isError = line.looksLikeError()))
                }
            }
        }
    }

    private fun String.looksLikeError(): Boolean {
        val lower = lowercase()
        return lower.startsWith("error") || lower.contains("error:") || lower.contains("failed")
    }

    public companion object {
        /** Exit code used when proot could not be launched at all. */
        public const val EXIT_COULD_NOT_START: Int = -1

        /** Exit code used when a command exceeded its timeout. */
        public const val EXIT_TIMEOUT: Int = -2

        private const val PUMP_JOIN_MILLIS = 2_000L

        /** Caps memory use when a command produces unbounded output. */
        private const val MAX_COLLECTED_LINES = 5_000
    }
}
