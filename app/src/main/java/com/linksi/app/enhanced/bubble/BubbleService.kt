package com.linksi.app.enhanced.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.linksi.app.R
import com.linksi.app.enhanced.EnhancedFeatureDefaults
import com.linksi.app.enhanced.EnhancedPreferenceKeys
import com.linksi.app.enhanced.ui.QuickPanelActivity
import com.linksi.app.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The optional floating Linksi bubble (specification section 12).
 *
 * Responsibilities, all of them optional and all of them failure isolated:
 *
 *  - show a small, draggable `TYPE_APPLICATION_OVERLAY` view that snaps to a side (12.1, 12.2),
 *  - keep the touchable area *exactly* the bubble, and never take focus, so touches meant for the
 *    app underneath still arrive there (research note ANDROID16_REQUIREMENTS.md section 8.2),
 *  - dismiss itself after the configured delay, or never when the setting is "persistent",
 *  - open [QuickPanelActivity] on tap,
 *  - clean up completely - view, handler, foreground notification and service - on stop.
 *
 * It never reads the clipboard, never inspects other apps and never starts a background activity on
 * its own; the only activity it launches is the one the user just asked for by tapping.
 *
 * Everything platform-facing is guarded with `runCatching`: a revoked overlay permission, a
 * `BadTokenException` from `addView`, or an OEM that hides overlays must all end in "no bubble",
 * never in a crash of the host app.
 */
