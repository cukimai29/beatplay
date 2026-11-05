package com.utaloom.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automix rules: the settings pair (crossfade length, gapless flag) decides the transition,
 * the blend window decides when the next track is loaded and when the two overlap.
 */
class AutomixTest {

    @Test
    fun transitionFollowsTheSettings() {
        assertEquals(TrackTransition.OFF, Automix.transitionOf(3, gaplessPlayback = false))
        assertEquals(TrackTransition.SEAMLESS, Automix.transitionOf(0, gaplessPlayback = true))
        assertEquals(TrackTransition.CROSSFADE, Automix.transitionOf(4, gaplessPlayback = true))
        assertFalse(TrackTransition.OFF.enabled)
        assertTrue(TrackTransition.SEAMLESS.enabled)
        assertTrue(TrackTransition.CROSSFADE.enabled)
    }

    @Test
    fun blendWindowMatchesTheChosenTransition() {
        assertEquals(0L, Automix.windowMs(TrackTransition.OFF, 5))
        assertEquals(Automix.SEAMLESS_MS, Automix.windowMs(TrackTransition.SEAMLESS, 5))
        assertEquals(3_000L, Automix.windowMs(TrackTransition.CROSSFADE, 3))
        // A nonsense length still yields a usable window instead of breaking the blend.
        assertEquals(1_000L, Automix.windowMs(TrackTransition.CROSSFADE, 0))
        assertEquals(30_000L, Automix.windowMs(TrackTransition.CROSSFADE, 90))
    }

    @Test
    fun blendingStartsInsideTheWindowOnly() {
        val window = 3_000L
        assertFalse(Automix.shouldBlend(3_001L, window))
        assertTrue(Automix.shouldBlend(3_000L, window))
        assertTrue(Automix.shouldBlend(1_500L, window))
        assertTrue(Automix.shouldBlend(0L, window))
        assertFalse(Automix.shouldBlend(10_000L, window))
        // Automix off: never blend.
        assertFalse(Automix.shouldBlend(0L, 0L))
    }

    @Test
    fun preparingStopsOnceTheBlendIsDue() {
        val window = 3_000L
        assertTrue(Automix.shouldPrepare(10_000L, window))
        assertFalse(Automix.shouldPrepare(3_000L, window))
        assertFalse(Automix.shouldPrepare(2_000L, window))
        assertFalse(Automix.shouldPrepare(10_000L, 0L))
    }

    @Test
    fun fadesAreMirroredSoLoudnessStaysEven() {
        assertEquals(0f, Automix.fadeIn(0f), 0.0001f)
        assertEquals(1f, Automix.fadeIn(1f), 0.0001f)
        assertEquals(1f, Automix.fadeOut(0f), 0.0001f)
        assertEquals(0f, Automix.fadeOut(1f), 0.0001f)
        // Equal-power pairing: both halves add up to roughly constant power, not to 1.
        val progress = 0.35f
        val power = Automix.fadeIn(progress) * Automix.fadeIn(progress) + Automix.fadeOut(progress) * Automix.fadeOut(progress)
        assertEquals(1f, power, 0.0001f)
    }

    @Test
    fun fadeCurveIsMonotonicAndClamped() {
        var previous = -1f
        for (step in 0..10) {
            val level = Automix.fadeIn(step / 10f)
            assertTrue("fade in must rise", level > previous)
            previous = level
        }
        assertEquals(1f, Automix.fadeIn(4f), 0.0001f)
        assertEquals(0f, Automix.fadeIn(-4f), 0.0001f)
        assertEquals(1f, Automix.fadeOut(-4f), 0.0001f)
        assertEquals(0f, Automix.fadeOut(4f), 0.0001f)
    }

    @Test
    fun seamlessSwitchIsShortEnoughToFeelInstant() {
        assertTrue(Automix.SEAMLESS_MS <= 200L)
        assertTrue(Automix.STEP_MS <= 50L)
    }
}
