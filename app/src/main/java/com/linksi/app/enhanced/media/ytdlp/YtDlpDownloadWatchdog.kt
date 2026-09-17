package com.linksi.app.enhanced.media.ytdlp

import java.util.concurrent.atomic.AtomicLong

/**
 * How long a site-engine download may run, and how long it may make no progress (pure JVM, so both
 * numbers are unit tested rather than discovered on a device).
 *
 * ### Why a watchdog exists at all
 *
 * yt-dlp is a child process the app cannot interrupt reliably, and its own network options do not
 * cover a silent read hang. Observed on an Android 16 emulator: mid-download, yt-dlp stopped
 * writing to its `.part` file and printed nothing further, while the process stayed alive
 * indefinitely - nine minutes of monitoring with a byte-identical file - despite
 * `--socket-timeout 20 --retries 3` being in force. The same stream had been flowing at ~85 KiB/s
 * moments before, and a later identical run completed. That is a hung socket, not a slow network.
 *
 * Without this, the user's download sits at a fixed percentage forever with a Cancel button that
 * may not work, which is a worse outcome than a clear failure they can retry.
 *
 * ### The two limits are deliberately different
 *
 * * [stallLimitMillis] is the useful one. It measures **silence in bytes**, not silence in output:
 *   yt-dlp can chatter about fragments, destinations and FFmpeg without transferring anything, so
 *   only a change in the reported byte count counts as progress. This is what catches a hung
 *   socket, and it is the reason a download that is merely slow is not killed. It applies only
 *   **after the first byte**: before that there is no transfer to have stopped, and yt-dlp's own
 *   start-up (fetch the page, parse the manifest, choose formats) legitimately transfers no media
 *   for tens of seconds.
 * * [hardLimitMillis] is the backstop, and it covers two cases: a start-up that never produces a
 *   first byte at all, and a transfer that keeps dribbling a byte at a time and so never looks
 *   stalled. It is generous because it is not a performance budget: a large video over a slow
 *   mobile link legitimately takes many minutes.
 *
 * Both are multiplied by [scaleFor] so a large expected file gets proportionally more room, with
 * the multiplier capped so a bogus size cannot push the deadline out of reach.
 */
data class DownloadWatchdogPolicy(
    val stallLimitMillis: Long = DEFAULT_STALL_LIMIT_MILLIS,
    val hardLimitMillis: Long = DEFAULT_HARD_LIMIT_MILLIS
) {

    /**
     * The stall and hard limits for a download whose size the extractor already knows.
     *
     * The scale is anchored on [ANCHOR_BYTES]: at or below it the limits are used as they are, and
     * above it they grow in proportion, never by more than [MAX_SCALE]. A 1 GiB video is therefore
     * allowed several times longer than a 20 MiB clip, but a wrong `expectedBytes` can still only
     * buy a bounded amount of patience.
     */
    fun scaleFor(expectedBytes: Long?): DownloadWatchdogPolicy {
        val scale = scaleFactor(expectedBytes)
        return if (scale == 1.0) this else copy(
            stallLimitMillis = (stallLimitMillis * scale).toLong(),
            hardLimitMillis = (hardLimitMillis * scale).toLong()
        )
    }

    companion object {
        /**
         * How long yt-dlp may transfer nothing before the download is called stuck.
         *
         * Chosen against the evidence: the observed hang produced no bytes at all for over nine
         * minutes, while healthy fragment downloads on the same link reported progress every few
         * hundred milliseconds. A minute is tens of times longer than any healthy gap seen, and
         * short enough that a user is not left staring at a frozen percentage.
         */
        const val DEFAULT_STALL_LIMIT_MILLIS = 60_000L

        /**
         * The outer bound for a download that never quite stops making progress.
         *
         * Thirty minutes is far beyond any transfer this app's own tests have produced (the
         * largest was 20 MiB in under five minutes) and exists only so that no download can run
         * forever.
         */
        const val DEFAULT_HARD_LIMIT_MILLIS = 30 * 60 * 1000L

        /** The size at which the limits start to grow. */
        const val ANCHOR_BYTES = 20L * 1024L * 1024L

        /** The most the limits may be stretched, whatever size is claimed. */
        const val MAX_SCALE = 8.0

        /**
         * The multiplier for a known size: 1 below [ANCHOR_BYTES], then proportional, capped at
         * [MAX_SCALE]. A size that is absent, zero or negative means "unknown" and changes nothing.
         */
        fun scaleFactor(expectedBytes: Long?): Double {
            val bytes = expectedBytes ?: return 1.0
            if (bytes <= ANCHOR_BYTES) return 1.0
            return (bytes.toDouble() / ANCHOR_BYTES).coerceAtMost(MAX_SCALE)
        }
    }
}

