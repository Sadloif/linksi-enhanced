package com.linksi.app.enhanced.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RuntimeCapabilities], [CapabilityReport] and the capability vocabulary.
 *
 * Case identifiers in the comments map onto LINKSI_ENHANCED_REVISED_SPEC.md sections 31 and 32.
 * Every value is injected instead of read from `android.os.Build`, so the whole decision table -
 * "no optional feature is offered on a device that cannot run it" - is testable on the plain JVM.
 */
class RuntimeCapabilitiesTest {

    /** An arm64 device that has granted only the notification permission. */
    private fun modern(
        sdkInt: Int = 34,
        abis: List<String> = listOf("arm64-v8a", "armeabi-v7a"),
        notification: Boolean = true,
        overlay: Boolean = false,
        accessibility: Boolean = false,
        server: Boolean = false
    ) = RuntimeCapabilities(
        sdkInt = sdkInt,
        supportedAbis = abis,
        hasNotificationPermission = notification,
        hasOverlayPermission = overlay,
        accessibilityServiceEnabled = accessibility,
        serverResolverConfigured = server
    )

    // ── ABI decisions (spec 31: the native engine ships per ABI) ────────────────

    @Test
    fun anArm64DeviceSupportsTheLocalMediaEngine() {
        val capabilities = modern(abis = listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(capabilities.supportsLocalMediaEngine)
        assertTrue(capabilities.isArm64)
        assertFalse(capabilities.isLegacy32BitOnly)
        assertEquals("arm64-v8a", capabilities.abi)
    }

    @Test
    fun anArm64DeviceIsStillSupportedWhenItIsTheOnlyAbiReported() {
        val capabilities = modern(abis = listOf("arm64-v8a"))
        assertTrue(capabilities.supportsLocalMediaEngine)
        assertTrue(capabilities.isArm64)
        assertFalse(capabilities.isLegacy32BitOnly)
    }

    @Test
    fun a64BitAbiOtherThanArmCountsAsModernButNotAsArm64() {
        val capabilities = modern(abis = listOf("x86_64"))
        assertTrue(capabilities.supportsLocalMediaEngine)
        assertFalse(capabilities.isArm64)
        assertFalse(capabilities.isLegacy32BitOnly)
        assertEquals("x86_64", capabilities.abi)
    }

    @Test
    fun eachBundledTargetAbiSupportsTheLocalMediaEngine() {
        // The four ABIs the bundled engine is expected to provide (spec 31).
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            assertTrue("$abi should be supported", modern(abis = listOf(abi)).supportsLocalMediaEngine)
        }
    }

    @Test
    fun a32BitOnlyDeviceStillRunsTheLocalMediaEngine() {
        // MEDIA_ENGINE_ABIS contains all four shipped ABIs (arm64-v8a, armeabi-v7a, x86_64, x86),
        // so a 32-bit device downloads locally. isLegacy32BitOnly is what records that it has no
        // 64-bit ABI at all; it must not be conflated with "cannot run the engine".
        val capabilities = modern(abis = listOf("armeabi-v7a", "armeabi"))
        assertTrue(capabilities.supportsLocalMediaEngine)
        assertFalse(capabilities.isArm64)
        assertTrue(capabilities.isLegacy32BitOnly)
        assertEquals("armeabi-v7a", capabilities.abi)
    }

    @Test
    fun a32BitX86DeviceIsAlsoLegacyButStillSupported() {
        val capabilities = modern(abis = listOf("x86"))
        assertTrue(capabilities.supportsLocalMediaEngine)
        assertTrue(capabilities.isLegacy32BitOnly)
        assertFalse(capabilities.isArm64)
    }

    @Test
    fun aMixtureOf32And64BitAbisIsNotLegacy() {
        val capabilities = modern(abis = listOf("armeabi-v7a", "arm64-v8a"))
        assertFalse(capabilities.isLegacy32BitOnly)
        // firstOrNull decides the reported ABI, exactly as Build.SUPPORTED_ABIS would.
        assertEquals("armeabi-v7a", capabilities.abi)
    }

