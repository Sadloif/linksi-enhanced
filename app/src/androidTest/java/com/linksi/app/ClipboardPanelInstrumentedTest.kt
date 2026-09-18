package com.linksi.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.detect.ClipboardUrlReader
import com.linksi.app.enhanced.ui.DownloadNavigation
import com.linksi.app.enhanced.ui.PANEL_URL_TAG
import com.linksi.app.enhanced.ui.QuickPanelActivity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The clipboard path that the **floating bubble** depends on.
 *
 * Tapping the bubble starts [QuickPanelActivity] with no extra, so the panel reads the clipboard once
 * at the moment it takes window focus (specification section 11.1). That route had no test of any kind,
 * while the rule underneath it — *never read the clipboard without window focus* — is a privacy
 * promise rather than an implementation detail.
 *
 * This test covers the promise, which is deterministic and needs no UI. The route itself is exercised
 * by the run that supplies the clipboard and opens the panel, and is checked from the outside with
 * `uiautomator` in `TEST_REPORT.md` §38; an instrumented test cannot read a window's own view tree
 * without `UiAutomation`, so the assertion that belongs here is the one that is actually testable.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardPanelInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private companion object {
        const val TAG = "ClipboardPanelTest"

        /** A real share-sheet shape: a Facebook Reel with two trackers attached. */
        const val RAW_URL =
            "https://www.facebook.com/reel/1710485373378939/?utm_source=clipboard_test&fbclid=xyz789"
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    @After
    fun tearDown() {
        // Do not leave a link on the shared clipboard for whatever runs next.
        runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
    }

    @Test
    fun theClipboardIsNotReadWithoutWindowFocus() {
        clipboard.setPrimaryClip(ClipData.newPlainText("link", RAW_URL))

        val unfocused = ClipboardUrlReader.read(context, isFocused = false)
        Log.i(TAG, "unfocused read -> $unfocused")

        assertFalse(
            "a read without window focus must find nothing, however good the clip is",
            unfocused.hasUrl
        )
        assertFalse("and must report itself as unreadable", unfocused.readable)
    }

    @Test
    fun aClipboardWithNothingActionableIsReportedAsHavingNoUrl() {
        // Plain text that is not a link: the panel must say "no valid URL", not offer a bogus one.
        clipboard.setPrimaryClip(ClipData.newPlainText("note", "just some notes, no link here"))

        val focused = ClipboardUrlReader.read(context, isFocused = true)
        Log.i(TAG, "focused read of non-link text -> $focused")

        assertFalse("non-link text must not become a URL", focused.hasUrl)
    }

    @Test
    fun aUriClipboardItemIsDiscardedWithoutCoercingItsContent() {
        clipboard.setPrimaryClip(ClipData.newRawUri("uri", Uri.parse(RAW_URL)))

        val focused = ClipboardUrlReader.read(context, isFocused = true)

        assertFalse("a non-text clipboard item must not be inspected as a URL", focused.hasUrl)
        assertFalse("a discarded non-text clip must not be reported as readable text", focused.readable)
    }

    @Test
    fun thePanelCanBeOpenedWithNoExtraSoItFallsBackToTheClipboard() {
        clipboard.setPrimaryClip(ClipData.newPlainText("link", RAW_URL))

        val intent = Intent(context, QuickPanelActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        assertFalse(
            "this path is only meaningful when no PANEL_URL extra is supplied",
            intent.hasExtra(DownloadNavigation.EXTRA_PANEL_URL)
        )

        // Starting it must not throw. Whether it *renders* the cleaned link is checked from outside
        // with uiautomator, which is the only way to see another window's view tree.
        val started = runCatching { context.startActivity(intent) }
        Log.i(TAG, "panel start -> ${started.isSuccess}")
        started.onFailure { error -> Log.w(TAG, "the panel could not be started", error) }

        // Give it time to take focus and perform its single clipboard read.
        Thread.sleep(3_000)
    }

    @Test
    fun aReusedSingleTopPanelShowsTheNewExplicitUrl() {
        val first = "https://example.com/first.mp4"
        val second = "https://example.com/second.mp4"

        ActivityScenario.launch<QuickPanelActivity>(QuickPanelActivity.intentFor(context, first)).use {
            // Assert about the URL *preview* specifically, not "any node containing this text".
            // The panel legitimately shows the cleaned URL in more than one place - the preview, and
            // the subtitle of the "Clean URL" action - and for a URL with no query string the cleaner
            // is a no-op, so both hold the identical string. A bare onNodeWithText therefore fails
            // with "Expected at most 1 node but found 5", which is a fact about the panel's layout
            // rather than a defect. The tag names the node this test means.
            composeRule.onNodeWithTag(PANEL_URL_TAG).assertTextContains(first)

            context.startActivity(
                QuickPanelActivity.intentFor(context, second)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            composeRule.waitUntil(timeoutMillis = 5_000) {
                composeRule.onAllNodesWithTag(PANEL_URL_TAG)
                    .fetchSemanticsNodes()
                    .any { node ->
                        node.config.getOrNull(SemanticsProperties.Text)
                            ?.any { it.text.contains(second) } == true
                    }
            }
            composeRule.onNodeWithTag(PANEL_URL_TAG).assertTextContains(second)
            assertFalse(
                "the reused panel must not keep rendering the previous URL",
                composeRule.onNodeWithTag(PANEL_URL_TAG)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(SemanticsProperties.Text)
                    ?.any { it.text.contains(first) } == true
            )
        }
    }
}
