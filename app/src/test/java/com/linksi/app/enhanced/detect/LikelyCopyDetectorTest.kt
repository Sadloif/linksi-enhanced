package com.linksi.app.enhanced.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LikelyCopyDetector].
 *
 * These are the privacy and false-positive tests for the optional accessibility feature. The cases
 * map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 11, 11.3, 57 and 73.14, and onto the technical
 * constraints in research/ANDROID16_REQUIREMENTS.md section 7.
 *
 * Two properties matter most and are asserted repeatedly below:
 *  - nothing is ever detected without an actionable HTTP(S) URL, and
 *  - nothing is ever detected in a password field or an excluded package.
 */
class LikelyCopyDetectorTest {

    private fun click(
        text: String? = null,
        description: String? = null,
        isPassword: Boolean = false,
        isSensitive: Boolean = false,
        packageName: String? = "com.example.app"
    ) = CopyEventInput(
        eventType = CopyEventType.VIEW_CLICKED,
        text = text,
        contentDescription = description,
        isPassword = isPassword,
        isSensitiveField = isSensitive,
        packageName = packageName
    )

    private fun detection(input: CopyEventInput, ignored: List<String> = emptyList()) =
        LikelyCopyDetector.detect(input, ignored)

    // ── Positive detection ────────────────────────────────────────────────────

    @Test
    fun aCopyLinkControlWithAUrlInItsTextIsDetected() {
        val result = detection(click(text = "Copy link https://example.com/article"))
        assertTrue(result is CopyDetection.Detected)
        assertEquals("https://example.com/article", (result as CopyDetection.Detected).url)
    }

    @Test
    fun aCopyLinkContentDescriptionIsEnoughSignalEvenWithoutTheWordInTheText() {
        val result = detection(
            click(text = "https://www.instagram.com/reel/Cx1y2z3/", description = "Copy link")
        )
        assertTrue(result is CopyDetection.Detected)
        assertEquals(
            "https://www.instagram.com/reel/Cx1y2z3/",
            (result as CopyDetection.Detected).url
        )
    }

    @Test
    fun aBareUrlWithCopyInTheLabelIsDetected() {
        // The classic Android text-selection toolbar "Copy" action, whose text is just the URL.
        val result = detection(click(text = "https://fb.watch/abc123/", description = "Copy"))
        assertTrue(result is CopyDetection.Detected)
    }

    @Test
    fun aPlainLinkWordIsEnoughSignal() {
        val result = detection(click(text = "Link", description = "https://example.com/page"))
        assertTrue(result is CopyDetection.Detected)
        assertEquals("https://example.com/page", (result as CopyDetection.Detected).url)
    }

    @Test
    fun signalMatchingIsCaseInsensitive() {
        assertTrue(detection(click(text = "COPY https://example.com/a")) is CopyDetection.Detected)
        assertTrue(detection(click(text = "copy LINK https://example.com/a")) is CopyDetection.Detected)
        assertTrue(detection(click(text = "https://example.com/a", description = "CoPy LiNk")) is CopyDetection.Detected)
    }

    @Test
    fun trailingSentencePunctuationIsStrippedFromTheDetectedUrl() {
        val result = detection(click(text = "Copy link https://example.com/a."))
        assertEquals("https://example.com/a", (result as CopyDetection.Detected).url)
    }

    @Test
    fun textWasUrlIsTrueOnlyWhenTheWholeTextWasTheUrl() {
        val exact = detection(click(text = "https://example.com/a", description = "Copy"))
        assertTrue((exact as CopyDetection.Detected).textWasUrl)

        val embedded = detection(click(text = "Copy link https://example.com/a", description = "Copy"))
        assertFalse((embedded as CopyDetection.Detected).textWasUrl)
    }

    @Test
    fun aSelectionChangeCarryingAUrlIsDetected() {
        val input = CopyEventInput(
            eventType = CopyEventType.VIEW_TEXT_SELECTION_CHANGED,
            text = "https://example.com/selected",
            packageName = "com.example.browser"
        )
        val result = detection(input)
        assertTrue(result is CopyDetection.Detected)
        assertEquals("https://example.com/selected", (result as CopyDetection.Detected).url)
    }

