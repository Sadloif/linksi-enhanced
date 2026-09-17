package com.linksi.app.enhanced.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.linksi.app.R
import com.linksi.app.enhanced.media.MediaError
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * The download notifications and the foreground-service notice (specification section 23,
 * research brief section 4).
 *
 * Non-negotiable behaviours, all of them about *not* letting notifications break a download:
 *  - Every post is guarded by `areNotificationsEnabled()`. When `POST_NOTIFICATIONS` is denied the
 *    system drops posts silently, so the guard is not required for correctness - it is there so the
 *    app never crashes and never pops the permission dialog itself (specification section 26).
 *  - The foreground notice is built here and handed to WorkManager, which calls `startForeground`.
 *    It is *not* guarded: a denied permission still shows the notice in the Task Manager, and the
 *    download must run either way.
 *  - Progress posts are throttled by [NotificationThrottle] and marked silent/only-alert-once, so a
 *    long download cannot trip Android 15's notification cooldown.
 *  - Results use a **different** notification id from the progress notice. WorkManager cancels the
 *    foreground notification by id when the worker ends, which would otherwise delete the very
 *    "download complete" notification that was just posted.
 */
@Singleton
class DownloadNotifications @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val manager = NotificationManagerCompat.from(context)

    private val throttle = NotificationThrottle()

    /** Idempotent: re-creating a channel with the same values is a documented no-op. */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val system = context.getSystemService(NotificationManager::class.java) ?: return

        val progress = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            context.getString(R.string.enhanced_download_channel_progress_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.enhanced_download_channel_progress_desc)
            setShowBadge(false)
        }

        val results = NotificationChannel(
            RESULT_CHANNEL_ID,
            context.getString(R.string.enhanced_download_channel_complete_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.enhanced_download_channel_complete_desc)
        }

        runCatching {
            system.createNotificationChannel(progress)
            system.createNotificationChannel(results)
        }
    }

    /**
     * The foreground-service notice for one download. On API 29+ it carries
     * `FOREGROUND_SERVICE_TYPE_DATA_SYNC`; below that the plain two-argument constructor is used,
     * because the type constant does not exist yet (research brief section 1.2).
     */
    fun foregroundInfo(
        downloadId: String,
        title: String,
        state: DownloadState.Downloading
    ): ForegroundInfo {
        val notification = progressNotification(downloadId, title, state)
        val id = notificationId(downloadId)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /** Updates the in-place progress notification. Dropped when notifications are off. */
    fun notifyProgress(
        downloadId: String,
        title: String,
        state: DownloadState.Downloading,
        force: Boolean = false
    ) {
        if (!canNotify()) return
        if (!throttle.shouldPost(downloadId, state, force)) return
        post(notificationId(downloadId), progressNotification(downloadId, title, state))
    }

    fun notifyComplete(downloadId: String, displayName: String, location: String?) {
        throttle.forget(downloadId)
        if (!canNotify()) return

        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.enhanced_download_complete_title))
            .setContentText(displayName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayName))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .build()

        post(resultNotificationId(downloadId), notification)
    }

    fun notifyFailed(downloadId: String, displayName: String, error: MediaError) {
        throttle.forget(downloadId)
        if (!canNotify()) return

        val reason = context.getString(errorTextRes(error))
        val notification = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(context.getString(R.string.enhanced_download_failed_title))
            .setContentText(reason)
            .setSubText(displayName)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .apply { contentIntent()?.let { setContentIntent(it) } }
            .build()

        post(resultNotificationId(downloadId), notification)
    }

    /** Removes both the progress and the result notification for [downloadId]. */
    fun cancel(downloadId: String) {
        throttle.forget(downloadId)
        runCatching {
            manager.cancel(notificationId(downloadId))
            manager.cancel(resultNotificationId(downloadId))
        }
    }

    /** False when the user has notifications off; never prompts, never throws. */
    fun canNotify(): Boolean =
        hasPostPermission() && runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

    /**
     * The runtime `POST_NOTIFICATIONS` check. Below API 33 the permission does not exist and posting
     * is always allowed, so this returns true.
     */
    private fun hasPostPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Posts a notification. Best effort by design: the permission can be revoked between the check
     * and the call, and a dropped notification must never fail a download.
     *
     * The permission test is written inline rather than calling [hasPostPermission] because lint's
     * `MissingPermission` check requires the `checkSelfPermission` guard to be visible in the method
     * that calls `notify`.
     */
    private fun post(id: Int, notification: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            // Revoked between the check and the post. Nothing to report and nothing to fail.
        }
    }

    fun notificationId(downloadId: String): Int = stableId(PROGRESS_TAG + downloadId)

    fun resultNotificationId(downloadId: String): Int = stableId(RESULT_TAG + downloadId)

    private fun progressNotification(
        downloadId: String,
        title: String,
        state: DownloadState.Downloading
    ): Notification {
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(SMALL_ICON)
            .setContentTitle(title)
            .setContentText(progressText(state))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(PROGRESS_MAX, state.percent ?: 0, !state.hasKnownTotal)

        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    private fun progressText(state: DownloadState.Downloading): String {
        val unknown = context.getString(R.string.enhanced_download_size_unknown)
        val downloaded = DownloadFormatting.formatBytes(state.bytesDownloaded, unknown)
        val base = if (state.hasKnownTotal) {
            context.getString(
                R.string.enhanced_download_notification_progress,
                downloaded,
                DownloadFormatting.formatBytes(state.totalBytes, unknown)
            )
        } else {
            downloaded
        }

        val withPercent = state.percent
            ?.let { context.getString(R.string.enhanced_download_notification_percent, it, base) }
            ?: base

        val speed = DownloadFormatting.formatSpeed(state.bytesPerSecond)
            ?: return withPercent

        return context.getString(R.string.enhanced_download_notification_detail, withPercent, speed)
    }

    /** Opens the app when the notification is tapped; null when the app has no launcher entry. */
    private fun contentIntent(): PendingIntent? {
        val launch = runCatching {
            context.packageManager.getLaunchIntentForPackage(context.packageName)
        }.getOrNull() ?: return null

        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return runCatching {
            PendingIntent.getActivity(
                context,
                CONTENT_INTENT_REQUEST,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()
    }

    /**
     * Notification ids must be ints and must be stable across processes, so they are derived from
     * the request id rather than kept in a map that a restart would empty.
     */
    private fun stableId(value: String): Int = abs(value.hashCode()) and Int.MAX_VALUE

    companion object {
        const val PROGRESS_CHANNEL_ID = "linksi_downloads"

        const val RESULT_CHANNEL_ID = "linksi_download_results"

        private const val PROGRESS_TAG = "linksi.download.progress."

        private const val RESULT_TAG = "linksi.download.result."

        private const val CONTENT_INTENT_REQUEST = 4200

        private const val PROGRESS_MAX = 100

        /**
         * The app already uses a system drawable for its reminder notification; reusing one keeps
         * this module from adding a resource the launcher icon would otherwise have to supply a
         * monochrome variant for.
         */
        private val SMALL_ICON = android.R.drawable.stat_sys_download
    }
}

/** The user-facing text for a failure reason (specification section 68). */
@StringRes
fun errorTextRes(error: MediaError): Int = when (error) {
    MediaError.UNSUPPORTED_SITE -> R.string.enhanced_download_error_unsupported
    MediaError.PRIVATE_CONTENT -> R.string.enhanced_download_error_private
    MediaError.MEDIA_GONE -> R.string.enhanced_download_error_gone
    MediaError.LOGIN_REQUIRED -> R.string.enhanced_download_error_login_required
    MediaError.NETWORK -> R.string.enhanced_download_error_network
    MediaError.EXTRACTOR_FAILED -> R.string.enhanced_download_error_failed
    MediaError.NO_FORMATS -> R.string.enhanced_download_error_no_formats
    MediaError.NO_STORAGE -> R.string.enhanced_download_error_no_storage
    MediaError.ENGINE_UNAVAILABLE -> R.string.enhanced_download_error_engine_unavailable
    MediaError.SERVER_UNAVAILABLE -> R.string.enhanced_download_error_server_unavailable
    MediaError.CANCELLED -> R.string.enhanced_download_error_cancelled
}

/**
 * Rate limiting for progress notifications (research brief section 4.5).
 *
 * Posts at most once per [minIntervalMillis], or sooner when the percentage moves by at least
 * [minPercentDelta]. Keeping this pure makes the rule testable instead of hidden inside a worker.
 */
class NotificationThrottle(
    private val clock: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
    private val minPercentDelta: Int = DEFAULT_PERCENT_DELTA
) {

    private val lastPostAt = mutableMapOf<String, Long>()
    private val lastPercent = mutableMapOf<String, Int?>()

    fun shouldPost(
        downloadId: String,
        state: DownloadState.Downloading,
        force: Boolean = false
    ): Boolean {
        val now = clock()
        val previous = lastPostAt[downloadId]

        if (previous == null || force) {
            record(downloadId, now, state.percent)
            return true
        }

        val elapsed = now - previous
        val percent = state.percent
        val previousPercent = lastPercent[downloadId]
        val percentMoved = percent != null &&
            previousPercent != null &&
            abs(percent - previousPercent) >= minPercentDelta

        if (elapsed >= minIntervalMillis || percentMoved) {
            record(downloadId, now, percent)
            return true
        }
        return false
    }

    fun forget(downloadId: String) {
        lastPostAt.remove(downloadId)
        lastPercent.remove(downloadId)
    }

    private fun record(downloadId: String, now: Long, percent: Int?) {
        lastPostAt[downloadId] = now
        lastPercent[downloadId] = percent
    }

    companion object {
        /** Twice a second is plenty for a progress bar and stays clear of the cooldown. */
        const val DEFAULT_INTERVAL_MILLIS = 500L

        const val DEFAULT_PERCENT_DELTA = 1
    }
}
