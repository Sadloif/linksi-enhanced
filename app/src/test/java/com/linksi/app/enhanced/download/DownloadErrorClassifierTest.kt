package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaError
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadErrorClassifier].
 *
 * Two things are pinned here: the vocabulary the user sees (specification section 68 - never a
 * technical exception message) and which failures are worth retrying. Getting the second one wrong
 * either gives up too early or retries a 404 forever.
 */
class DownloadErrorClassifierTest {

    // ── HTTP status codes ─────────────────────────────────────────────────────

    @Test
    fun authenticationAndPermissionFailuresHaveTheirOwnReasons() {
        assertEquals(MediaError.LOGIN_REQUIRED, DownloadErrorClassifier.forHttpCode(401))
        assertEquals(MediaError.LOGIN_REQUIRED, DownloadErrorClassifier.forHttpCode(407))
        assertEquals(MediaError.PRIVATE_CONTENT, DownloadErrorClassifier.forHttpCode(403))
    }

    @Test
    fun aMissingResourceIsReportedAsGone() {
        assertEquals(MediaError.MEDIA_GONE, DownloadErrorClassifier.forHttpCode(404))
        assertEquals(MediaError.MEDIA_GONE, DownloadErrorClassifier.forHttpCode(410))
    }

    @Test
    fun throttlingAndTimeoutsAreNetworkFailures() {
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forHttpCode(408))
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forHttpCode(429))
    }

    @Test
    fun aServerErrorIsReportedAsTheServerBeingUnavailable() {
        assertEquals(MediaError.SERVER_UNAVAILABLE, DownloadErrorClassifier.forHttpCode(500))
        assertEquals(MediaError.SERVER_UNAVAILABLE, DownloadErrorClassifier.forHttpCode(502))
        assertEquals(MediaError.SERVER_UNAVAILABLE, DownloadErrorClassifier.forHttpCode(503))
        assertEquals(MediaError.SERVER_UNAVAILABLE, DownloadErrorClassifier.forHttpCode(599))
    }

    @Test
    fun anythingElseIsAGenericFailure() {
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forHttpCode(400))
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forHttpCode(405))
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forHttpCode(418))
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forHttpCode(0))
    }

    // ── Transient or not ──────────────────────────────────────────────────────

    @Test
    fun onlyConnectionAndServerProblemsAreRetried() {
        assertTrue(DownloadErrorClassifier.isTransient(MediaError.NETWORK))
        assertTrue(DownloadErrorClassifier.isTransient(MediaError.SERVER_UNAVAILABLE))

        for (error in listOf(
            MediaError.UNSUPPORTED_SITE,
            MediaError.PRIVATE_CONTENT,
            MediaError.MEDIA_GONE,
            MediaError.LOGIN_REQUIRED,
            MediaError.EXTRACTOR_FAILED,
            MediaError.NO_FORMATS,
            MediaError.NO_STORAGE,
            MediaError.ENGINE_UNAVAILABLE,
            MediaError.CANCELLED
        )) {
            assertFalse("$error must not be retried", DownloadErrorClassifier.isTransient(error))
        }
    }

    @Test
    fun theHttpCodesAgreeWithTheErrorClassification() {
        assertTrue(DownloadErrorClassifier.isTransientHttpCode(408))
        assertTrue(DownloadErrorClassifier.isTransientHttpCode(429))
        assertTrue(DownloadErrorClassifier.isTransientHttpCode(500))
        assertFalse(DownloadErrorClassifier.isTransientHttpCode(400))
        assertFalse(DownloadErrorClassifier.isTransientHttpCode(403))
        assertFalse(DownloadErrorClassifier.isTransientHttpCode(404))
    }

    // ── Throwables ────────────────────────────────────────────────────────────

    @Test
    fun networkExceptionsAreNetworkFailures() {
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forThrowable(UnknownHostException("no dns")))
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forThrowable(SocketTimeoutException("slow")))
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forThrowable(SSLHandshakeException("bad cert")))
        assertEquals(MediaError.NETWORK, DownloadErrorClassifier.forThrowable(IOException("connection reset")))
    }

    @Test
    fun aSinkFailureKeepsItsOwnReason() {
        assertEquals(
            MediaError.NO_STORAGE,
            DownloadErrorClassifier.forThrowable(
                DownloadSinkException(MediaError.NO_STORAGE, "no space left")
            )
        )
        // A sink is free to classify more precisely; the classifier must not flatten it.
        assertEquals(
            MediaError.CANCELLED,
            DownloadErrorClassifier.forThrowable(
                DownloadSinkException(MediaError.CANCELLED, "closed")
            )
        )
    }

    @Test
    fun asecurityExceptionMeansStorageNotNetwork() {
        assertEquals(MediaError.NO_STORAGE, DownloadErrorClassifier.forThrowable(SecurityException("denied")))
    }

    @Test
    fun programmingErrorsAreNotDressedUpAsNetworkProblems() {
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forThrowable(IllegalStateException("bug")))
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forThrowable(IllegalArgumentException("bad url")))
        assertEquals(MediaError.EXTRACTOR_FAILED, DownloadErrorClassifier.forThrowable(RuntimeException("?")))
    }

    @Test
    fun aSinkFailureIsAlwaysAnIoException() {
        // The downloader catches IOException around storage, so a sink failure must be one.
        val error: IOException = DownloadSinkException(MediaError.NO_STORAGE, "nope")
        assertTrue(error is DownloadSinkException)
    }
}