class BubbleService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    private var sizeSetting: String = BubblePolicy.SIZE_NORMAL
    private var positionSetting: String = BubblePolicy.POSITION_REMEMBER
    private var autoDismissSeconds: Int = EnhancedFeatureDefaults.BUBBLE_AUTO_DISMISS_SECONDS

    /** Last snapped y, so "remember last" keeps the vertical spot as well as the side. */
    private var rememberedY: Int = -1

    private val dismissRunnable = Runnable { dismissBubble(stopService = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // The foreground notification is created first: Android requires startForeground within a
        // few seconds of the start request, and the bubble itself must never be the thing that
        // fails first.
        if (!promoteToForeground()) {
            stopSelf()
            return
        }
        if (!canDrawOverlays(this)) {
            // The permission can be revoked between the check in the companion helper and this
            // service actually starting. Fail soft and disappear.
            stopSelf()
            return
        }
        // Start settings arrive through onStartCommand, which always runs after onCreate and
        // carries the same intent, so nothing is read from the framework here.
        if (!addBubbleView()) {
            stopSelf()
            return
        }
        scheduleAutoDismiss()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS) {
            dismissBubble(stopService = true)
            return START_NOT_STICKY
        }
        applyIntentSettings(intent)
        // A second start (another detected link) re-reads the settings and restarts the timer.
        if (bubbleView != null) scheduleAutoDismiss()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardownBubble()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A rotation changes the usable width, which can leave the bubble outside the new screen. It is
     * dragged back to its side instead of being recreated, so the user never sees it jump to a
     * default spot.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = bubbleView ?: return
        val manager = windowManager ?: return
        runCatching { snapToSide(dp(BubblePolicy.diameterDp(sizeSetting)), manager, view) }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun applyIntentSettings(intent: Intent?) {
        if (intent == null) return
        sizeSetting = BubblePolicy.normalizeSize(
            intent.getStringExtra(EXTRA_SIZE) ?: sizeSetting
        )
        positionSetting = BubblePolicy.normalizePosition(
            intent.getStringExtra(EXTRA_POSITION) ?: positionSetting
        )
        val dismiss = intent.getIntExtra(EXTRA_AUTO_DISMISS_SECONDS, Int.MIN_VALUE)
        if (dismiss != Int.MIN_VALUE) autoDismissSeconds = dismiss
    }

    // ── Window ────────────────────────────────────────────────────────────────

    private fun addBubbleView(): Boolean = runCatching {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = manager

        val metrics = resources.displayMetrics
        val diameter = dp(BubblePolicy.diameterDp(sizeSetting))
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val margin = dp(EDGE_MARGIN_DP)

        val side = BubblePolicy.snapSide(
            storedPosition = positionSetting,
            bubbleCentreX = width / 2,
            screenWidth = width
        )
        val y = if (rememberedY >= 0) {
            BubblePolicy.clampY(rememberedY, diameter, height, statusBarHeight())
        } else {
            BubblePolicy.clampY(height / 3, diameter, height, statusBarHeight())
        }

        val params = WindowManager.LayoutParams(
            diameter,
            diameter,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE keeps the app underneath focused (the keyboard and text fields keep
            // working); NOT_TOUCH_MODAL keeps every touch outside the bubble going to that app.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = BubblePolicy.snappedX(side, diameter, width, margin)
            this.y = y
        }

        val view = createBubbleView(diameter)
        attachTouchHandling(view, diameter, manager)

        manager.addView(view, params)
        bubbleView = view
        layoutParams = params
        true
    }.getOrDefault(false)

    private fun createBubbleView(diameter: Int): View {
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BUBBLE_BACKGROUND)
            setStroke(dp(2), BUBBLE_STROKE)
        }

        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.linksi)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.enhanced_bubble_content_description)
        }

        return FrameLayout(this).apply {
            this.background = background
            // Exact size: the overlay's touchable region is the bubble and nothing else.
            layoutParams = FrameLayout.LayoutParams(diameter, diameter)
            addView(
                icon,
                FrameLayout.LayoutParams(
                    dp(BubblePolicy.iconDp(sizeSetting)),
                    dp(BubblePolicy.iconDp(sizeSetting)),
                    Gravity.CENTER
                )
            )
            // An overlay is not part of the app's accessibility tree in a useful way; hide it from
            // accessibility services so it cannot become a target of this app's own detection.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun attachTouchHandling(view: View, diameter: Int, manager: WindowManager) {
        val slop = dp(TOUCH_SLOP_DP)
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            val params = layoutParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    // A touch is an explicit sign of interest: hold the bubble while it lasts.
                    handler.removeCallbacks(dismissRunnable)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (!dragging && BubblePolicy.isDrag(0, 0, dx, dy, slop)) dragging = true
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        params.x = BubblePolicy.clampX(startX + dx, diameter, metrics.widthPixels)
                        params.y = BubblePolicy.clampY(
                            startY + dy,
                            diameter,
                            metrics.heightPixels,
                            statusBarHeight()
                        )
                        runCatching { manager.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val upDx = (event.rawX - downRawX).toInt()
                    val upDy = (event.rawY - downRawY).toInt()
                    val wasTap = BubblePolicy.isTap(0, 0, upDx, upDy, slop)
                    if (wasTap) {
                        openQuickPanel()
                    } else {
                        snapToSide(diameter, manager)
                        scheduleAutoDismiss()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapToSide(diameter: Int, manager: WindowManager, target: View? = bubbleView) {
        runCatching {
            val params = layoutParams ?: return@runCatching
            if (target == null) return@runCatching
            val metrics = resources.displayMetrics
            val side = BubblePolicy.snapSide(
                storedPosition = positionSetting,
                bubbleCentreX = params.x + diameter / 2,
                screenWidth = metrics.widthPixels
            )
            params.x = BubblePolicy.snappedX(side, diameter, metrics.widthPixels, dp(EDGE_MARGIN_DP))
            params.y = BubblePolicy.clampY(
                params.y,
                diameter,
                metrics.heightPixels,
                statusBarHeight()
            )
            rememberedY = params.y
            manager.updateViewLayout(target, params)
            persistSide(side)
        }
    }

    /** Writes the side back when the setting is "remember last". Never blocks or fails the UI. */
    private fun persistSide(side: String) {
        if (BubblePolicy.normalizePosition(positionSetting) != BubblePolicy.POSITION_REMEMBER) return
        scope.launch {
            runCatching {
                applicationContext.dataStore.edit {
                    it[stringPreferencesKey(EnhancedPreferenceKeys.BUBBLE_POSITION)] = side
                }
            }
        }
    }

    private fun openQuickPanel() {
        dismissBubble(stopService = true)
        runCatching {
            val intent = Intent(this, QuickPanelActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(intent)
        }
    }

    // ── Lifecycle helpers ─────────────────────────────────────────────────────

    private fun scheduleAutoDismiss() {
        handler.removeCallbacks(dismissRunnable)
        val delay = BubblePolicy.autoDismissDelayMs(autoDismissSeconds) ?: return
        handler.postDelayed(dismissRunnable, delay)
    }

    private fun dismissBubble(stopService: Boolean) {
        teardownBubble()
        if (stopService) stopSelf()
    }

    private fun teardownBubble() {
        handler.removeCallbacks(dismissRunnable)
        val view = bubbleView
        bubbleView = null
        layoutParams = null
        if (view != null) {
            runCatching { windowManager?.removeView(view) }
        }
        windowManager = null
    }

    private fun promoteToForeground(): Boolean = runCatching {
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    }.getOrDefault(false)

    private fun buildNotification(): Notification {
        // Tapping the notification or its Dismiss action stops the bubble. Both are service
        // PendingIntents: no activity is ever started from the background.
        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, BubbleService::class.java).setAction(ACTION_DISMISS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.linksi)
            .setContentTitle(getString(R.string.enhanced_bubble_notification_title))
            .setContentText(getString(R.string.enhanced_bubble_notification_text))
            .setContentIntent(cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .addAction(0, getString(R.string.enhanced_bubble_notification_dismiss), cancelIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.enhanced_bubble_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.enhanced_bubble_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics
    ).toInt().coerceAtLeast(1)

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id <= 0) return dp(FALLBACK_STATUS_BAR_DP)
        return runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(dp(FALLBACK_STATUS_BAR_DP))
    }

    companion object {

        const val ACTION_DISMISS = "com.linksi.app.enhanced.bubble.DISMISS"
        const val EXTRA_SIZE = "com.linksi.app.enhanced.bubble.EXTRA_SIZE"
        const val EXTRA_POSITION = "com.linksi.app.enhanced.bubble.EXTRA_POSITION"
        const val EXTRA_AUTO_DISMISS_SECONDS = "com.linksi.app.enhanced.bubble.EXTRA_AUTO_DISMISS"

        private const val NOTIFICATION_ID = 7401
        private const val NOTIFICATION_CHANNEL_ID = "linksi_bubble"
        private const val EDGE_MARGIN_DP = 8
        private const val TOUCH_SLOP_DP = 12
        private const val FALLBACK_STATUS_BAR_DP = 24

        private const val BUBBLE_BACKGROUND = 0xFF6750A4.toInt()
        private const val BUBBLE_STROKE = 0x66FFFFFF

        /**
         * True when the app may draw an overlay.
         *
         * `TYPE_APPLICATION_OVERLAY` exists from API 26, which is also this app's `minSdk`, so no
         * version branch is needed beyond the permission check itself.
         */
        fun canDrawOverlays(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        /**
         * Shows the bubble with [settings], if the app is allowed to.
         *
         * Returns true only when the service was actually started. This never throws: a missing
         * overlay permission, a restricted background start, or an OEM denial all return false and
         * leave the caller's feature disabled (specification sections 8 and 35).
         */
        fun show(context: Context, settings: BubbleSettings = BubbleSettings()): Boolean {
            if (!canDrawOverlays(context)) return false
            return runCatching {
                val intent = Intent(context, BubbleService::class.java).apply {
                    putExtra(EXTRA_SIZE, settings.size)
                    putExtra(EXTRA_POSITION, settings.position)
                    putExtra(EXTRA_AUTO_DISMISS_SECONDS, settings.autoDismissSeconds)
                }
                ContextCompat.startForegroundService(context.applicationContext, intent)
                true
            }.getOrDefault(false)
        }

        /** Stops the bubble and removes its view. Safe to call when it is not running. */
        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, BubbleService::class.java))
            }
        }
    }
}
