package com.utaloom.playback

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** How BeatPlay moves from one track to the next. */
enum class TrackTransition {
    /** Plain switch. A short silence can be heard between tracks. */
    OFF,

    /** No silence and no audible fade: the tracks swap within a hair's breadth. */
    SEAMLESS,

    /** The tracks are blended over a few seconds, the way Apple Music's Automix works. */
    CROSSFADE,
    ;

    val enabled: Boolean get() = this != OFF
}

/**
 * Pure rules for gapless / Automix transitions (unit-tested, no I/O).
 *
 * libVLC cannot hand two tracks to the sound card at the same instant, so a truly
 * silent-free switch is built from two engines: the next track is resolved, loaded
 * and buffered while the current one is still playing, then the two are blended
 * and the outgoing engine is dropped.
 */
object Automix {
    /** A seamless switch still fades for this long, so the swap can never click. */
    const val SEAMLESS_MS = 140L

    /** Load the next track once this much of the current track is still ahead. */
    const val PREPARE_AHEAD_MS = 20_000L

    /** If VLC cannot start the muted/preloaded stream by then, use the normal track switch. */
    const val PREPARE_READY_TIMEOUT_MS = 12_000L

    /** Volume ramp step: smooth enough for a fade, cheap enough to run for seconds. */
    const val STEP_MS = 20L

    /**
     * Tracks active fade time rather than wall-clock time, so an incoming stream that buffers
     * cannot make the outgoing track fade to silence while there is no replacement audio.
     */
    class FadeClock(durationMs: Long) {
        private val duration = durationMs.coerceAtLeast(1L)
        private var elapsed = 0L

        val progress: Float get() = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        val isComplete: Boolean get() = elapsed >= duration

        fun advanceBy(deltaMs: Long, buffering: Boolean) {
            if (!buffering) elapsed = (elapsed + deltaMs.coerceAtLeast(0L)).coerceAtMost(duration)
        }
    }

    fun transitionOf(crossfadeSeconds: Int, gaplessPlayback: Boolean): TrackTransition = when {
        !gaplessPlayback -> TrackTransition.OFF
        crossfadeSeconds <= 0 -> TrackTransition.SEAMLESS
        else -> TrackTransition.CROSSFADE
    }

    /** Length of the blend window in milliseconds. */
    fun windowMs(transition: TrackTransition, crossfadeSeconds: Int): Long = when (transition) {
        TrackTransition.OFF -> 0L
        TrackTransition.SEAMLESS -> SEAMLESS_MS
        TrackTransition.CROSSFADE -> crossfadeSeconds.coerceIn(1, 30) * 1000L
    }

    /** Equal-power fade level (0..1) of the incoming track at [progress] (0..1). */
    fun fadeIn(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** Equal-power fade level of the outgoing track: the mirror of [fadeIn]. */
    fun fadeOut(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** True once the remaining time of the current track is inside the blend window. */
    fun shouldBlend(remainingMs: Long, windowMs: Long): Boolean =
        windowMs > 0 && remainingMs in 0..windowMs

    /** True while there is still time to load the next track before the blend starts. */
    fun shouldPrepare(remainingMs: Long, windowMs: Long): Boolean =
        windowMs > 0 && remainingMs > windowMs
}