/**
 * Decides whether a running download has stopped making progress, from the progress reports
 * themselves.
 *
 * Kept separate from the coroutine that acts on the answer so both halves of the rule are unit
 * tested: "no bytes for a minute" is arithmetic, and getting it wrong either kills healthy
 * downloads or never kills a stuck one.
 *
 * Thread-safe by construction. Progress arrives on yt-dlp's own reader thread while the watchdog
 * reads from a coroutine, so the counter is atomic and the last-change timestamp is only ever
 * compared, never used to compute a duration across threads.
 */
class DownloadStallDetector(
    private val stallLimitMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private val lastBytes = AtomicLong(0L)

    /**
     * Whether the stream under way has reported its **total**, which happens at 100%.
     *
     * This is the one piece of state that distinguishes a dead socket from legitimate silence, and
     * it was missing from the first version of this rule. Once a stream reports 100%, yt-dlp stops
     * reporting progress *by design* while it runs the postprocessor - on the test emulator, FFmpeg
     * spent **2 minutes 48 seconds** quiet after a 75 MiB stream reached 100% - and a byte-idle timer
     * cannot tell that apart from a hang. Treating it as a stall killed a healthy download.
     *
     * A new *stream* resets it: a `video+audio` download reports 100% for the video and then starts
     * the audio from near zero, and the audio is exactly the stream that hung in the real incident.
     */
    @Volatile
    private var lastReportWasComplete = false

    /**
     * Whether any byte has been transferred yet.
     *
     * This distinguishes a transfer that has **stopped** from one that has not **started**: yt-dlp
     * spends its first many seconds fetching the page, parsing a manifest and choosing formats,
     * during which it transfers no media at all. Before the first byte there is nothing to be
     * stalled relative to, and [DownloadWatchdogPolicy.hardLimitMillis] is the bound that applies.
     */
    @Volatile
    private var sawBytes = false

    @Volatile
    private var lastProgressAt: Long = clock()

    /**
     * Records the bytes transferred so far, and the total the line reported, and answers whether the
     * download has stalled.
     *
     * Progress counts only when the **byte count changes**. yt-dlp's output is not a byte stream: it
     * announces destinations, fragment counts and post-processing steps, and a hung socket can still
     * be inside a line that was already printed. Treating any callback as progress is how a stalled
     * download looks alive forever.
     */
    fun onProgress(
        bytesDownloaded: Long,
        totalBytes: Long? = null,
        now: Long = clock()
    ): Boolean {
        // A line whose total has shrunk to the bytes reported is yt-dlp saying "this stream is
        // done", whether it printed a percentage or only the sizes.
        val complete = totalBytes != null && totalBytes > 0L && bytesDownloaded >= totalBytes
        val wasComplete = lastReportWasComplete
        lastReportWasComplete = complete

        if (bytesDownloaded > lastBytes.get()) {
            lastBytes.set(bytesDownloaded)
            lastProgressAt = now
            sawBytes = true
        } else if (wasComplete && !complete) {
            // A *new stream* has begun after the previous one finished. Its byte count legitimately
            // starts lower than the finished stream's, so it would never clear the "greater than the
            // last count" test, and the clock would still be reading from the stream before it. In
            // the real incident this is exactly the stream that hung: the video completed, the audio
            // was requested, and the audio then stopped.
            lastProgressAt = now
        }
        return isStalled(now)
    }

    /**
     * True when a transfer has started, has not finished, and has reported nothing for the limit.
     *
     * Post-processing is excluded on purpose: after a stream reports 100% the child can be quiet for
     * minutes while FFmpeg works, which is progress the progress channel cannot see.
     */
    fun isStalled(now: Long = clock()): Boolean =
        sawBytes && !lastReportWasComplete && now - lastProgressAt >= stallLimitMillis

    /** Whether a transfer ever started. */
    fun hasTransferred(): Boolean = sawBytes

    /** The most recent byte count seen, for the failure message. */
    fun bytesSeen(): Long = lastBytes.get()
}

/**
 * The sentence shown when a download was stopped because it stopped transferring.
 *
 * It says what happened in the user's terms and, crucially, that retrying is worth doing: the cause
 * is a network that went quiet, and the half-finished file is deliberately kept in the scratch
 * directory after a retryable failure, so yt-dlp resumes from the fragments it already has instead
 * of starting again.
 */
internal const val STALLED_DETAIL =
    "the download made no progress and was stopped; it can be retried and will continue where it left off"

/** The sentence shown when a download was stopped for exceeding the hard limit. */
internal const val TIMED_OUT_DETAIL =
    "the download did not finish in the time allowed and was stopped"
