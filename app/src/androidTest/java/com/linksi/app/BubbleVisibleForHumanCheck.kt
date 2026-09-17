package com.linksi.app

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.bubble.BubbleService
import com.linksi.app.enhanced.bubble.BubbleSettings
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Leaves a **live floating bubble on the device** for a human to look at.
 *
 * Every automated check on this module passes — the service binds with its four event types, the
 * overlay permission is granted, `BubbleService` reports adding a `TYPE_APPLICATION_OVERLAY` window,
 * the tap decision is pinned by unit tests, and the route a tap leads to (panel opened with no extra,
 * reading the clipboard, showing the link cleaned) has its own test. The one thing no check here can
 * settle is whether a person can **see** the circle, because producing a real link-copy gesture
 * remotely is impossible on this ROM: there is no clipboard helper, no Chrome, and a synthetic
 * long-press produces no text selection.
 *
 * So this test does the only useful remaining thing: it shows the bubble and leaves it up, so the
 * owner can confirm it in one glance and tap it. It is deliberately **not** run as part of the normal
 * suite — its companion `BubbleOverlayInstrumentedTest` stops its bubble immediately — and it is named
 * so that an unattended run does not leave a floating window on the device for minutes.
 *
 * Run it with:
 * `am instrument -w -e class com.linksi.app.BubbleVisibleForHumanCheck com.linksi.app.debug.test/…`
 */
@RunWith(AndroidJUnit4::class)
class BubbleVisibleForHumanCheck {

    private companion object {
        const val TAG = "BubbleHumanCheck"

        /** Long enough to pick the phone up and look; the bubble can also be dismissed by tapping. */
        const val VISIBLE_SECONDS = 300
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun aBubbleIsLeftOnScreenToBeSeenAndTapped() {
        assumeTrue(
            "the overlay permission must be granted, or no bubble can be drawn",
            Settings.canDrawOverlays(context)
        )

        val shown = BubbleService.show(
            context,
            BubbleSettings(autoDismissSeconds = VISIBLE_SECONDS, position = "right")
        )
        Log.i(TAG, "show() returned $shown; the bubble should now be visible on the right edge")
        assertTrue("the service must start", shown)

        // Confirm from the service's own log that the view was accepted rather than refused, then wait
        // without stopping it. The caller is expected to read the screen; nothing here asserts pixels.
        Thread.sleep(3_000)
        val serviceLog = runCatching {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val output = java.io.ByteArrayOutputStream()
            val descriptor = automation.executeShellCommand("logcat -d -s BubbleService:V")
            java.io.FileInputStream(descriptor.fileDescriptor).use { it.copyTo(output) }
            descriptor.close()
            output.toString(Charsets.UTF_8.name())
        }.getOrDefault("")
        Log.i(TAG, "BubbleService said: ${serviceLog.takeLast(300)}")

        // Hold the instrumentation so the bubble is not dismissed when the test process ends early.
        Thread.sleep(VISIBLE_SECONDS * 1000L)
    }
}
