package com.linksi.app.enhanced.bubble

import com.linksi.app.enhanced.EnhancedFeatureDefaults

/**
 * The bubble settings one show needs, resolved once by the caller from DataStore.
 *
 * Kept in the bubble package so [BubbleService] can be shown with a plain value and does not have
 * to know anything about the settings screen or the detector.
 */
data class BubbleSettings(
    val size: String? = null,
    val position: String? = null,
    val autoDismissSeconds: Int = EnhancedFeatureDefaults.BUBBLE_AUTO_DISMISS_SECONDS
)
