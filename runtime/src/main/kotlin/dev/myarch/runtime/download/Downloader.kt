package dev.myarch.runtime.download

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** How far a download has got. */
public data class DownloadProgress(
    val bytesDownloaded: Long,
    /** Total size, or -1 when the server would not say. */
    val totalBytes: Long,
    val bytesPerSecond: Long,
) {
    public val fraction: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** What came of a download attempt. */
public sealed interface DownloadOutcome {
    public data class Success(val file: File, val bytes: Long, val sha256: String) : DownloadOutcome

    /** A failure that another mirror might not have. */
    public data class Failed(val reason: String, val retryable: Boolean) : DownloadOutcome

    public data object Cancelled : DownloadOutcome
}

/**
 * Downloads a file, resuming where it left off.
 *
 * Resuming matters more here than in most apps: a rootfs image is several
 * hundred megabytes, phones move between networks mid-download, and starting
 * again from zero on a metered connection is a real cost. The partial file is
 * kept and extended with a `Range` request; if the server ignores the range we
 * truncate and start over rather than corrupting the file by appending a
 * second copy of the beginning.
 */
public class Downloader(
    private val http: HttpSource = UrlHttpSource(),
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {

    @Suppress("LongMethod", "ReturnCount")
    public fun download(
        url: String,
        destination: File,
        expectedSha256: String? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadOutcome {
        destination.parentFile?.mkdirs()
        var resumeFrom = if (destination.exists()) destination.length() else 0L

        val response = http.open(url, resumeFrom, timeoutMillis)
        response.use {
            if (!response.isSuccess) {
                return DownloadOutcome.Failed(
                    response.errorMessage ?: "HTTP ${response.statusCode}",
                    retryable = response.statusCode == 0 || response.statusCode >= 500,
                )
            }
            if (resumeFrom > 0 && !response.isPartial) {
                // The server sent the whole file despite the Range header.
                resumeFrom = 0
            }
            val total = if (response.isPartial && response.contentLength > 0) {
                resumeFrom + response.contentLength
            } else {
                response.contentLength
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val stream = response.stream ?: return DownloadOutcome.Failed("no response body", retryable = true)

            try {
                RandomAccessFile(destination, "rw").use { out ->
                    if (resumeFrom > 0) {
                        // Fold the bytes we already have into the digest, so the
                        // checksum covers the whole file and not just the tail.
                        out.seek(0)
                        val existing = ByteArray(BUFFER_BYTES)
                        var left = resumeFrom
                        while (left > 0) {
                            val read = out.read(existing, 0, minOf(left, BUFFER_BYTES.toLong()).toInt())
                            if (read <= 0) break
                            digest.update(existing, 0, read)
                            left -= read
                        }
                    } else {
                        out.setLength(0)
                    }
                    out.seek(resumeFrom)

                    val buffer = ByteArray(BUFFER_BYTES)
                    var written = resumeFrom
                    var lastReport = System.nanoTime()
                    var bytesSinceReport = 0L
                    while (true) {
                        if (isCancelled()) return DownloadOutcome.Cancelled
                        val read = stream.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        written += read
                        bytesSinceReport += read
                        val now = System.nanoTime()
                        val elapsed = now - lastReport
                        if (elapsed >= PROGRESS_INTERVAL_NANOS) {
                            val perSecond = bytesSinceReport * NANOS_PER_SECOND / elapsed
                            onProgress(DownloadProgress(written, total, perSecond))
                            lastReport = now
                            bytesSinceReport = 0
                        }
                    }
                    onProgress(DownloadProgress(written, total, 0))

                    val hex = digest.digest().toHex()
                    if (expectedSha256 != null && !hex.equals(expectedSha256, ignoreCase = true)) {
                        // A mismatch means a corrupted transfer or a tampered
                        // mirror. Delete the file: resuming would never recover.
                        destination.delete()
                        return DownloadOutcome.Failed(
                            "checksum mismatch: expected $expectedSha256, got $hex",
                            retryable = true,
                        )
                    }
                    if (total > 0 && written != total) {
                        return DownloadOutcome.Failed(
                            "transfer ended early at $written of $total bytes",
                            retryable = true,
                        )
                    }
                    return DownloadOutcome.Success(destination, written, hex)
                }
            } catch (io: IOException) {
                return DownloadOutcome.Failed(io.message ?: "I/O error", retryable = true)
            }
        }
    }

    public companion object {
        private const val BUFFER_BYTES = 1 shl 16
        private const val PROGRESS_INTERVAL_NANOS = 250_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        public const val DEFAULT_TIMEOUT_MILLIS: Int = 30_000
    }
}

/** Lower-case hex, the form checksum files use. */
public fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
