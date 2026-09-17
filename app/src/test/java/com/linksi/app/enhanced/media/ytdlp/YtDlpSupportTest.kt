package com.linksi.app.enhanced.media.ytdlp

import com.linksi.app.enhanced.media.MediaSource
import com.linksi.app.enhanced.media.MediaSourceDetector
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Unit tests for the pure helpers around the site engine: which URLs it accepts
 * ([ytDlpSupports]), which native payload it needs ([YtDlpNativeLibraries]), the command line it is
 * given ([formatSelector], [mimeTypeFor]) and how it reads the result back ([producedFile],
 * [baseNameFor]).
 *
 * These are exactly the decisions that only fail on a device - a wrong ABI library name disables the
 * engine, a wrong format selector downloads the wrong stream - so they are pinned on the JVM where
 * a regression is a red test rather than a broken download.
 */
class YtDlpSupportTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ── What the site engine accepts (spec 16) ─────────────────────────────────────────────────

    @Test
    fun theFivePrimaryTargetsAndYouTubeAreAllAccepted() {
        for (source in MediaSourceDetector.PRIMARY_TARGETS + MediaSource.YOUTUBE) {
            assertTrue(
                "$source must be offered to the site engine",
                ytDlpSupports(source, "https://example.com/a-page")
            )
        }
    }

    @Test
    fun theFivePrimaryTargetsAreTheOnesTheSpecificationNames() {
        assertEquals(
            setOf(
                MediaSource.INSTAGRAM,
                MediaSource.FACEBOOK,
                MediaSource.TIKTOK,
                MediaSource.PINTEREST,
                MediaSource.REDDIT
            ),
            MediaSourceDetector.PRIMARY_TARGETS
        )
    }

    @Test
    fun aGenericHttpsPageIsAccepted() {
        assertTrue(ytDlpSupports(MediaSource.OTHER, "https://some-blog.example/post"))
        assertTrue(ytDlpSupports(MediaSource.UNKNOWN, "https://some-blog.example/post"))
    }

    @Test
    fun aDirectFileIsLeftToTheCheaperExtractor() {
        // The direct extractor has already probed it with two requests; starting Python would only
        // reach the same answer more slowly.
        assertFalse(ytDlpSupports(MediaSource.DIRECT_FILE, "https://cdn.example.com/clip.mp4"))
    }

    @Test
    fun aNonHttpUrlIsNeverAccepted() {
        assertFalse(ytDlpSupports(MediaSource.OTHER, "ftp://example.com/clip.mp4"))
        assertFalse(ytDlpSupports(MediaSource.OTHER, "content://media/external/video/1"))
        assertFalse(ytDlpSupports(MediaSource.OTHER, "example.com/post"))
        assertFalse(ytDlpSupports(MediaSource.OTHER, ""))
    }

    // ── The native payload probe (spec 31) ─────────────────────────────────────────────────────

    @Test
    fun theEngineIsRunnableOnlyWithBothPythonEntries() {
        assertFalse(YtDlpNativeLibraries.isRunnable(emptySet()))
        assertFalse(YtDlpNativeLibraries.isRunnable(setOf(YtDlpNativeLibraries.PYTHON_EXECUTABLE)))
        assertFalse(
            "the standard library archive is not optional",
            YtDlpNativeLibraries.isRunnable(setOf(YtDlpNativeLibraries.PYTHON_EXECUTABLE, "libc++.so"))
        )
        assertTrue(
            YtDlpNativeLibraries.isRunnable(
                setOf(
                    YtDlpNativeLibraries.PYTHON_EXECUTABLE,
                    YtDlpNativeLibraries.PYTHON_STDLIB_ARCHIVE
                )
            )
        )
    }

    @Test
    fun missingFromNamesExactlyWhatIsAbsent() {
        assertEquals(
            listOf(
                YtDlpNativeLibraries.PYTHON_EXECUTABLE,
                YtDlpNativeLibraries.PYTHON_STDLIB_ARCHIVE
            ),
            YtDlpNativeLibraries.missingFrom(setOf("libfoo.so"))
        )
        assertEquals(
            listOf(YtDlpNativeLibraries.PYTHON_EXECUTABLE),
            YtDlpNativeLibraries.missingFrom(
                setOf(YtDlpNativeLibraries.PYTHON_STDLIB_ARCHIVE)
            )
        )
    }

    @Test
    fun muxingCapabilityIsReportedSeparatelyFromRunnability() {
        // A device that can extract but not merge is still worth using: combined streams download
        // fine, only the DASH ones need FFmpeg.
        val pythonOnly = setOf(
            YtDlpNativeLibraries.PYTHON_EXECUTABLE,
            YtDlpNativeLibraries.PYTHON_STDLIB_ARCHIVE
        )
        assertTrue(YtDlpNativeLibraries.isRunnable(pythonOnly))
        assertFalse(YtDlpNativeLibraries.isMuxingCapable(pythonOnly))
        assertTrue(
            YtDlpNativeLibraries.isMuxingCapable(
                pythonOnly + YtDlpNativeLibraries.FFMPEG_EXECUTABLE
            )
        )
    }

    @Test
    fun theNativeNamesAreTheOnesTheAarsActuallyShip() {
        // Read out of the published 0.17.3 AARs; a rename would silently disable the engine.
        assertEquals("libpython.so", YtDlpNativeLibraries.PYTHON_EXECUTABLE)
        assertEquals("libpython.zip.so", YtDlpNativeLibraries.PYTHON_STDLIB_ARCHIVE)
        assertEquals("libffmpeg.so", YtDlpNativeLibraries.FFMPEG_EXECUTABLE)
        assertEquals("libffmpeg.zip.so", YtDlpNativeLibraries.FFMPEG_ARCHIVE)
        assertEquals(2, YtDlpNativeLibraries.REQUIRED.size)
    }

    // ── The format selector (spec 19) ──────────────────────────────────────────────────────────

    @Test
    fun aVideoOnlyFormatIsPairedWithAnAudioStream() {
        assertEquals("137+bestaudio/137/best", formatSelector("137", requiresMuxing = true))
    }

    @Test
    fun aCombinedFormatIsFetchedOnItsOwn() {
        assertEquals("22", formatSelector("22", requiresMuxing = false))
    }

    @Test
    fun withNoFormatChosenTheEngineIsAskedForTheBestItHas() {
        assertEquals("best", formatSelector(null, requiresMuxing = false))
        assertEquals("bestvideo+bestaudio/best", formatSelector(null, requiresMuxing = true))
    }

    @Test
    fun theDirectExtractorsMarkerIsNotAYtDlpFormatId() {
        // "direct" means the direct path already handled this URL; passing it to `-f` would make
        // yt-dlp fail on an unknown format.
        assertEquals("best", formatSelector("direct", requiresMuxing = false))
        assertEquals("bestvideo+bestaudio/best", formatSelector("direct", requiresMuxing = true))
    }

    @Test
    fun aBlankFormatIdIsNoChoiceAtAll() {
        assertEquals("best", formatSelector("", requiresMuxing = false))
        assertEquals("best", formatSelector("   ", requiresMuxing = false))
    }

    // ── Reading the result back ────────────────────────────────────────────────────────────────

    @Test
    fun theNewestCompleteFileIsTheResult() {
        val directory = folder.newFolder("work")
        File(directory, "media.f137.mp4.part").writeBytes(ByteArray(4096))
        File(directory, "empty.m4a").writeBytes(ByteArray(0))
        File(directory, "media.f140.m4a").writeBytes(ByteArray(1024))

        val merged = File(directory, "media.mp4")
        merged.writeBytes(ByteArray(8192))
        merged.setLastModified(System.currentTimeMillis() + 5_000)

        assertEquals(merged, producedFile(directory))
    }

    @Test
    fun aDirectoryWithNothingPublishableHasNoResult() {
        val directory = folder.newFolder("empty-work")
        assertNull(producedFile(directory))

        File(directory, "media.mp4.part").writeBytes(ByteArray(16))
        assertNull("a partial file is not a result", producedFile(directory))
    }

    // ── Naming the published file ──────────────────────────────────────────────────────────────

    @Test
    fun aUserSuppliedExtensionIsReplacedRatherThanNested() {
        // "holiday.mp4" plus a WebM-only site must not become "holiday.mp4.webm".
        assertEquals("holiday", baseNameFor("holiday.mp4"))
        assertEquals("holiday", baseNameFor("holiday.WEBM"))
        assertEquals("My Clip", baseNameFor("My Clip.mkv"))
    }

    @Test
    fun aNameThatMerelyContainsADotIsLeftAlone() {
        assertEquals("Mr. Beast interview", baseNameFor("Mr. Beast interview"))
        assertEquals("v1.2.3", baseNameFor("v1.2.3"))
        assertEquals("notes.draft", baseNameFor("notes.draft"))
        assertEquals("a.verylongextension", baseNameFor("a.verylongextension"))
    }

    @Test
    fun aNameWithNoExtensionIsUnchanged() {
        assertEquals("My Clip", baseNameFor("My Clip"))
        assertEquals("", baseNameFor(""))
        assertEquals("trailing.", baseNameFor("   trailing.  "))
    }

    @Test
    fun theMimeTypeMatchesTheContainer() {
        assertEquals("video/mp4", mimeTypeFor("mp4"))
        assertEquals("video/webm", mimeTypeFor("webm"))
        assertEquals("video/x-matroska", mimeTypeFor("mkv"))
        assertEquals("audio/mp4", mimeTypeFor("m4a"))
        assertEquals("audio/mpeg", mimeTypeFor("mp3"))
        assertEquals("video/mp4", mimeTypeFor("MP4"))
    }

    @Test
    fun anUnknownContainerHasNoMimeTypeRatherThanAWrongOne() {
        // A null lets the platform infer from the file name; a wrong type would mislabel the file
        // in the user's Downloads collection.
        assertNull(mimeTypeFor("xyz"))
        assertNull(mimeTypeFor(""))
    }
}
