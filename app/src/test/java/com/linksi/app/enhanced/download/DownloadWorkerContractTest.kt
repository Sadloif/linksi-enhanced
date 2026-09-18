package com.linksi.app.enhanced.download

import com.linksi.app.enhanced.media.MediaBackend
import com.linksi.app.enhanced.media.MediaFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests for the private-server/direct-downloader boundary. */
class DownloadWorkerContractTest {

    @Test
    fun aPrivateServerMuxingFormatCannotEnterTheDirectDownloader() {
        val request = DownloadRequest(
            id = "download",
            url = "https://example.com/post",
            backend = MediaBackend.PRIVATE_SERVER
        )
        val format = MediaFormat(
            id = "video-only",
            label = "720p",
            extension = "mp4",
            directUrl = "https://cdn.example/video.mp4",
            requiresMuxing = true,
            backend = MediaBackend.PRIVATE_SERVER
        )

        assertFalse(canDownloadPrivateServerFormatDirectly(request, format))
    }

    @Test
    fun aCompletePrivateServerArtifactCanEnterTheDirectDownloader() {
        val request = DownloadRequest(
            id = "download",
            url = "https://example.com/post",
            backend = MediaBackend.PRIVATE_SERVER
        )
        val format = MediaFormat(
            id = "complete",
            label = "720p",
            extension = "mp4",
            directUrl = "https://cdn.example/complete.mp4",
            backend = MediaBackend.PRIVATE_SERVER
        )

        assertTrue(canDownloadPrivateServerFormatDirectly(request, format))
    }
}
