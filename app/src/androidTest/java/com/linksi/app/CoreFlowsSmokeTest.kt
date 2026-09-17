package com.linksi.app

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linksi.app.data.db.LinksDatabase
import com.linksi.app.utils.ONBOARDING_COMPLETE
import com.linksi.app.utils.dataStore
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/**
 * On-device smoke tests for the flows the specification says must keep working (sections 46 and 66),
 * plus the URL cleaner's acceptance criterion (section 70.12) verified end to end on a real device
 * rather than only in unit tests.
 *
 * These run against the real `MainActivity`, the real Hilt graph and the real Room database, so a
 * regression in the save path fails here even when every unit test still passes.
 */
@RunWith(AndroidJUnit4::class)
class CoreFlowsSmokeTest {

    private companion object {
        const val DB_NAME = "linksi_db"
    }

    private val composeRule = createAndroidComposeRule<MainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Marks onboarding complete *before* the activity launches, otherwise the app shows the
     * onboarding pager instead of the home screen and every assertion below would be meaningless.
     */
    private val prepareApp: TestRule = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                runBlocking { context.dataStore.edit { it[ONBOARDING_COMPLETE] = true } }
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(prepareApp).around(composeRule)

    // ── helpers ─────────────────────────────────────────────────────────────────

    private fun text(resourceId: Int): String = context.getString(resourceId)

    /** True when the stored link exists, read through a second connection to the same database. */
    private fun storedLinkExists(url: String): Boolean {
        val database = Room.databaseBuilder(context, LinksDatabase::class.java, DB_NAME).build()
        return try {
            runBlocking { database.linkDao().getLinkByUrl(url) != null }
        } finally {
            database.close()
        }
    }

    /** Opens the add-link sheet, types [url] into the URL field and taps Save. */
    private fun saveLink(url: String) {
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription(text(R.string.save_link)).performClick()
        composeRule.waitForIdle()

        // The placeholder identifies the URL field precisely; the sheet also has title, description,
        // tags and note fields, so indexing by position would be brittle.
        composeRule.onNode(hasSetTextAction() and hasText(text(R.string.paste_url_hint)))
            .performTextInput(url)
        composeRule.waitForIdle()

        // The sheet header carries the same label, so target the tagged primary button.
        composeRule.onNodeWithTag("add_link_save").performClick()
        composeRule.waitForIdle()
    }

    // ── tests ───────────────────────────────────────────────────────────────────

    @Test
    fun appLaunchesAndShowsTheHomeScreen() {
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription(text(R.string.save_link)).assertExists()
    }

    @Test
    fun savingATrackingHeavyUrlStoresTheCleanedForm() {
        val expected = "https://example.com/article?id=42"

        saveLink("https://example.com/article?id=42&utm_source=facebook&utm_medium=social&utm_campaign=test")

        // waitUntil throws on timeout, which is the assertion; polling the database avoids racing the
        // insert coroutine that the save runs in.
        composeRule.waitUntil(timeoutMillis = 20_000) { storedLinkExists(expected) }
    }

    /**
     * The exact example the specification gives in section 9.4 and the testing plan in section 19.
     * This is acceptance criterion 70.12: "The Facebook example cleans correctly".
     */
    @Test
    fun theSpecificationFacebookReelExampleIsCleanedOnSave() {
        val expected = "https://www.facebook.com/reel/1710485373378939"

        saveLink(
            "https://www.facebook.com/reel/1710485373378939/?referral_source=external_link" +
                "&surface_type=tab&in_reels_tab_context=TRUE"
        )

        composeRule.waitUntil(timeoutMillis = 20_000) { storedLinkExists(expected) }
    }

    /**
     * Specification section 9.2 / acceptance criterion 70.13: path and query case must survive the
     * save path. This is the regression that the whole URL-normalisation fix exists for.
     */
    @Test
    fun mixedCaseInPathAndQueryIsPreservedOnSave() {
        val expected = "https://example.com/File?id=AbC123"

        saveLink(expected)

        composeRule.waitUntil(timeoutMillis = 20_000) { storedLinkExists(expected) }
    }
}