    @Test
    fun anUnknownAbiListIsNeitherArm64NorLegacyNorSupported() {
        val capabilities = modern(abis = emptyList())
        assertEquals("unknown", capabilities.abi)
        assertFalse(capabilities.isArm64)
        assertFalse(capabilities.supportsLocalMediaEngine)
        // "No information" must not be reported as "definitely 32-bit only".
        assertFalse(capabilities.isLegacy32BitOnly)
    }

    @Test
    fun anUnrecognisedAbiIsNotTreatedAsSupported() {
        val capabilities = modern(abis = listOf("riscv64", "mips"))
        assertFalse(capabilities.supportsLocalMediaEngine)
        assertFalse(capabilities.isArm64)
        // It is not one of the two 64-bit ABIs we know, so it counts as the legacy branch.
        assertTrue(capabilities.isLegacy32BitOnly)
        assertEquals("riscv64", capabilities.abi)
    }

    @Test
    fun anEmptyAbiEntryIsUsedVerbatimRatherThanFallbackToUnknown() {
        // firstOrNull only falls back when there is no entry at all.
        assertEquals("", modern(abis = listOf("")).abi)
    }

    // ── SDK level decisions (spec 31) ──────────────────────────────────────────

    @Test
    fun typedForegroundServicesRequireApi34() {
        assertFalse(modern(sdkInt = 26).supportsTypedForegroundServices)
        assertFalse(modern(sdkInt = 33).supportsTypedForegroundServices)
        assertTrue(modern(sdkInt = 34).supportsTypedForegroundServices)
        assertTrue(modern(sdkInt = 35).supportsTypedForegroundServices)
    }

    @Test
    fun mediaStoreDownloadsAndScopedStorageRequireApi29() {
        assertFalse(modern(sdkInt = 26).supportsMediaStoreDownloads)
        assertFalse(modern(sdkInt = 28).supportsScopedStorage)
        assertTrue(modern(sdkInt = 29).supportsMediaStoreDownloads)
        assertTrue(modern(sdkInt = 29).supportsScopedStorage)
        assertTrue(modern(sdkInt = 34).supportsMediaStoreDownloads)
    }

    // ── The core capabilities are unconditional (spec 32) ──────────────────────

    @Test
    fun theCoreCapabilitiesAreAlwaysAvailable() {
        // Linksi must keep working on a worst-case device: API 26, 32-bit, nothing granted.
        val worstCase = RuntimeCapabilities(
            sdkInt = 26,
            supportedAbis = listOf("armeabi-v7a"),
            hasNotificationPermission = false,
            hasOverlayPermission = false,
            accessibilityServiceEnabled = false,
            serverResolverConfigured = false
        )
        val report = worstCase.report()
        for (name in CORE_CAPABILITIES) {
            assertEquals("$name must always be available", CapabilityStatus.AVAILABLE, report.statusOf(name))
            assertTrue(report.isAvailable(name))
        }
    }

    @Test
    fun theCoreCapabilitiesAreAvailableOnEveryDevice() {
        for (sdkInt in listOf(26, 29, 33, 34)) {
            for (abis in listOf(emptyList(), listOf("armeabi-v7a"), listOf("arm64-v8a"))) {
                val report = modern(sdkInt = sdkInt, abis = abis).report()
                for (name in CORE_CAPABILITIES) {
                    assertTrue(
                        "$name should be available on API $sdkInt/$abis",
                        report.isAvailable(name)
                    )
                }
            }
        }
    }

    @Test
    fun coreCapabilitiesCarryNoDetailText() {
        // There is nothing the user has to do about them.
        val report = modern().report()
        for (name in CORE_CAPABILITIES) {
            val capability = report.capabilities.first { it.name == name }
            assertEquals("", capability.detail)
            assertTrue(capability.isAvailable)
        }
    }

    // ── Permission-driven statuses (spec 32) ──────────────────────────────────

    @Test
    fun theFloatingBubbleNeedsTheOverlayPermission() {
        val without = modern(overlay = false, notification = false)
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, without.report().statusOf(CapabilityNames.FLOATING_BUBBLE))
        assertEquals(
            "Display over other apps",
            without.report().capabilities.first { it.name == CapabilityNames.FLOATING_BUBBLE }.detail
        )

