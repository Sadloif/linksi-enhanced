package com.linksi.app

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.preferences.core.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.enhanced.ui.QuickPanelActivity
import com.linksi.app.ui.screens.ShareReceiverActivity
import com.linksi.app.utils.ONBOARDING_COMPLETE
import com.linksi.app.utils.dataStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Focused device checks for behavior that becomes mandatory when targeting Android 16. */
@RunWith(AndroidJUnit4::class)
class Android16CompatibilitySmokeTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedApplicationTargetsAndroid16() {
        assumeTrue("this compatibility check is for Android 16", Build.VERSION.SDK_INT >= 36)
        assertEquals(36, context.applicationInfo.targetSdkVersion)
    }

    @Test
    fun shareReceiverStartsWithEdgeToEdgeEnforced() {
        assumeTrue("this compatibility check is for Android 16", Build.VERSION.SDK_INT >= 36)
        val intent = Intent(context, ShareReceiverActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/shared")
        }

        ActivityScenario.launch<ShareReceiverActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
            composeRule.onNodeWithText(context.getString(R.string.save_to_folder))
                .assertIsDisplayed()
        }
    }

    @Test
    fun quickPanelStartsWithEdgeToEdgeEnforced() {
        assumeTrue("this compatibility check is for Android 16", Build.VERSION.SDK_INT >= 36)
        val intent = QuickPanelActivity.intentFor(context, "https://example.com/file.mp4")

        ActivityScenario.launch<QuickPanelActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                assertFalse(activity.isDestroyed)
                assertTrue(activity.window.decorView.isAttachedToWindow)
            }
            composeRule.onNodeWithText(context.getString(R.string.quick_panel_download_file))
                .assertIsDisplayed()
        }
    }

    @Test
    fun onboardingControlsStayAboveTheNavigationBar() {
        assumeTrue("this compatibility check is for Android 16", Build.VERSION.SDK_INT >= 36)
        runBlocking { context.dataStore.edit { it[ONBOARDING_COMPLETE] = false } }

        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                val controls = composeRule.onNodeWithTag("onboarding_bottom_controls")
                controls.assertIsDisplayed()
                val nextButton = composeRule.onNodeWithText(context.getString(R.string.next))
                nextButton.assertIsDisplayed()

                val nextButtonBottom = nextButton.fetchSemanticsNode().boundsInRoot.bottom
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val navigationBottom = ViewCompat.getRootWindowInsets(decor)
                        ?.getInsets(WindowInsetsCompat.Type.navigationBars())
                        ?.bottom ?: 0
                    assertTrue(
                        "onboarding controls overlap the navigation bar",
                        nextButtonBottom <= decor.height - navigationBottom + 1f
                    )
                }
            }
        } finally {
            runBlocking { context.dataStore.edit { it[ONBOARDING_COMPLETE] = true } }
        }
    }
}
