package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [FilenameSanitizer].
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_TESTING_PLAN.md section 48. The
 * guarantees being pinned are: valid on Android/FAT, never blank, never over the length budget,
 * and never silently overwriting an existing file.
 */
class FilenameSanitizerTest {

    // ── Illegal characters and separators ──────────────────────────────────────

    @Test
    fun pathSeparatorsAreReplacedNotHonoured() {
        // A slash in a download name must never be able to escape the download folder.
        assertEquals("a_b.mp4", FilenameSanitizer.sanitize("a/b", "mp4"))
        assertEquals("a_b.mp4", FilenameSanitizer.sanitize("a\\b", "mp4"))
        assertEquals("etc_passwd.mp4", FilenameSanitizer.sanitize("/etc/passwd", "mp4"))
        // Once the separators are replaced there is no traversal left: only the leading dots are
        // trimmed, so the result is a normal, harmless file name.
        assertEquals("etc.mp4", FilenameSanitizer.sanitize("../../etc", "mp4"))
        assertFalse(FilenameSanitizer.sanitize("../../etc", "mp4").contains(".."))
        assertEquals("C__Windows.mp4", FilenameSanitizer.sanitize("C:\\Windows", "mp4"))
    }

    @Test
    fun everyIllegalCharacterIsReplacedWithAnUnderscore() {
        for (illegal in listOf(':', '*', '?', '"', '<', '>', '|')) {
            assertEquals(
                "character '$illegal' should be replaced",
                "a_b.mp4",
                FilenameSanitizer.sanitize("a${illegal}b", "mp4")
            )
        }
    }

    @Test
    fun controlCharactersAreRemovedFromTheName() {
        // A tab or a newline is whitespace, so it collapses to a space like any other run of
        // whitespace - not to an underscore.
        assertEquals("a b.mp4", FilenameSanitizer.sanitize("a\nb", "mp4"))
        assertEquals("a b.mp4", FilenameSanitizer.sanitize("a\rb", "mp4"))
        assertEquals("a b.mp4", FilenameSanitizer.sanitize("a\tb", "mp4"))
        assertEquals("a_b.mp4", FilenameSanitizer.sanitize("a\u0000b", "mp4"))
        // A bell is not in the explicit illegal list, so it passes through untouched.
        assertEquals("a\u0007b.mp4", FilenameSanitizer.sanitize("a\u0007b", "mp4"))
    }

    @Test
    fun aControlCharacterIsNeverProducedEvenInTheExtension() {
        val name = FilenameSanitizer.sanitize("clip", "mp\u00004")
        assertEquals("clip.mp4", name)
    }

    // ── Whitespace, dots and trimming ──────────────────────────────────────────

    @Test
    fun internalWhitespaceIsCollapsedToOneSpace() {
        assertEquals("my clip.mp4", FilenameSanitizer.sanitize("my    clip", "mp4"))
        assertEquals("my clip.mp4", FilenameSanitizer.sanitize("my \t\n clip", "mp4"))
    }

