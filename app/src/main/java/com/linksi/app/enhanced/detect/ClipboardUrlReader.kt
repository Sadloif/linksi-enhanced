package com.linksi.app.enhanced.detect

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Reads the clipboard **only** at an explicit, user-initiated, foreground moment (specification
 * section 11.1).
 *
 * Android 10 and later refuse clipboard access to any app that is not the current input focus, and
 * the specification forbids silent background scraping outright. This reader therefore:
 *
 *  - refuses to run unless the caller can prove it has window focus ([isFocused]),
 *  - is never invoked by the accessibility service, by the bubble, or at app start,
 *  - returns the first *actionable* HTTP(S) URL and discards everything else (section 57),
 *  - never logs, caches, stores or reports what it read, not even on failure.
 *
 * There is no polling API here on purpose: the only entry points are called from a foreground
 * activity the user just interacted with.
 */
object ClipboardUrlReader {

    /**
     * The outcome of one read.
     *
     * @param url the actionable URL, or null when the clipboard held anything else.
     * @param textWasUrl true when the whole clip was exactly the URL; false when the URL was part of
     *   a longer text. The panel only shows an "original" line when this is false.
     * @param readable false when the clipboard could not be read at all (no focus, no clip data, or
     *   a platform denial). Callers must treat this exactly like "no URL found".
     */
    data class ClipboardReadResult(
        val url: String? = null,
        val textWasUrl: Boolean = false,
        val readable: Boolean = false
    ) {
        val hasUrl: Boolean get() = url != null
    }

    /** The result returned whenever nothing usable could be read. */
    private val EMPTY = ClipboardReadResult()

    /**
     * Reads the clipboard for the given [context], but only when [isFocused] is true.
     *
     * The focus flag is a parameter rather than something read from [context] so that the refusal
     * path is testable and so that a caller cannot accidentally pass an `Application` context and
     * have the check silently pass.
     */
    fun read(context: Context, isFocused: Boolean): ClipboardReadResult {
        if (!isFocused) return EMPTY
        return runCatching { readUnsafe(context.applicationContext) }.getOrDefault(EMPTY)
    }

    /**
     * Reads the clipboard from an [activity] that currently has window focus.
     *
     * Use this from `Activity.onWindowFocusChanged(true)` or after confirming
     * `activity.hasWindowFocus()`; it checks again for you.
     */
    fun read(activity: Activity): ClipboardReadResult {
        val focused = runCatching { activity.hasWindowFocus() }.getOrDefault(false)
        return read(activity, focused)
    }

    /** Lifecycle-aware variant: reads only in a resumed, focused state. */
    fun read(owner: LifecycleOwner, context: Context): ClipboardReadResult {
        val state = runCatching { owner.lifecycle.currentState }.getOrNull()
        if (state != Lifecycle.State.RESUMED) return EMPTY
        val activity = context as? Activity ?: return EMPTY
        return read(activity)
    }

    /**
     * True when [context] is an activity that currently has window focus. Exposed so callers can
     * decide *not* to run any of this work while the app is in the background.
     */
    fun isFocused(context: Context): Boolean {
        val activity = context as? Activity ?: return false
        return runCatching { activity.hasWindowFocus() }.getOrDefault(false)
    }

    /**
     * The only place the platform clipboard is touched. The clip is reduced to the first
     * actionable URL and then dropped: nothing else is retained.
     */
    private fun readUnsafe(context: Context): ClipboardReadResult {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return EMPTY
        if (!manager.hasPrimaryClip()) return EMPTY

        val clip: ClipData = manager.primaryClip ?: return EMPTY
        if (clip.itemCount == 0) return EMPTY

        // Only text clips are considered. An image or intent clip is discarded unread.
        val item = clip.getItemAt(0)
        val text = item.coerceToText(context)?.toString() ?: item.text?.toString() ?: return EMPTY
        if (text.isBlank()) return EMPTY

        val url = UrlTextExtractor.firstHttpUrl(text)?.takeIf { UrlTextExtractor.isActionableUrl(it) }
            ?: return ClipboardReadResult(readable = true)

        return ClipboardReadResult(
            url = url,
            textWasUrl = text.trim() == url,
            readable = true
        )
    }
}