    @Test
    fun aSelectionChangeWithoutAUrlIsNotDetected() {
        // Selecting ordinary prose must never produce a bubble.
        val input = CopyEventInput(
            eventType = CopyEventType.VIEW_TEXT_SELECTION_CHANGED,
            text = "just some selected words",
            packageName = "com.example.reader"
        )
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(input))
    }

    @Test
    fun localhostIsAnActionableUrlBecauseItHasARealHost() {
        val result = detection(click(text = "Copy http://localhost:8080/admin"))
        assertTrue(result is CopyDetection.Detected)
    }

    // ── Negative: no copy signal ──────────────────────────────────────────────

    @Test
    fun aPlainTextFieldWithoutACopyWordIsIgnored() {
        // Typing a URL into a search box has no copy-related signal and must not show a bubble.
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(text = "https://example.com/a")))
    }

    @Test
    fun aClickOnSomethingUnrelatedIsIgnored() {
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(text = "Settings")))
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(description = "Back")))
    }

    @Test
    fun anEventWithNothingToReadIsIgnored() {
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click()))
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(text = "", description = null)))
    }

    @Test
    fun otherEventTypesAreOnlyConsideredWhenTheyCarryACopyLabelAndAUrl() {
        val signal = CopyEventInput(
            eventType = CopyEventType.WINDOW_CONTENT_CHANGED,
            text = "Copy link https://example.com/a",
            packageName = "com.example.app"
        )
        assertTrue(detection(signal) is CopyDetection.Detected)
    }

    // ── Negative: no URL (the privacy boundary, section 57) ────────────────────

    @Test
    fun aCopyActionWithNoUrlIsDiscarded() {
        // "Copy" is pressed on plain text: nothing must be retained, reported or shown.
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy", description = "Copy")))
        assertEquals(
            CopyDetection.NO_URL,
            detection(click(text = "Copy text", description = "Copy this paragraph of prose"))
        )
    }

    @Test
    fun aBareDomainIsNotAUrl() {
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy link example.com/page")))
    }

    @Test
    fun nonHttpSchemesAreNotDetected() {
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy link ftp://example.com/a")))
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy link mailto:a@example.com")))
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy link content://media/1")))
    }

    @Test
    fun aHostWithoutADotIsNotActionable() {
        assertEquals(CopyDetection.NO_URL, detection(click(text = "Copy link https://intranethost/a")))
    }

    @Test
    fun theUrlMayComeFromTheContentDescriptionWhenTheTextHasNone() {
        val result = detection(click(text = "Copy link", description = "https://example.com/from-description"))
        assertEquals("https://example.com/from-description", (result as CopyDetection.Detected).url)
    }

    @Test
    fun anUnrelatedContentDescriptionCannotShadowACopySignalWithoutAUrl() {
        // Text is "Copy" with no URL; the description mentions an unrelated URL, so it *is* used.
        // This is documented behaviour: the description is a legitimate copy source.
        val result = detection(click(text = "Copy", description = "https://example.com/real"))
        assertTrue(result is CopyDetection.Detected)
    }

    // ── Password and sensitive fields (section 11.3.2, 11.3.3) ─────────────────

    @Test
    fun aPasswordFieldIsNeverInspectedEvenWhenEverythingElseMatches() {
        val result = detection(
            click(
                text = "Copy link https://example.com/secret",
                description = "Copy link",
                isPassword = true
            )
        )
        assertEquals(CopyDetection.PASSWORD_FIELD, result)
    }

    @Test
    fun aPasswordFieldIsRejectedBeforeTheIgnoreListIsConsulted() {
        val result = detection(
            click(text = "Copy https://example.com/a", isPassword = true),
            ignored = listOf("com.example.app")
        )
        assertEquals(CopyDetection.PASSWORD_FIELD, result)
    }

    @Test
    fun aSensitiveFieldIsRejected() {
        val result = detection(
            click(text = "Copy https://example.com/a", isSensitive = true)
        )
        assertEquals(CopyDetection.PASSWORD_FIELD, result)
    }

    // ── Ignore list (section 11.3.10) ─────────────────────────────────────────

    @Test
    fun anIgnoredPackageIsRejectedEvenWhenEverythingElseMatches() {
        val result = detection(
            click(text = "Copy link https://example.com/a", packageName = "com.bank.app"),
            ignored = listOf("com.bank.app")
        )
        assertEquals(CopyDetection.IGNORED_PACKAGE, result)
    }

    @Test
    fun ignoreMatchingIsCaseInsensitiveAndTrimmed() {
        val result = detection(
            click(text = "Copy link https://example.com/a", packageName = "com.Bank.App"),
            ignored = listOf("  COM.bank.app  ")
        )
        assertEquals(CopyDetection.IGNORED_PACKAGE, result)
    }

    @Test
    fun aTrailingDotExcludesTheWholePackageFamily() {
        assertTrue(
            LikelyCopyDetector.isIgnoredPackage(
                "com.bank.app.secure",
                listOf("com.bank.app.")
            )
        )
        assertTrue(
            LikelyCopyDetector.isIgnoredPackage("com.vendor.", listOf("com.vendor."))
        )
    }

    @Test
    fun anEntryDoesNotMatchAPackageThatMerelyStartsWithItWithinOneSegment() {
        // "com.bank.app" must not ignore "com.bank.application.other": package boundaries count.
        assertFalse(
            LikelyCopyDetector.isIgnoredPackage(
                "com.bank.application.other",
                listOf("com.bank.app")
            )
        )
    }

    @Test
    fun aDeeperPackageIsExcludedByItsParentEntry() {
        assertTrue(
            LikelyCopyDetector.isIgnoredPackage(
                "com.bank.app.sub",
                listOf("com.bank.app")
            )
        )
    }

    @Test
    fun blankEntriesAndBlankPackageNamesNeverIgnoreAnything() {
        assertFalse(LikelyCopyDetector.isIgnoredPackage(null, listOf("com.bank.app")))
        assertFalse(LikelyCopyDetector.isIgnoredPackage("", listOf("com.bank.app")))
        assertFalse(LikelyCopyDetector.isIgnoredPackage("   ", listOf("com.bank.app")))
        assertFalse(LikelyCopyDetector.isIgnoredPackage("com.bank.app", listOf("", "   ")))
        assertFalse(LikelyCopyDetector.isIgnoredPackage("com.example", emptyList()))
    }

    @Test
    fun anEmptyIgnoreListLeavesDetectionWorking() {
        val result = detection(click(text = "Copy link https://example.com/a"), ignored = emptyList())
        assertTrue(result is CopyDetection.Detected)
    }

    @Test
    fun theDefaultArgumentMeansTheSameAsAnEmptyIgnoreList() {
        val input = click(text = "Copy link https://example.com/a")
        assertEquals(detection(input, emptyList()), LikelyCopyDetector.detect(input))
    }

    // ── Ignore list parsing ───────────────────────────────────────────────────

    @Test
    fun theIgnoreListRoundTripsThroughStorageFormat() {
        val packages = listOf("com.bank.app", "com.password.manager", "com.auth.app")
        val raw = LikelyCopyDetector.serializeIgnoreList(packages)
        assertEquals(packages, LikelyCopyDetector.parseIgnoreList(raw))
    }

    @Test
    fun theIgnoreListParserAcceptsNewlinesCommasAndSemicolons() {
        val raw = "com.a\ncom.b, com.c;com.d\n\n  com.e  "
        assertEquals(
            listOf("com.a", "com.b", "com.c", "com.d", "com.e"),
            LikelyCopyDetector.parseIgnoreList(raw)
        )
    }

    @Test
    fun theIgnoreListParserDropsDuplicatesAndHandlesNull() {
        assertEquals(listOf("com.a"), LikelyCopyDetector.parseIgnoreList("com.a\ncom.a"))
        assertTrue(LikelyCopyDetector.parseIgnoreList(null).isEmpty())
        assertTrue(LikelyCopyDetector.parseIgnoreList("").isEmpty())
        assertTrue(LikelyCopyDetector.parseIgnoreList("  \n , ; ").isEmpty())
    }

    @Test
    fun serialisingAnEmptyListIsAnEmptyString() {
        assertEquals("", LikelyCopyDetector.serializeIgnoreList(emptyList()))
        assertEquals("", LikelyCopyDetector.serializeIgnoreList(listOf("", "  ")))
    }

    // ── Robustness ────────────────────────────────────────────────────────────

    @Test
    fun hostileInputNeverThrows() {
        val hostile = listOf(
            "https://" + "a".repeat(20_000) + "/x",
            "Copy " + "(".repeat(2_000),
            "\u0000\u0001 Copy link https://example.com/a",
            "Copy link https://example.com/" + ".".repeat(2_000)
        )
        for (text in hostile) {
            // Only the contract matters: a verdict comes back and it never throws.
            LikelyCopyDetector.detect(click(text = text, description = "Copy link"))
        }
    }

    @Test
    fun anAbsurdlyLongSelectionIsTreatedAsADocumentNotACopyAction() {
        val huge = "https://example.com/a " + "x".repeat(10_000)
        val input = CopyEventInput(
            eventType = CopyEventType.VIEW_TEXT_SELECTION_CHANGED,
            text = huge,
            packageName = "com.example.reader"
        )
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(input))
    }

    @Test
    fun aNullPackageNameDoesNotBreakDetection() {
        val input = click(text = "Copy link https://example.com/a", packageName = null)
        assertTrue(detection(input, listOf("com.bank.app")) is CopyDetection.Detected)
    }

    // ── The browser "Copy link address" case (found on the OPPO) ──────────────

    /**
     * The real-world case that failed on the owner's phone: the menu item is labelled with the **link
     * itself** rather than the words "Copy link", so a rule that demanded a copy word in the node's
     * text rejected it. Detection worked for an in-app paste and did nothing for an external copy.
     *
     * The URL arrives in the event's text and the label says "copy", which is what the platform
     * exposes for this control.
     */
    @Test
    fun aClickLabelledCopyWithTheUrlInTheEventIsDetected() {
        val input = CopyEventInput(
            eventType = CopyEventType.VIEW_CLICKED,
            text = "https://example.com/a",
            contentDescription = "Copy link address",
            packageName = "com.android.chrome"
        )
        val result = detection(input)
        assertTrue("expected a detection, got $result", result is CopyDetection.Detected)
        assertEquals("https://example.com/a", (result as CopyDetection.Detected).url)
    }

    /** The label may be on the text while the description carries the link; either way it is a copy. */
    @Test
    fun aClickWhoseDescriptionCarriesTheLinkIsAlsoDetected() {
        val input = CopyEventInput(
            eventType = CopyEventType.VIEW_CLICKED,
            text = "Copy link",
            contentDescription = "https://example.com/b",
            packageName = "com.android.chrome"
        )
        val result = detection(input)
        assertTrue("expected a detection, got $result", result is CopyDetection.Detected)
        assertEquals("https://example.com/b", (result as CopyDetection.Detected).url)
    }

    /**
     * The property the new signal must not break: a click on a plain text field that happens to hold
     * a URL is not a copy, because nothing about the control says "copy".
     */
    @Test
    fun aUrlInAPlainTextFieldIsStillIgnoredEvenWhenClicked() {
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(text = "https://example.com/a")))
        assertEquals(
            CopyDetection.NO_COPY_SIGNAL,
            detection(click(text = "https://example.com/a", description = "Search"))
        )
    }

    /**
     * A label carrying a copy word but no URL anywhere is a copy signal with nothing to act on, so the
     * verdict is `NO_URL` rather than `NO_COPY_SIGNAL`. Either way **no bubble is shown**, which is the
     * property that matters; the distinction is only which rule declined, and `NO_URL` is the honest
     * description of "we thought you copied, but there was no link in the event".
     */
    @Test
    fun aCopyLabelWithNoUrlAtAllYieldsNoUrl() {
        assertEquals(
            CopyDetection.NO_URL,
            detection(click(description = "Copy link address"))
        )
    }

    /**
     * A paragraph that merely contains the word "link" must not count as a control label, or any page
     * text mentioning links would arm the bubble.
     */
    @Test
    fun aLongProseLabelContainingTheWordLinkIsNotACopyControl() {
        val prose = "This page explains how to link your account, see the link below for details " +
            "about linking and unlinking, and follow the link to continue reading https://example.com/a"
        assertEquals(CopyDetection.NO_COPY_SIGNAL, detection(click(text = prose)))
    }

    /**
     * The weaker signals only count on a click. A page that merely *mentions* links must not arm the
     * bubble just because a content or window change carried a URL - that is navigation, not a copy.
     *
     * An exact label such as "Copy link address" is a different matter and is accepted on any event
     * type, because those words cannot plausibly be page content.
     */
    @Test
    fun proseMentioningCopyOrLinkOnlyCountsOnAClick() {
        val prose = "Read this article to link your account https://example.com/a"
        assertEquals(
            CopyDetection.NO_COPY_SIGNAL,
            detection(
                CopyEventInput(
                    eventType = CopyEventType.WINDOW_STATE_CHANGED,
                    text = prose,
                    packageName = "com.example.reader"
                )
            )
        )
        assertEquals(
            CopyDetection.NO_COPY_SIGNAL,
            detection(
                CopyEventInput(
                    eventType = CopyEventType.WINDOW_CONTENT_CHANGED,
                    contentDescription = "link",
                    text = "https://example.com/a",
                    packageName = "com.example.reader"
                )
            )
        )
    }

    // ── Select a link, then tap "Copy" (the owner's WhatsApp/Brave case) ──────

    /**
     * The exact flow the owner reported failing: select a link in another app and tap **Copy**. The
     * platform delivers that as a click on a control labelled "Copy" with **no URL in the event**, so
     * on its own it has a copy label and nothing to copy - which is why it was rejected while pasting
     * inside Linksi worked.
     *
     * The URL comes from the selection the click refers to.
     */
    @Test
    fun aCopyClickResolvesToTheJustSelectedUrl() {
        // `atMs` is the wall clock, so a genuinely fresh selection has to be stamped relative to now.
        val selection = RecentSelection("https://example.com/selected", atMs = System.currentTimeMillis())
        val copyClick = click(description = "Copy")

        val result = LikelyCopyDetector.detect(
            copyClick,
            ignoredPackages = emptyList(),
            recentSelection = selection
        )
        assertTrue("expected the click to resolve to the selection, got $result", result is CopyDetection.Detected)
        assertEquals("https://example.com/selected", (result as CopyDetection.Detected).url)
    }

    /**
     * The bound that makes retaining a selection acceptable: it expires. A stale selection must not
     * let an unrelated later "Copy" tap produce a bubble.
     */
    @Test
    fun aStaleSelectionIsNotUsed() {
        val stale = RecentSelection(
            "https://example.com/old",
            atMs = System.currentTimeMillis() - RecentSelection.MAX_AGE_MS - 1
        )
        val result = LikelyCopyDetector.detect(
            click(description = "Copy"),
            ignoredPackages = emptyList(),
            recentSelection = stale
        )
        assertEquals(CopyDetection.NO_URL, result)
    }

    /** With no selection at all, a bare Copy click remains unusable - correctly. */
    @Test
    fun aCopyClickWithNoSelectionHasNothingToCopy() {
        assertEquals(
            CopyDetection.NO_URL,
            LikelyCopyDetector.detect(click(description = "Copy"), emptyList(), null)
        )
    }

    /** A URL in the event wins over the remembered selection, which is only ever a fallback. */
    @Test
    fun theEventsOwnUrlWinsOverTheRememberedSelection() {
        val selection = RecentSelection("https://example.com/old", atMs = System.currentTimeMillis())
        val result = LikelyCopyDetector.detect(
            click(text = "https://example.com/current", description = "Copy link"),
            emptyList(),
            selection
        )
        assertEquals("https://example.com/current", (result as CopyDetection.Detected).url)
    }
}