        val with = modern(overlay = true)
        assertEquals(CapabilityStatus.AVAILABLE, with.report().statusOf(CapabilityNames.FLOATING_BUBBLE))
        assertEquals(
            "",
            with.report().capabilities.first { it.name == CapabilityNames.FLOATING_BUBBLE }.detail
        )
    }

    @Test
    fun accessibilityDetectionNeedsTheServiceToBeEnabled() {
        val disabled = modern(accessibility = false)
        assertEquals(
            CapabilityStatus.NEEDS_PERMISSION,
            disabled.report().statusOf(CapabilityNames.ACCESSIBILITY_DETECTION)
        )
        assertEquals(
            "Accessibility service disabled",
            disabled.report().capabilities.first { it.name == CapabilityNames.ACCESSIBILITY_DETECTION }.detail
        )

        val enabled = modern(accessibility = true)
        assertEquals(
            CapabilityStatus.AVAILABLE,
            enabled.report().statusOf(CapabilityNames.ACCESSIBILITY_DETECTION)
        )
    }

    @Test
    fun notificationsNeedTheRuntimePermission() {
        val denied = modern(notification = false)
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, denied.report().statusOf(CapabilityNames.NOTIFICATIONS))
        assertEquals(
            "Notification permission not granted",
            denied.report().capabilities.first { it.name == CapabilityNames.NOTIFICATIONS }.detail
        )

        val granted = modern(notification = true)
        assertEquals(CapabilityStatus.AVAILABLE, granted.report().statusOf(CapabilityNames.NOTIFICATIONS))
        assertEquals(
            "",
            granted.report().capabilities.first { it.name == CapabilityNames.NOTIFICATIONS }.detail
        )
    }

    @Test
    fun permissionsDefaultToNotGranted() {
        val defaults = RuntimeCapabilities(sdkInt = 34, supportedAbis = listOf("arm64-v8a"))
        assertFalse(defaults.hasNotificationPermission)
        assertFalse(defaults.hasOverlayPermission)
        assertFalse(defaults.accessibilityServiceEnabled)
        assertFalse(defaults.serverResolverConfigured)
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, defaults.report().statusOf(CapabilityNames.NOTIFICATIONS))
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, defaults.report().statusOf(CapabilityNames.FLOATING_BUBBLE))
        assertEquals(CapabilityStatus.NEEDS_PERMISSION, defaults.report().statusOf(CapabilityNames.ACCESSIBILITY_DETECTION))
    }

    // ── Engine and server availability (spec 31) ──────────────────────────────

    @Test
    fun theLocalDownloaderIsUnavailableWhenNoShippedAbiIsPresent() {
        // An unrecognised ABI means no bundled native engine can run, so the download action must
        // be reported as unavailable and the reason must name the CPU (spec 31).
        val report = modern(abis = listOf("riscv64")).report()
        assertEquals(CapabilityStatus.UNAVAILABLE, report.statusOf(CapabilityNames.LOCAL_DOWNLOADER))
        val detail = report.capabilities.first { it.name == CapabilityNames.LOCAL_DOWNLOADER }.detail
        assertTrue("the detail should name the ABI, got '$detail'", detail.contains("riscv64"))
        assertTrue(detail.contains("Not supported"))
    }

    @Test
    fun theLocalDownloaderIsReportedWhenTheAbiListIsEmpty() {
        val report = modern(abis = emptyList()).report()
        assertEquals(CapabilityStatus.UNAVAILABLE, report.statusOf(CapabilityNames.LOCAL_DOWNLOADER))
        assertTrue(
            report.capabilities.first { it.name == CapabilityNames.LOCAL_DOWNLOADER }.detail.contains("unknown")
        )
    }

    @Test
    fun theLocalDownloaderReportsTheAbiWhenItIsAvailable() {
        val report = modern(abis = listOf("arm64-v8a", "armeabi-v7a")).report()
        assertEquals(CapabilityStatus.AVAILABLE, report.statusOf(CapabilityNames.LOCAL_DOWNLOADER))
        assertEquals(
            "arm64-v8a",
            report.capabilities.first { it.name == CapabilityNames.LOCAL_DOWNLOADER }.detail
        )
    }

    @Test
    fun everyShippedAbiMakesTheLocalDownloaderAvailable() {
        // The bundled engine ships arm64-v8a, armeabi-v7a, x86_64 and x86 (spec 31).
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            val report = modern(abis = listOf(abi)).report()
            assertEquals(
                "the local downloader should be available on $abi",
                CapabilityStatus.AVAILABLE,
                report.statusOf(CapabilityNames.LOCAL_DOWNLOADER)
            )
            assertEquals(
                abi,
                report.capabilities.first { it.name == CapabilityNames.LOCAL_DOWNLOADER }.detail
            )
        }
    }

    @Test
    fun theServerFallbackIsUnavailableUntilAResolverIsConfigured() {
        val notConfigured = modern(server = false).report()
        assertEquals(CapabilityStatus.UNAVAILABLE, notConfigured.statusOf(CapabilityNames.SERVER_FALLBACK))
        assertEquals(
            "No private resolver configured",
            notConfigured.capabilities.first { it.name == CapabilityNames.SERVER_FALLBACK }.detail
        )

        val configured = modern(server = true).report()
        assertEquals(CapabilityStatus.AVAILABLE, configured.statusOf(CapabilityNames.SERVER_FALLBACK))
        assertEquals(
            "",
            configured.capabilities.first { it.name == CapabilityNames.SERVER_FALLBACK }.detail
        )
    }

    @Test
    fun theServerFallbackIsIndependentOfTheLocalEngine() {
        // The fallback must be reported on its own merits: a device with no usable local engine can
        // still be offered the server resolver (spec 24 and 31).
        val report = modern(abis = listOf("riscv64"), server = true).report()
        assertEquals(CapabilityStatus.UNAVAILABLE, report.statusOf(CapabilityNames.LOCAL_DOWNLOADER))
        assertEquals(CapabilityStatus.AVAILABLE, report.statusOf(CapabilityNames.SERVER_FALLBACK))

        val noResolver = modern(abis = listOf("arm64-v8a"), server = false).report()
        assertEquals(CapabilityStatus.AVAILABLE, noResolver.statusOf(CapabilityNames.LOCAL_DOWNLOADER))
        assertEquals(CapabilityStatus.UNAVAILABLE, noResolver.statusOf(CapabilityNames.SERVER_FALLBACK))
    }

    @Test
    fun backgroundDownloadIsAlwaysAvailableButChangesItsMechanism() {
        // Typed foreground service types are mandatory from API 34, but the capability itself is
        // never withheld - only the detail text changes.
        val legacy = modern(sdkInt = 26).report()
        assertEquals(CapabilityStatus.AVAILABLE, legacy.statusOf(CapabilityNames.BACKGROUND_DOWNLOAD))
        assertEquals(
            "Foreground service (legacy)",
            legacy.capabilities.first { it.name == CapabilityNames.BACKGROUND_DOWNLOAD }.detail
        )

        val typed = modern(sdkInt = 34).report()
        assertEquals(CapabilityStatus.AVAILABLE, typed.statusOf(CapabilityNames.BACKGROUND_DOWNLOAD))
        assertEquals(
            "Foreground service (dataSync)",
            typed.capabilities.first { it.name == CapabilityNames.BACKGROUND_DOWNLOAD }.detail
        )
    }

    // ── CapabilityReport bookkeeping ─────────────────────────────────────────

    @Test
    fun theReportNamesTheDeviceItDescribes() {
        val report = modern(sdkInt = 33, abis = listOf("arm64-v8a")).report()
        assertEquals(33, report.sdkInt)
        assertEquals("arm64-v8a", report.abi)
    }

    @Test
    fun theReportContainsEveryKnownCapabilityExactlyOnce() {
        val report = modern().report()
        val expected = setOf(
            CapabilityNames.LINK_MANAGEMENT,
            CapabilityNames.URL_CLEANER,
            CapabilityNames.SHARE_RECEIVER,
            CapabilityNames.FLOATING_BUBBLE,
            CapabilityNames.ACCESSIBILITY_DETECTION,
            CapabilityNames.LOCAL_DOWNLOADER,
            CapabilityNames.SERVER_FALLBACK,
            CapabilityNames.NOTIFICATIONS,
            CapabilityNames.BACKGROUND_DOWNLOAD
        )
        assertEquals(expected, report.capabilities.map { it.name }.toSet())
        assertEquals(expected.size, report.capabilities.size)
        assertTrue(report.capabilities.all { it.name.isNotBlank() })
    }

    @Test
    fun anUnknownCapabilityNameIsReportedAsUnavailable() {
        val report = modern().report()
        assertEquals(CapabilityStatus.UNAVAILABLE, report.statusOf("does_not_exist"))
        assertFalse(report.isAvailable("does_not_exist"))
    }

    @Test
    fun isAvailableOnlyHoldsForTheAvailableStatus() {
        val report = modern(notification = false, overlay = false, accessibility = false, server = false).report()
        assertTrue(report.isAvailable(CapabilityNames.LINK_MANAGEMENT))
        assertFalse(report.isAvailable(CapabilityNames.NOTIFICATIONS))
        assertFalse(report.isAvailable(CapabilityNames.SERVER_FALLBACK))
    }

    // ── summary (spec 32's user-facing shape) ────────────────────────────────

    @Test
    fun theSummaryContainsTheDeviceLines() {
        val summary = modern(sdkInt = 34, abis = listOf("arm64-v8a", "armeabi-v7a")).report().summary()
        val lines = summary.lines()
        assertEquals("Device Compatibility", lines.first())
        assertEquals("", lines[1])
        assertEquals("Android 34", lines[2])
        assertEquals("arm64-v8a", lines[3])
        assertEquals("", lines[4])
        assertTrue(summary.startsWith("Device Compatibility\n\nAndroid 34\narm64-v8a"))
        assertTrue(summary.contains("Android 34"))
        assertTrue(summary.contains("arm64-v8a"))
    }

    @Test
    fun theSummaryMarksAvailableNeedsPermissionAndUnavailableRows() {
        val summary = modern(
            sdkInt = 26,
            abis = listOf("armeabi-v7a"),
            notification = true,
            overlay = false,
            accessibility = true,
            server = false
        ).report().summary()

        assertTrue(summary.contains("\u2713 ${CapabilityNames.LINK_MANAGEMENT}"))
        assertTrue(summary.contains("! ${CapabilityNames.FLOATING_BUBBLE} - Display over other apps"))
        assertTrue(summary.contains("\u2713 ${CapabilityNames.ACCESSIBILITY_DETECTION}"))
        assertTrue(summary.contains("\u2717 ${CapabilityNames.SERVER_FALLBACK} - No private resolver configured"))
        // armeabi-v7a *is* a shipped engine ABI, so this row is a tick that names the CPU.
        assertTrue(summary.contains("\u2713 ${CapabilityNames.LOCAL_DOWNLOADER} - armeabi-v7a"))
        assertTrue(summary.contains("\u2713 ${CapabilityNames.NOTIFICATIONS}"))
        assertTrue(summary.contains("\u2713 ${CapabilityNames.BACKGROUND_DOWNLOAD} - Foreground service (legacy)"))
        assertTrue(summary.contains("armeabi-v7a"))
    }

    @Test
    fun theSummaryIsTrimmedAndHasNoTrailingBlankLine() {
        val summary = modern().report().summary()
        assertEquals(summary.trimEnd(), summary)
        assertFalse(summary.endsWith("\n"))
        assertTrue(summary.isNotBlank())
    }

    @Test
    fun theSummaryListsEveryCapabilityThatTheReportHolds() {
        val report = modern().report()
        val summary = report.summary()
        for (capability in report.capabilities) {
            assertTrue("'${capability.name}' missing from the summary", summary.contains(capability.name))
        }
        assertEquals(report.capabilities.size + 5, summary.lines().size)
    }

    @Test
    fun capabilityIsAvailableOnlyForTheAvailableStatus() {
        assertTrue(Capability(CapabilityNames.URL_CLEANER, CapabilityStatus.AVAILABLE).isAvailable)
        assertFalse(Capability(CapabilityNames.URL_CLEANER, CapabilityStatus.NEEDS_PERMISSION).isAvailable)
        assertFalse(Capability(CapabilityNames.URL_CLEANER, CapabilityStatus.UNAVAILABLE).isAvailable)
        assertEquals("", Capability(CapabilityNames.URL_CLEANER, CapabilityStatus.AVAILABLE).detail)
    }

    @Test
    fun theCapabilityNamesAreStableStringsNotDisplayText() {
        // UI code must never compare display strings, so the identifiers stay lowercase snake_case.
        val names = listOf(
            CapabilityNames.LINK_MANAGEMENT,
            CapabilityNames.URL_CLEANER,
            CapabilityNames.SHARE_RECEIVER,
            CapabilityNames.FLOATING_BUBBLE,
            CapabilityNames.ACCESSIBILITY_DETECTION,
            CapabilityNames.LOCAL_DOWNLOADER,
            CapabilityNames.SERVER_FALLBACK,
            CapabilityNames.BACKGROUND_DOWNLOAD,
            CapabilityNames.NOTIFICATIONS
        )
        assertEquals(names.size, names.toSet().size)
        for (name in names) {
            assertNotNull(name)
            assertTrue("'$name' should be snake_case", name.matches(Regex("[a-z]+(_[a-z]+)*")))
        }
    }

    @Test
    fun thereAreThreePossibleCapabilityStatuses() {
        assertEquals(
            listOf(
                CapabilityStatus.AVAILABLE,
                CapabilityStatus.NEEDS_PERMISSION,
                CapabilityStatus.UNAVAILABLE
            ),
            CapabilityStatus.entries.toList()
        )
    }

    @Test
    fun theAbiConstantsMatchTheValuesAndroidReports() {
        assertEquals("arm64-v8a", RuntimeCapabilities.ABI_ARM64)
        assertEquals("armeabi-v7a", RuntimeCapabilities.ABI_ARMV7)
        assertEquals("x86_64", RuntimeCapabilities.ABI_X86_64)
        assertEquals("x86", RuntimeCapabilities.ABI_X86)
        assertEquals(
            setOf("arm64-v8a", "x86_64"),
            RuntimeCapabilities.SIXTY_FOUR_BIT_ABIS
        )
        assertEquals(
            setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
            RuntimeCapabilities.MEDIA_ENGINE_ABIS
        )
    }

    @Test
    fun capabilityValuesAreComparableByValue() {
        // Equality is by value, so a probe result can be cached and compared in tests.
        assertEquals(modern(), modern())
        assertEquals(modern().hashCode(), modern().hashCode())
        assertFalse(modern() == modern(overlay = true))
        assertFalse(modern() == modern().copy(accessibilityServiceEnabled = true))
        assertEquals(modern(), modern().copy())
    }

    @Test
    fun aRuntimeCapabilitiesValueCanBeRebuiltFromItsComponents() {
        val capabilities = modern(overlay = true, accessibility = true, server = true)
        assertEquals(34, capabilities.sdkInt)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), capabilities.supportedAbis)
        assertTrue(capabilities.hasNotificationPermission)
        assertTrue(capabilities.hasOverlayPermission)
        assertTrue(capabilities.accessibilityServiceEnabled)
        assertTrue(capabilities.serverResolverConfigured)
    }

    private companion object {
        /**
         * The capabilities that must never depend on an optional feature (spec 32). The list is
         * asserted through the report rather than by reading a constant, so a regression that
         * removes one of them from `report()` fails here.
         */
        val CORE_CAPABILITIES = listOf(
            CapabilityNames.LINK_MANAGEMENT,
            CapabilityNames.URL_CLEANER,
            CapabilityNames.SHARE_RECEIVER
        )
    }
}
