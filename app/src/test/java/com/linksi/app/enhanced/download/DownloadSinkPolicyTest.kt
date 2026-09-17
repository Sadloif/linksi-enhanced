package com.linksi.app.enhanced.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DownloadSinkPolicy].
 *
 * Case identifiers in the comments map onto the research brief section 5.4 ("decision ladder") and
 * the specification section 22 (download destinations). What is being pinned is that the storage
 * decision is a pure function of the destination and the API level - no device state, no
 * permission, and never a legacy write permission.
 */
class DownloadSinkPolicyTest {

    private val everySdk = 26..36

    // ── Public Downloads ──────────────────────────────────────────────────────

    @Test
    fun publicDownloadsUsesTheMediaStoreCollectionFromApi29() {
        for (sdk in 29..36) {
            assertEquals(
                "API $sdk must use MediaStore.Downloads",
                SinkKind.MEDIA_STORE_DOWNLOADS,
                DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, sdk)
            )
        }
    }

    @Test
    fun publicDownloadsFallsBackToAppStorageBelowApi29() {
        // There is no MediaStore.Downloads collection on API 26-28 and this app does not request
        // WRITE_EXTERNAL_STORAGE at runtime, so those downloads stay app-private.
        for (sdk in 26..28) {
            assertEquals(
                "API $sdk must stay app-private",
                SinkKind.APP_STORAGE,
                DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, sdk)
            )
        }
    }

    @Test
    fun thePublicDownloadsBoundaryIsExactlyApi29() {
        assertEquals(
            SinkKind.APP_STORAGE,
            DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, 28)
        )
        assertEquals(
            SinkKind.MEDIA_STORE_DOWNLOADS,
            DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, 29)
        )
    }

    // ── App storage ───────────────────────────────────────────────────────────

    @Test
    fun appStorageIsAlwaysAppStorage() {
        for (sdk in everySdk) {
            assertEquals(
                "API $sdk",
                SinkKind.APP_STORAGE,
                DownloadSinkPolicy.forSdk(DownloadDestination.APP_STORAGE, sdk)
            )
        }
    }

    // ── User-selected folder ──────────────────────────────────────────────────

    @Test
    fun userSelectedAlwaysUsesTheStorageAccessFramework() {
        // SAF exists at every supported API level, so the platform version is irrelevant.
        for (sdk in everySdk) {
            assertEquals(
                "API $sdk",
                SinkKind.USER_SELECTED_SAF,
                DownloadSinkPolicy.forSdk(DownloadDestination.USER_SELECTED, sdk)
            )
        }
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    @Test
    fun noDestinationEverNeedsTheLegacyWritePermission() {
        for (destination in DownloadDestination.values()) {
            for (sdk in everySdk) {
                assertFalse(
                    "$destination on API $sdk must not need WRITE_EXTERNAL_STORAGE",
                    DownloadSinkPolicy.requiresLegacyWritePermission(destination, sdk)
                )
            }
        }
    }

    // ── Visibility ────────────────────────────────────────────────────────────

    @Test
    fun publicVisibilityFollowsTheSinkKind() {
        assertTrue(DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, 36).isPubliclyVisible)
        assertTrue(DownloadSinkPolicy.forSdk(DownloadDestination.USER_SELECTED, 26).isPubliclyVisible)
        assertFalse(DownloadSinkPolicy.forSdk(DownloadDestination.APP_STORAGE, 36).isPubliclyVisible)
        assertFalse(DownloadSinkPolicy.forSdk(DownloadDestination.PUBLIC_DOWNLOADS, 26).isPubliclyVisible)
    }

    @Test
    fun isPubliclyVisibleAgreesWithTheChosenKind() {
        for (destination in DownloadDestination.values()) {
            for (sdk in everySdk) {
                val kind = DownloadSinkPolicy.forSdk(destination, sdk)
                assertEquals(
                    "$destination on API $sdk",
                    kind.isPubliclyVisible,
                    DownloadSinkPolicy.isPubliclyVisible(destination, sdk)
                )
            }
        }
    }

    // ── Exhaustiveness ────────────────────────────────────────────────────────

    @Test
    fun everyDestinationAndApiLevelGetsADecision() {
        // A destination added to the enum without a policy rule would fail to compile; this test
        // pins the expected table so a silent behaviour change is caught instead.
        val expected = mapOf(
            DownloadDestination.PUBLIC_DOWNLOADS to "MEDIA_STORE_DOWNLOADS",
            DownloadDestination.APP_STORAGE to "APP_STORAGE",
            DownloadDestination.USER_SELECTED to "USER_SELECTED_SAF"
        )

        for ((destination, kindName) in expected) {
            for (sdk in listOf(26, 28, 29, 33, 34, 36)) {
                val kind = DownloadSinkPolicy.forSdk(destination, sdk)
                assertNotNull("$destination on API $sdk", kind)
                if (destination != DownloadDestination.PUBLIC_DOWNLOADS || sdk >= 29) {
                    assertEquals("$destination on API $sdk", kindName, kind.name)
                }
            }
        }
    }

    @Test
    fun theMediaStoreMinimumApiLevelMatchesThePlatformConstant() {
        assertEquals(29, DownloadSinkPolicy.MEDIA_STORE_DOWNLOADS_MIN_SDK)
        assertEquals(29, DownloadSinkPolicy.SCOPED_STORAGE_MIN_SDK)
    }
}
