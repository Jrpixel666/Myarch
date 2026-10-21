package dev.myarch.runtime.download

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** A byte stream plus the metadata the downloader needs to resume. */
public class HttpStream(
    public val statusCode: Int,
    /** Length of *this response*, which for a resumed request is the remainder. */
    public val contentLength: Long,
    /** True when the server honoured our `Range` header. */
    public val isPartial: Boolean,
    public val stream: InputStream?,
    public val errorMessage: String? = null,
) : AutoCloseable {
    public val isSuccess: Boolean get() = statusCode in SUCCESS_RANGE && stream != null

    override fun close() {
        runCatching { stream?.close() }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299
    }
}

/**
 * HTTP, abstracted so the installer can be tested without a network.
 *
 * Only the two operations the installer needs are exposed: probe a URL (for
 * the mirror switcher) and open a stream, optionally resuming at a byte
 * offset.
 */
public interface HttpSource {
    /** Checks reachability without downloading the body. */
    public fun probe(url: String, timeoutMillis: Int): HttpStream

    /** Opens [url], asking the server to start at [resumeFrom]. */
    public fun open(url: String, resumeFrom: Long, timeoutMillis: Int): HttpStream
}

/** [HttpSource] backed by `HttpURLConnection`. */
public class UrlHttpSource(
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpSource {

    override fun probe(url: String, timeoutMillis: Int): HttpStream = try {
        val connection = connect(url, timeoutMillis)
        // A plain HEAD is refused or mishandled by some mirrors, so ask for the
        // first byte instead: that exercises the same path a download takes.
        connection.setRequestProperty("Range", "bytes=0-0")
        connection.connect()
        val status = connection.responseCode
        val length = contentRangeTotal(connection) ?: connection.contentLengthLong
        connection.inputStream?.close()
        connection.disconnect()
        HttpStream(status, length, status == HTTP_PARTIAL, stream = null)
    } catch (error: Exception) {
        HttpStream(0, -1, false, null, error.describe())
    }

    override fun open(url: String, resumeFrom: Long, timeoutMillis: Int): HttpStream = try {
        val connection = connect(url, timeoutMillis)
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=$resumeFrom-")
        }
        connection.connect()
        val status = connection.responseCode
        if (status in 200..299) {
            HttpStream(status, connection.contentLengthLong, status == HTTP_PARTIAL, connection.inputStream)
        } else {
            val message = runCatching { connection.responseMessage }.getOrNull() ?: "HTTP $status"
            connection.errorStream?.close()
            HttpStream(status, -1, false, null, message)
        }
    } catch (error: Exception) {
        HttpStream(0, -1, false, null, error.describe())
    }

    private fun connect(url: String, timeoutMillis: Int): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Accept-Encoding", "identity")
        // Mirrors redirect between http and https (os.archlinuxarm.org always
        // does), which HttpURLConnection refuses to follow automatically.
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun contentRangeTotal(connection: HttpURLConnection): Long? {
        val header = connection.getHeaderField("Content-Range") ?: return null
        return header.substringAfterLast('/').toLongOrNull()
    }

    private fun Exception.describe(): String =
        "${this::class.java.simpleName}: ${message ?: "no detail"}"

    public companion object {
        private const val HTTP_PARTIAL = 206
        public const val DEFAULT_USER_AGENT: String = "Myarch-Installer/1.0 (+https://github.com/Jrpixel666/Myarch)"
    }
}