    @Test
    fun surroundingWhitespaceIsTrimmed() {
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("   clip   ", "mp4"))
    }

    @Test
    fun leadingAndTrailingDotsAreStripped() {
        // A trailing dot is invalid on Windows and confusing everywhere else.
        assertEquals("clip.mp4", FilenameSanitizer.sanitize(".clip.", "mp4"))
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("...clip...", "mp4"))
    }

    @Test
    fun aNameThatIsOnlyDotsStillProducesAUsableName() {
        assertEquals("linksi-download.mp4", FilenameSanitizer.sanitize("...", "mp4"))
    }

    @Test
    fun leadingUnderscoresAreTrimmedButInnerOnesAreKept() {
        assertEquals("clip_name.mp4", FilenameSanitizer.sanitize("__clip_name__", "mp4"))
    }

    // ── Empty and blank input ─────────────────────────────────────────────────

    @Test
    fun emptyAndBlankInputFallBackToTheDefaultBaseName() {
        assertEquals("linksi-download.mp4", FilenameSanitizer.sanitize("", "mp4"))
        assertEquals("linksi-download.mp4", FilenameSanitizer.sanitize("   ", "mp4"))
        assertEquals("linksi-download.mp4", FilenameSanitizer.sanitize(null, "mp4"))
        assertEquals("linksi-download.mp4", FilenameSanitizer.sanitize("\n\t ", "mp4"))
    }

    @Test
    fun aCustomFallbackBaseIsUsedWhenThereIsNoName() {
        assertEquals("untitled.mp4", FilenameSanitizer.sanitize(null, "mp4", fallbackBase = "untitled"))
        assertEquals("untitled.mp4", FilenameSanitizer.sanitize("///", "mp4", fallbackBase = "untitled"))
    }

    @Test
    fun aHostileAllIllegalNameIsReducedToTheFallback() {
        // "///" becomes "___" and then trims to nothing, so the fallback takes over.
        assertEquals(FilenameSanitizer.DEFAULT_BASE, FilenameSanitizer.sanitize("///", null))
    }

    // ── Reserved device names (FAT/exFAT) ─────────────────────────────────────

    @Test
    fun reservedDeviceNamesAreEscapedWithALeadingUnderscore() {
        assertEquals("_CON.mp4", FilenameSanitizer.sanitize("CON", "mp4"))
        assertEquals("_NUL.mp4", FilenameSanitizer.sanitize("NUL", "mp4"))
        assertEquals("_LPT1.mp4", FilenameSanitizer.sanitize("LPT1", "mp4"))
        assertEquals("_PRN.mp4", FilenameSanitizer.sanitize("PRN", "mp4"))
        assertEquals("_AUX.mp4", FilenameSanitizer.sanitize("AUX", "mp4"))
        assertEquals("_COM1.mp4", FilenameSanitizer.sanitize("COM1", "mp4"))
    }

    @Test
    fun reservedNameMatchingIsCaseInsensitive() {
        assertEquals("_con.mp4", FilenameSanitizer.sanitize("con", "mp4"))
        assertEquals("_Nul.mp4", FilenameSanitizer.sanitize("Nul", "mp4"))
    }

    @Test
    fun aNameThatMerelyContainsAReservedWordIsLeftAlone() {
        assertEquals("CONcert.mp4", FilenameSanitizer.sanitize("CONcert", "mp4"))
        assertEquals("my-NUL.mp4", FilenameSanitizer.sanitize("my-NUL", "mp4"))
    }

    // ── Length budget ─────────────────────────────────────────────────────────

    @Test
    fun aVeryLongNameIsCappedIncludingTheExtension() {
        val longName = "a".repeat(400)
        val name = FilenameSanitizer.sanitize(longName, "mp4")
        assertEquals(120, name.length)
        assertTrue(name.endsWith(".mp4"))
        assertEquals("a".repeat(116) + ".mp4", name)
    }

    @Test
    fun aCustomMaxLengthIsHonoured() {
        val name = FilenameSanitizer.sanitize("a".repeat(100), "mp4", maxLength = 20)
        assertEquals(20, name.length)
        assertEquals("a".repeat(16) + ".mp4", name)
    }

    @Test
    fun withoutAnExtensionTheWholeBudgetIsAvailableForTheBase() {
        val name = FilenameSanitizer.sanitize("a".repeat(400), null)
        assertEquals(120, name.length)
        assertFalse(name.contains('.'))
    }

    @Test
    fun aTinyMaxLengthNeverProducesAnEmptyBase() {
        // The extension reservation is clamped to at least one usable character.
        val name = FilenameSanitizer.sanitize("abcdef", "mp4", maxLength = 3)
        assertTrue("expected a non-empty base, got '$name'", name.isNotEmpty())
        assertTrue(name.endsWith(".mp4"))
    }

    @Test
    fun aVeryLongNameWithTrailingIllegalCharactersStillEndsInTheExtension() {
        val name = FilenameSanitizer.sanitize("a".repeat(118) + "...", "mp4", maxLength = 121)
        assertEquals(121, name.length)
        assertTrue(name.endsWith(".mp4"))
        assertFalse(name.contains(".."))
    }

    // ── Extensions ────────────────────────────────────────────────────────────

    @Test
    fun anExtensionAlreadyPresentInTheNameIsNotDoubled() {
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip.mp4", "mp4"))
        assertEquals("my clip.mp4", FilenameSanitizer.sanitize("my clip.mp4", "mp4"))
        // Case-insensitive on both sides.
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip.MP4", "mp4"))
        // The extension is normalised to the cleaned (lowercased) value.
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip.mp4", "MP4"))
    }

    @Test
    fun aDifferentExtensionIsStillAppended() {
        assertEquals("clip.mp4.mkv", FilenameSanitizer.sanitize("clip.mp4", "mkv"))
        assertEquals("clip.mp4.webm", FilenameSanitizer.sanitize("clip.mp4", "webm"))
    }

    @Test
    fun theExtensionIsNormalised() {
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip", ".MP4"))
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip", "  mp4  "))
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip", "...mp4..."))
        assertEquals("clip.mp4", FilenameSanitizer.sanitize("clip", "mp4!"))
    }

    @Test
    fun anUnusableExtensionIsDroppedRatherThanGuessed() {
        assertEquals("clip", FilenameSanitizer.sanitize("clip", ""))
        assertEquals("clip", FilenameSanitizer.sanitize("clip", "   "))
        assertEquals("clip", FilenameSanitizer.sanitize("clip", ".", ))
        assertEquals("clip", FilenameSanitizer.sanitize("clip", "!!!"))
        assertEquals("clip", FilenameSanitizer.sanitize("clip", null))
    }

    @Test
    fun anAbsurdlyLongExtensionIsTruncatedToEightCharacters() {
        // 8 characters is the FAT limit; anything longer is a MIME subtype, not an extension.
        assertEquals("clip.abcdefgh", FilenameSanitizer.sanitize("clip", "abcdefghij"))
    }

    @Test
    fun theNameDoesNotEndInADotEvenWithoutAnExtension() {
        val name = FilenameSanitizer.sanitize("clip.", null)
        assertFalse(name.endsWith("."))
        assertEquals("clip", name)
    }

    // ── ensureUnique (spec 48: never overwrite) ───────────────────────────────

    @Test
    fun ensureUniqueReturnsTheDesiredNameWhenItIsFree() {
        assertEquals("clip.mp4", FilenameSanitizer.ensureUnique("clip.mp4", emptySet()))
        assertEquals("clip.mp4", FilenameSanitizer.ensureUnique("clip.mp4", setOf("other.mp4")))
    }

    @Test
    fun ensureUniqueInsertsACounterBeforeTheExtension() {
        assertEquals("clip (1).mp4", FilenameSanitizer.ensureUnique("clip.mp4", setOf("clip.mp4")))
        assertEquals(
            "clip (2).mp4",
            FilenameSanitizer.ensureUnique("clip.mp4", setOf("clip.mp4", "clip (1).mp4"))
        )
        assertEquals(
            "clip (3).mp4",
            FilenameSanitizer.ensureUnique("clip.mp4", setOf("clip.mp4", "clip (1).mp4", "clip (2).mp4"))
        )
    }

    @Test
    fun ensureUniqueFindsTheFirstFreeCounter() {
        // It walks upwards from 1 and stops at the first gap, it does not skip past a taken counter.
        assertEquals(
            "clip (1).mp4",
            FilenameSanitizer.ensureUnique("clip.mp4", setOf("clip.mp4", "clip (2).mp4"))
        )
        assertEquals(
            "clip (3).mp4",
            FilenameSanitizer.ensureUnique("clip.mp4", setOf("clip.mp4", "clip (1).mp4", "clip (2).mp4"))
        )
    }

    @Test
    fun ensureUniqueHandlesNamesWithoutAnExtension() {
        assertEquals("readme (1)", FilenameSanitizer.ensureUnique("readme", setOf("readme")))
        assertEquals("clip. (1)", FilenameSanitizer.ensureUnique("clip.", setOf("clip.")))
    }

    @Test
    fun ensureUniqueKeepsADottedBaseIntact() {
        assertEquals("a.b (1).txt", FilenameSanitizer.ensureUnique("a.b.txt", setOf("a.b.txt")))
    }

    @Test
    fun ensureUniqueStopsAtTheAttemptLimit() {
        val existing = (1..5).map { "clip ($it).mp4" } + "clip.mp4"
        assertEquals("clip (6).mp4", FilenameSanitizer.ensureUnique("clip.mp4", existing.toSet(), maxAttempts = 5))
    }

    @Test
    fun ensureUniqueFallsBackAfterExhaustingEveryAttempt() {
        val existing = (1..3).map { "clip ($it).mp4" } + "clip.mp4"
        // maxAttempts = 3 means counters 1..3 are tried; none are free, so the fallback is used.
        assertEquals("clip (4).mp4", FilenameSanitizer.ensureUnique("clip.mp4", existing.toSet(), maxAttempts = 3))
    }

    @Test
    fun ensureUniqueIsCaseSensitiveLikeAndroidFileSystems() {
        // Android's internal storage is case sensitive, so "Clip.mp4" does not clash with "clip.mp4".
        assertEquals("Clip.mp4", FilenameSanitizer.ensureUnique("Clip.mp4", setOf("clip.mp4")))
    }

    // ── extensionForMimeType ──────────────────────────────────────────────────

    @Test
    fun extensionForMimeTypeMapsTheCommonTypes() {
        assertEquals("mp4", FilenameSanitizer.extensionForMimeType("video/mp4"))
        assertEquals("mov", FilenameSanitizer.extensionForMimeType("video/quicktime"))
        assertEquals("mkv", FilenameSanitizer.extensionForMimeType("video/x-matroska"))
        assertEquals("mp3", FilenameSanitizer.extensionForMimeType("audio/mpeg"))
        assertEquals("jpg", FilenameSanitizer.extensionForMimeType("image/jpeg"))
        assertEquals("svg", FilenameSanitizer.extensionForMimeType("image/svg+xml"))
        assertEquals("pdf", FilenameSanitizer.extensionForMimeType("application/pdf"))
        assertEquals("epub", FilenameSanitizer.extensionForMimeType("application/epub+zip"))
    }

    @Test
    fun extensionForMimeTypeIgnoresParameters() {
        // Real servers send "text/plain; charset=utf-8" and similar.
        assertEquals("txt", FilenameSanitizer.extensionForMimeType("text/plain; charset=utf-8"))
        assertEquals("mp4", FilenameSanitizer.extensionForMimeType("video/mp4; codecs=avc1"))
        assertEquals("json", FilenameSanitizer.extensionForMimeType("  application/json ; charset=UTF-8 "))
    }

    @Test
    fun extensionForMimeTypeIsCaseInsensitive() {
        assertEquals("mp4", FilenameSanitizer.extensionForMimeType("VIDEO/MP4"))
        assertEquals("txt", FilenameSanitizer.extensionForMimeType("Text/Plain; Charset=UTF-8"))
    }

    @Test
    fun extensionForMimeTypeFallsBackToTheSubtype() {
        // Every character that is not valid in an extension is dropped, so "x-flv" becomes "xflv"
        // and "octet-stream" becomes "octetstr". A known MIME type always wins over this fallback.
        assertEquals("xflv", FilenameSanitizer.extensionForMimeType("video/x-flv"))
        assertEquals("heic", FilenameSanitizer.extensionForMimeType("image/heic"))
        assertEquals("octetstr", FilenameSanitizer.extensionForMimeType("application/octet-stream"))
        // These are in the table, so they never reach the fallback.
        assertEquals("mkv", FilenameSanitizer.extensionForMimeType("video/x-matroska"))
        assertEquals("webm", FilenameSanitizer.extensionForMimeType("video/webm"))
    }

    @Test
    fun extensionForMimeTypeRejectsInputWithoutASlash() {
        assertNull(FilenameSanitizer.extensionForMimeType("mp4"))
        assertNull(FilenameSanitizer.extensionForMimeType("just some text"))
    }

    @Test
    fun extensionForMimeTypeRejectsBlankAndMissingInput() {
        assertNull(FilenameSanitizer.extensionForMimeType(null))
        assertNull(FilenameSanitizer.extensionForMimeType(""))
        assertNull(FilenameSanitizer.extensionForMimeType("   "))
        assertNull(FilenameSanitizer.extensionForMimeType("; charset=utf-8"))
    }

    @Test
    fun extensionForMimeTypeNeverReturnsUnsafeCharacters() {
        for (mime in listOf("video/x-fl v", "video/*", "video/a/b", "video/..", "video/")) {
            val extension = FilenameSanitizer.extensionForMimeType(mime)
            if (extension != null) {
                assertTrue("'$extension' from '$mime' is not a safe extension", extension.all { it.isLetterOrDigit() })
            }
        }
        assertNull(FilenameSanitizer.extensionForMimeType("video/"))
    }

    @Test
    fun extensionForMimeTypeFeedsSanitizeDirectly() {
        val extension = FilenameSanitizer.extensionForMimeType("text/plain; charset=utf-8")
        assertEquals("notes.txt", FilenameSanitizer.sanitize("notes", extension))
    }

    // ── isSafe ────────────────────────────────────────────────────────────────

    @Test
    fun isSafeAcceptsOrdinaryNames() {
        assertTrue(FilenameSanitizer.isSafe("clip.mp4"))
        assertTrue(FilenameSanitizer.isSafe("my clip.mp4"))
        assertTrue(FilenameSanitizer.isSafe("clip_2024-01-01.mp4"))
        assertTrue(FilenameSanitizer.isSafe("CONcert.mp4"))
    }

    @Test
    fun isSafeRejectsEmptyAndPaddedNames() {
        assertFalse(FilenameSanitizer.isSafe(""))
        assertFalse(FilenameSanitizer.isSafe(" clip.mp4"))
        assertFalse(FilenameSanitizer.isSafe("clip.mp4 "))
        assertFalse(FilenameSanitizer.isSafe("clip\n.mp4"))
    }

    @Test
    fun isSafeRejectsIllegalCharacters() {
        for (illegal in listOf('/', '\\', ':', '*', '?', '"', '<', '>', '|', '\u0000')) {
            assertFalse("'$illegal' should be unsafe", FilenameSanitizer.isSafe("a${illegal}b.mp4"))
        }
    }

    @Test
    fun isSafeRejectsLeadingAndTrailingDots() {
        assertFalse(FilenameSanitizer.isSafe(".hidden"))
        assertFalse(FilenameSanitizer.isSafe("clip."))
        assertTrue(FilenameSanitizer.isSafe("clip.mp4"))
    }

    @Test
    fun isSafeRejectsReservedDeviceNames() {
        assertFalse(FilenameSanitizer.isSafe("CON"))
        assertFalse(FilenameSanitizer.isSafe("NUL"))
        assertFalse(FilenameSanitizer.isSafe("LPT1"))
        assertFalse(FilenameSanitizer.isSafe("con.txt"))
        assertFalse(FilenameSanitizer.isSafe("nul.mp4"))
    }

    @Test
    fun sanitizeOutputAlwaysSatisfiesIsSafe() {
        // The two functions must agree: whatever sanitize produces is by definition safe.
        val rawNames = listOf(
            "clip", "  my   clip  ", "///", "...", "CON", "a:b*c?d", "a\nb",
            "a".repeat(400), "\u0000", "clip.mp4", ".hidden."
        )
        for (extension in listOf(null, "mp4", "MP4", "!!!", "abcdefghij")) {
            for (raw in rawNames) {
                val name = FilenameSanitizer.sanitize(raw, extension)
                assertTrue("sanitize('$raw', '$extension') produced unsafe '$name'", FilenameSanitizer.isSafe(name))
            }
        }
    }
}
