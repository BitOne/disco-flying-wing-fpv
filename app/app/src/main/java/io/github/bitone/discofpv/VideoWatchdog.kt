/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

/**
 * Tells a frozen picture from live video: the screen reports the new frames
 * its view rendered ([onFrames]). While a stream is open ([onStreamOpened]):
 * - fewer than [minFrames] in the last [frozenAfterMs] is a frozen picture,
 *   warned about (the view keeps its last frame, which must not pass for
 *   live video);
 * - no new frame at all for [restartAfterMs] asks for a stream restart, at
 *   most every [retryEveryMs]. A slow picture (a busy phone, a weak link, a
 *   stalled decoder letting a stray frame through) is only warned about:
 *   a restart blanks the picture for a while, worse than slow, and would
 *   not mend a stalled decoder anyway.
 * A new stream gets [firstFrameMs] for its first frames (the decoder
 * starting) before NO VIDEO, and [firstRestartMs] before a restart: a
 * phone can be slow to start a stream, and restarting it then only delays
 * it more. Times in ms, from any monotonic clock. Independent of Android,
 * unit-tested.
 */
class VideoWatchdog(
    private val frozenAfterMs: Long = 1000,
    private val minFrames: Int = 3,
    private val restartAfterMs: Long = 3000,
    private val retryEveryMs: Long = 4000,
    private val firstFrameMs: Long = 4000,
    private val firstRestartMs: Long = 10_000,
) {
    enum class State { NO_STREAM, LIVE, FROZEN }

    private var open = false
    private var openedAt = 0L
    /** Recent (time, new frames) reports, oldest first, over [restartAfterMs]. */
    private val recent = ArrayDeque<Pair<Long, Int>>()
    /** When the video was last seen live (enough frames), null if never since opened. */
    private var liveAt: Long? = null
    private var lastRestart: Long? = null

    /** Restarts asked since the video was last live. */
    var restarts = 0
        private set

    fun onStreamOpened(now: Long) {
        open = true
        openedAt = now
        recent.clear()
        liveAt = null
    }

    fun onStreamClosed() {
        open = false
        recent.clear()
        liveAt = null
    }

    /** [count] new frames rendered since the last report. */
    fun onFrames(now: Long, count: Int = 1) {
        if (count <= 0) return
        recent.addLast(now to count)
        prune(now)
        if (framesWithin(now, frozenAfterMs) >= minFrames) {
            liveAt = now
            restarts = 0
        }
    }

    private fun prune(now: Long) {
        while (recent.isNotEmpty() && now - recent.first().first > restartAfterMs) recent.removeFirst()
    }

    private fun framesWithin(now: Long, ms: Long): Int = recent.filter { now - it.first <= ms }.sumOf { it.second }

    private fun starting(now: Long) = liveAt == null && now - openedAt <= firstFrameMs

    fun state(now: Long): State {
        if (!open) return State.NO_STREAM
        prune(now)
        return if (framesWithin(now, frozenAfterMs) >= minFrames || starting(now)) State.LIVE else State.FROZEN
    }

    /** Whether to restart the stream now; if so, counts it. */
    fun shouldRestart(now: Long): Boolean {
        if (!open || starting(now) || now - openedAt < restartAfterMs) return false
        if (liveAt == null && now - openedAt < firstRestartMs) return false
        prune(now)
        if (framesWithin(now, restartAfterMs) > 0) return false
        val prev = lastRestart
        if (prev != null && now - prev < retryEveryMs) return false
        lastRestart = now
        restarts++
        return true
    }

    /**
     * The HUD warning: null while live or without a stream; while restarts
     * are under way (the stream reopening, its first frames awaited), still
     * the warning, until frames flow again.
     */
    fun alert(now: Long): String? {
        val s = state(now)
        val flowing = open && framesWithin(now, frozenAfterMs) >= minFrames
        return when {
            flowing -> null
            restarts > 0 -> "VIDEO LOST, RESTARTING"
            s != State.FROZEN -> null
            // frames come, too few: a slow picture
            framesWithin(now, frozenAfterMs) > 0 -> "VIDEO SLOW"
            liveAt == null -> "NO VIDEO"
            else -> "VIDEO FROZEN"
        }
    }
}
