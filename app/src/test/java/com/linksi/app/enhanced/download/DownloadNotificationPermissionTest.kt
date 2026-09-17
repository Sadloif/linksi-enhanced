package com.linksi.app.enhanced.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadNotificationPermission.shouldRequest] - the "should the
 * `POST_NOTIFICATIONS` dialog be shown now?" rule (specification sections 33 and 56, testing plan
 * section 56's granted/denied/revoked matrix).
 *
 * The rule is pure on purpose: the decision is the part that can get the *policy* wrong (asking at
 * launch, asking for a feature that is off, asking twice, asking on a release where the permission
 * does not exist), and every one of those mistakes is silent on a device. Pinning them here is what
 * keeps the two UI triggers honest.
 */
class DownloadNotificationPermissionTest {

    /** The last release without the permission. */
    private val android12 = 32

    /** The first release with it. */
    private val android13 = 33

    /** The newest release the project targets (targetSdk 36 work). */
    private val android16 = 36

    // ── The context that must be present ──────────────────────────────────────

    @Test
    fun android13WithTheFeatureOnAndThePermissionMissingAsks() {
        assertTrue(
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android13,
                enabled = true,
                granted = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun aLaterReleaseBehavesLikeAndroid13() {
        assertTrue(
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android16,
                enabled = true,
                granted = false,
                alreadyAsked = false
            )
        )
    }

    // ── The four refusals ─────────────────────────────────────────────────────

    @Test
    fun android12NeverAsksBecauseThePermissionDoesNotExistThere() {
        assertFalse(
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android12,
                enabled = true,
                granted = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun aFeatureThatIsOffNeverAsks() {
        assertFalse(
            "an optional feature must not request its permission while it is disabled",
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android13,
                enabled = false,
                granted = false,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun anAlreadyGrantedPermissionIsNeverAskedForAgain() {
        assertFalse(
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android13,
                enabled = true,
                granted = true,
                alreadyAsked = false
            )
        )
    }

    @Test
    fun aDialogAlreadyShownInThisVisitIsNotShownTwice() {
        assertFalse(
            DownloadNotificationPermission.shouldRequest(
                sdkInt = android13,
                enabled = true,
                granted = false,
                alreadyAsked = true
            )
        )
    }

    // ── Combinations ─────────────────────────────────────────────────────────

    @Test
    fun everyReasonToRefuseAppliesOnItsOwn() {
        // A denial that was not re-asked, on a feature that is on: the only asking combination.
        assertTrue(
            DownloadNotificationPermission.shouldRequest(android13, enabled = true, granted = false, alreadyAsked = false)
        )
        // Turning the feature off after a denial must not produce a new dialog on the next start.
        assertFalse(
            DownloadNotificationPermission.shouldRequest(android13, enabled = false, granted = false, alreadyAsked = true)
        )
        // Old release, feature on, nothing granted: still nothing to ask for.
        assertFalse(
            DownloadNotificationPermission.shouldRequest(android12, enabled = true, granted = false, alreadyAsked = true)
        )
    }

    @Test
    fun theApiBoundaryIsExactly33() {
        // Pins the version the permission arrived in, so a future refactor cannot shift the line by
        // one and start asking on Android 12 (or stop asking on 13).
        for (sdk in 26 until 33) {
            assertFalse(
                "API $sdk has no POST_NOTIFICATIONS",
                DownloadNotificationPermission.shouldRequest(sdk, true, false, false)
            )
        }
        for (sdk in 33..36) {
            assertTrue(
                "API $sdk must ask",
                DownloadNotificationPermission.shouldRequest(sdk, true, false, false)
            )
        }
    }
}
