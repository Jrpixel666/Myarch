package dev.myarch.runtime.download

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/** Resume, checksum verification and the failure classification. */
class DownloaderTest {

    @get:Rule
    val folder: TemporaryFolder = TemporaryFolder()

    private val payload = ByteArray(5_000) { (it % 251).toByte() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Serves [payload], honouring Range requests. */
    private class FakeHttp(
        private val payload: ByteArray,
        private val honourRange: Boolean = true,
        private val status: Int = 200,
        private val truncateAt: Int? = null,
    ) : HttpSource {
        var lastResumeFrom: Long = -1

        override fun probe(url: String, timeoutMillis: Int): HttpStream =
            HttpStream(if (status == 200) 206 else status, payload.size.toLong(), true, null)

        override fun open(url: String, resumeFrom: Long, timeoutMillis: Int): HttpStream {
            lastResumeFrom = resumeFrom
            if (status !in 200..299) {
                return HttpStream(status, -1, false, null, "HTTP $status")
            }
            val from = if (honourRange) resumeFrom.toInt() else 0
            var body = payload.copyOfRange(from, payload.size)
            truncateAt?.let { body = body.copyOfRange(0, it) }
            return HttpStream(
                statusCode = if (honourRange && resumeFrom > 0) 206 else 200,
                contentLength = (payload.size - from).toLong(),
                isPartial = honourRange && resumeFrom > 0,
                stream = ByteArrayInputStream(body),
            )
        }
    }

    @Test
    fun `downloads a file and reports its checksum`() {
        val destination = folder.newFile("image.tar.gz").also { it.delete() }
        val outcome = Downloader(FakeHttp(payload)).download("https://example/image", destination)
        assertThat(outcome).isInstanceOf(DownloadOutcome.Success::class.java)
        val success = outcome as DownloadOutcome.Success
        assertThat(success.bytes).isEqualTo(payload.size.toLong())
        assertThat(success.sha256).isEqualTo(sha256(payload))
        assertThat(destination.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `resumes from a partial file and still checksums the whole thing`() {
        val destination = folder.newFile("partial")
        destination.writeBytes(payload.copyOfRange(0, 2_000))
        val http = FakeHttp(payload)
        val outcome = Downloader(http).download("https://example/image", destination)
        assertThat(http.lastResumeFrom).isEqualTo(2_000)
        val success = outcome as DownloadOutcome.Success
        // The checksum must cover the bytes we already had, not just the tail.
        assertThat(success.sha256).isEqualTo(sha256(payload))
        assertThat(destination.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `starts over when the server ignores the range header`() {
        val destination = folder.newFile("partial")
        destination.writeBytes(payload.copyOfRange(0, 2_000))
        val outcome = Downloader(FakeHttp(payload, honourRange = false))
            .download("https://example/image", destination)
        val success = outcome as DownloadOutcome.Success
        assertThat(success.sha256).isEqualTo(sha256(payload))
        assertThat(destination.length()).isEqualTo(payload.size.toLong())
    }

    @Test
    fun `a checksum mismatch deletes the file so a retry cannot resume it`() {
        val destination = folder.newFile("image").also { it.delete() }
        val outcome = Downloader(FakeHttp(payload))
            .download("https://example/image", destination, expectedSha256 = "0".repeat(64))
        val failure = outcome as DownloadOutcome.Failed
        assertThat(failure.reason).contains("checksum mismatch")
        assertThat(failure.retryable).isTrue()
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `a truncated transfer is reported rather than accepted`() {
        val destination = folder.newFile("image").also { it.delete() }
        val outcome = Downloader(FakeHttp(payload, truncateAt = 1_000))
            .download("https://example/image", destination)
        val failure = outcome as DownloadOutcome.Failed
        assertThat(failure.reason).contains("ended early")
    }

    @Test
    fun `a 404 is not retryable but a 503 is`() {
        val destination = folder.newFile("image").also { it.delete() }
        val notFound = Downloader(FakeHttp(payload, status = 404))
            .download("https://example/image", destination) as DownloadOutcome.Failed
        assertThat(notFound.retryable).isFalse()
        val unavailable = Downloader(FakeHttp(payload, status = 503))
            .download("https://example/image", destination) as DownloadOutcome.Failed
        assertThat(unavailable.retryable).isTrue()
    }

    @Test
    fun `cancellation stops the download`() {
        val destination = folder.newFile("image").also { it.delete() }
        val outcome = Downloader(FakeHttp(payload))
            .download("https://example/image", destination, isCancelled = { true })
        assertThat(outcome).isEqualTo(DownloadOutcome.Cancelled)
    }

    @Test
    fun `progress reaches the caller with a fraction`() {
        val destination = folder.newFile("image").also { it.delete() }
        var last: DownloadProgress? = null
        Downloader(FakeHttp(payload)).download("https://example/image", destination) { last = it }
        assertThat(last).isNotNull()
        assertThat(last!!.bytesDownloaded).isEqualTo(payload.size.toLong())
        assertThat(last!!.fraction).isEqualTo(1f)
    }

    @Test
    fun `fraction is zero when the server will not say how large the file is`() {
        assertThat(DownloadProgress(100, -1, 0).fraction).isEqualTo(0f)
    }
}
