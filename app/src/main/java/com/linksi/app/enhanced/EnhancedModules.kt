package com.linksi.app.enhanced

/**
 * Defaults for every optional enhanced module (specification sections 8 and 34).
 *
 * The rule the whole enhanced build depends on: **core Linksi never requires any of this**. Every
 * module is off unless the user turns it on, with the single exception of URL cleaning, whose
 * recommended default is enabled (specification section 9.5) and which cannot fail a save.
 *
 * Keeping the defaults in one place means the settings screen, the first-run setup and the tests
 * cannot drift apart.
 */
object EnhancedFeatureDefaults {

    /** Specification 9.5: cleaning on save is on by default. */
    const val URL_CLEANING = true

    /** Specification 11: smart detection is opt-in. */
    const val SMART_LINK_DETECTION = false

    /** Specification 12.2: the bubble is opt-in. */
    const val FLOATING_BUBBLE = false

    /** Specification 11.2: the accessibility service is opt-in and never required. */
    const val ACCESSIBILITY_ASSISTANCE = false

    /** Feature toggles that gate whole subsystems. */
    const val LOCAL_MEDIA_DOWNLOADER = true
    const val DIRECT_FILE_DOWNLOADER = true
    const val DOWNLOAD_NOTIFICATIONS = true

    /** Specification 24: server fallback is off and stays off until configured. */
    const val SERVER_FALLBACK = false

    /** Specification 12.2 defaults for the bubble. */
    const val BUBBLE_AUTO_DISMISS_SECONDS = 10
    const val BUBBLE_POSITION = "remember"
    const val BUBBLE_SIZE = "normal"

    /** Specification 11: only HTTP/HTTPS links are ever processed. */
    const val ONLY_HTTP_HTTPS = true

    /** Specification 22: ask before writing outside the default folder. */
    const val ORGANIZE_BY_SOURCE = false
    const val USE_ORIGINAL_FILENAME = true
}

/**
 * Stable preference keys for the enhanced modules, so settings and tests agree on the names.
 * They live in the existing `linksi_settings` DataStore alongside the baseline keys.
 */
object EnhancedPreferenceKeys {
    const val AUTO_CLEAN_URLS = "auto_clean_urls"
    const val SMART_LINK_DETECTION = "smart_link_detection"
    const val FLOATING_BUBBLE = "floating_bubble"
    const val ACCESSIBILITY_ASSISTANCE = "accessibility_assistance"
    const val BUBBLE_AUTO_DISMISS = "bubble_auto_dismiss"
    const val BUBBLE_POSITION = "bubble_position"
    const val BUBBLE_SIZE = "bubble_size"
    const val A11Y_IGNORED_PACKAGES = "a11y_ignored_packages"
    const val DOWNLOAD_NOTIFICATIONS = "download_notifications"
    const val DOWNLOAD_DESTINATION = "download_destination"
    const val DOWNLOAD_ORGANIZE_BY_SOURCE = "download_organize_by_source"
    const val SERVER_FALLBACK_ENABLED = "server_fallback_enabled"
    const val SERVER_FALLBACK_URL = "server_fallback_url"
    const val SERVER_FALLBACK_API_KEY = "server_fallback_api_key"
}
