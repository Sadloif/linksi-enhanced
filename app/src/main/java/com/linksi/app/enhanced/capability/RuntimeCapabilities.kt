package com.linksi.app.enhanced.capability

/**
 * What the current device can actually do (specification sections 31 and 32).
 *
 * Every value is **injected** rather than read from `android.os.Build`, so this class has no
 * Android dependency and is unit testable on the plain JVM. The Android wiring lives in
 * `CapabilityProbe`, which builds one of these from the real device.
 *
 * The purpose is that no optional feature is ever offered on a device that cannot run it, and no
 * unsupported feature can crash the app: callers ask first, then act.
 */
data class RuntimeCapabilities(
    val sdkInt: Int,
    val supportedAbis: List<String>,
    val hasNotificationPermission: Boolean = false,
    val hasOverlayPermission: Boolean = false,
    val accessibilityServiceEnabled: Boolean = false,
    /** True when an optional server resolver has been configured and enabled by the user. */
    val serverResolverConfigured: Boolean = false
) {

    val abi: String get() = supportedAbis.firstOrNull() ?: "unknown"

    val isArm64: Boolean get() = supportedAbis.contains(ABI_ARM64)

    /** True when the device cannot run any 64-bit ABI the media engine ships. */
    val isLegacy32BitOnly: Boolean
        get() = supportedAbis.isNotEmpty() && supportedAbis.none { it in SIXTY_FOUR_BIT_ABIS }

    /**
     * Whether a native (yt-dlp/FFmpeg based) media engine can run here at all. When this is false
     * the local downloader must be reported as unavailable and the app must offer the server
     * fallback instead of crashing on a missing native library.
     */
    val supportsLocalMediaEngine: Boolean get() = supportedAbis.any { it in MEDIA_ENGINE_ABIS }

    /** Foreground service types are mandatory from API 34. */
    val supportsTypedForegroundServices: Boolean get() = sdkInt >= 34

    /** `MediaStore.Downloads` exists from API 29. */
    val supportsMediaStoreDownloads: Boolean get() = sdkInt >= 29

    val supportsScopedStorage: Boolean get() = sdkInt >= 29

    /** Builds the human-readable capability report shown on the diagnostics screen. */
    fun report(): CapabilityReport {
        val rows = mutableListOf<Capability>()

        fun add(name: String, status: CapabilityStatus, detail: String = "") {
            rows += Capability(name, status, detail)
        }

        // Core Linksi must never depend on any optional capability.
        add(CapabilityNames.LINK_MANAGEMENT, CapabilityStatus.AVAILABLE)
        add(CapabilityNames.URL_CLEANER, CapabilityStatus.AVAILABLE)
        add(CapabilityNames.SHARE_RECEIVER, CapabilityStatus.AVAILABLE)

        add(
            CapabilityNames.FLOATING_BUBBLE,
            if (hasOverlayPermission) CapabilityStatus.AVAILABLE else CapabilityStatus.NEEDS_PERMISSION,
            if (hasOverlayPermission) "" else "Display over other apps"
        )

        add(
            CapabilityNames.ACCESSIBILITY_DETECTION,
            if (accessibilityServiceEnabled) CapabilityStatus.AVAILABLE else CapabilityStatus.NEEDS_PERMISSION,
            if (accessibilityServiceEnabled) "" else "Accessibility service disabled"
        )

        add(
            CapabilityNames.LOCAL_DOWNLOADER,
            if (supportsLocalMediaEngine) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            if (supportsLocalMediaEngine) abi else "Not supported on this CPU architecture ($abi)"
        )

        add(
            CapabilityNames.SERVER_FALLBACK,
            if (serverResolverConfigured) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE,
            if (serverResolverConfigured) "" else "No private resolver configured"
        )

        add(
            CapabilityNames.NOTIFICATIONS,
            if (hasNotificationPermission) CapabilityStatus.AVAILABLE else CapabilityStatus.NEEDS_PERMISSION,
            if (hasNotificationPermission) "" else "Notification permission not granted"
        )

        add(
            CapabilityNames.BACKGROUND_DOWNLOAD,
            if (supportsTypedForegroundServices) CapabilityStatus.AVAILABLE else CapabilityStatus.AVAILABLE,
            if (supportsTypedForegroundServices) "Foreground service (dataSync)" else "Foreground service (legacy)"
        )

        return CapabilityReport(sdkInt, abi, rows)
    }

    companion object {
        const val ABI_ARM64 = "arm64-v8a"
        const val ABI_ARMV7 = "armeabi-v7a"
        const val ABI_X86_64 = "x86_64"
        const val ABI_X86 = "x86"

        val SIXTY_FOUR_BIT_ABIS = setOf(ABI_ARM64, ABI_X86_64)

        /** ABIs a bundled native media engine is expected to provide. */
        val MEDIA_ENGINE_ABIS = setOf(ABI_ARM64, ABI_ARMV7, ABI_X86_64, ABI_X86)
    }
}

/** Stable capability identifiers, so UI code never compares display strings. */
object CapabilityNames {
    const val LINK_MANAGEMENT = "link_management"
    const val URL_CLEANER = "url_cleaner"
    const val SHARE_RECEIVER = "share_receiver"
    const val FLOATING_BUBBLE = "floating_bubble"
    const val ACCESSIBILITY_DETECTION = "accessibility_detection"
    const val LOCAL_DOWNLOADER = "local_downloader"
    const val SERVER_FALLBACK = "server_fallback"
    const val BACKGROUND_DOWNLOAD = "background_download"
    const val NOTIFICATIONS = "notifications"
}

enum class CapabilityStatus { AVAILABLE, NEEDS_PERMISSION, UNAVAILABLE }

data class Capability(
    val name: String,
    val status: CapabilityStatus,
    val detail: String = ""
) {
    val isAvailable: Boolean get() = status == CapabilityStatus.AVAILABLE
}

data class CapabilityReport(
    val sdkInt: Int,
    val abi: String,
    val capabilities: List<Capability>
) {
    fun statusOf(name: String): CapabilityStatus =
        capabilities.firstOrNull { it.name == name }?.status ?: CapabilityStatus.UNAVAILABLE

    fun isAvailable(name: String): Boolean = statusOf(name) == CapabilityStatus.AVAILABLE

    /** Multi-line, user-facing summary in the shape the specification gives in section 32. */
    fun summary(): String = buildString {
        appendLine("Device Compatibility")
        appendLine()
        appendLine("Android $sdkInt")
        appendLine(abi)
        appendLine()
        for (capability in capabilities) {
            val marker = when (capability.status) {
                CapabilityStatus.AVAILABLE -> "\u2713"
                CapabilityStatus.NEEDS_PERMISSION -> "!"
                CapabilityStatus.UNAVAILABLE -> "\u2717"
            }
            append(marker)
            append(' ')
            append(capability.name)
            if (capability.detail.isNotEmpty()) {
                append(" - ")
                append(capability.detail)
            }
            appendLine()
        }
    }.trimEnd()
}
