/*
 * Disco FPV. Copyright (c) 2026, Benoit Jacquemont. BSD-3-Clause.
 */
package io.github.bitone.discofpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoWatchdogTest {

    /** 30 fps reported every 250 ms (about 8 frames a tick), from [from] to [to]. */
    private fun VideoWatchdog.stream(from: Long, to: Long) {
        for (t in from..to step 250) onFrames(t, 8)
    }

    @Test
    fun liveWhileFramesArrive() {
        val w = VideoWatchdog()
        assertEquals(VideoWatchdog.State.NO_STREAM, w.state(0))
        w.onStreamOpened(0)
        w.stream(500, 5000)
        assertEquals(VideoWatchdog.State.LIVE, w.state(5100))
        assertNull(w.alert(5100))
        assertFalse(w.shouldRestart(5100))
    }

    @Test
    fun frozenThenRestartedOnceInAWhile() {
        val w = VideoWatchdog(frozenAfterMs = 1000, restartAfterMs = 3000, retryEveryMs = 4000)
        w.onStreamOpened(0)
        w.stream(250, 1000)
        assertEquals(VideoWatchdog.State.LIVE, w.state(1500))
        assertEquals("VIDEO FROZEN", w.alert(2100))
        assertFalse(w.shouldRestart(2500)) // frozen, not long enough
        assertTrue(w.shouldRestart(4100))
        assertFalse(w.shouldRestart(4500)) // just asked
        assertTrue(w.shouldRestart(8200))
        assertEquals(2, w.restarts)
        w.stream(8500, 9000)
        assertEquals(0, w.restarts)
        assertNull(w.alert(9100))
    }

    @Test
    fun aStrayFrameNowAndThenIsWarnedAboutNotRestarted() {
        // a stalled decoder lets one frame through every second and a half
        val w = VideoWatchdog()
        w.onStreamOpened(0)
        w.stream(250, 1000)
        for (t in 2500L..12_000L step 1500) {
            w.onFrames(t, 1)
            assertEquals("at $t", VideoWatchdog.State.FROZEN, w.state(t + 100))
            assertEquals("at $t", "VIDEO SLOW", w.alert(t + 100))
            assertFalse("at $t", w.shouldRestart(t + 100))
        }
    }

    @Test
    fun aSlowPictureIsWarnedAboutNotRestarted() {
        // 2 fps from the start: the emulator, a weak link
        val w = VideoWatchdog()
        w.onStreamOpened(0)
        for (t in 500L..12_000L step 500) {
            w.onFrames(t, 1)
            assertFalse("at $t", w.shouldRestart(t + 50))
        }
        assertEquals("VIDEO SLOW", w.alert(12_050))
    }

    @Test
    fun aNewStreamGetsTimeForItsFirstFrames() {
        val w = VideoWatchdog(firstFrameMs = 4000, firstRestartMs = 10_000)
        w.onStreamOpened(10_000)
        assertNull(w.alert(13_000))
        assertFalse(w.shouldRestart(13_000))
        assertEquals("NO VIDEO", w.alert(14_100))
        assertFalse(w.shouldRestart(14_100)) // a slow start: not restarted yet
        assertTrue(w.shouldRestart(20_100))
    }

    @Test
    fun theAlertStaysWhileTheStreamRestarts() {
        val w = VideoWatchdog()
        w.onStreamOpened(0)
        w.stream(250, 1000)
        assertTrue(w.shouldRestart(4100))
        w.onStreamClosed() // released...
        assertEquals("VIDEO LOST, RESTARTING", w.alert(4200))
        w.onStreamOpened(4600) // ...and opened again: waiting for frames
        assertEquals("VIDEO LOST, RESTARTING", w.alert(5000))
        w.stream(5250, 5750)
        assertNull(w.alert(5800))
    }

    @Test
    fun noAlertWithoutAStream() {
        val w = VideoWatchdog()
        w.onStreamOpened(0)
        w.stream(250, 1000)
        w.onStreamClosed() // link lost: LINK LOST says it
        assertNull(w.alert(60_000))
        assertFalse(w.shouldRestart(60_000))
    }
}
