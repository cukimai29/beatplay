/**
 * BeatPlay in-memory ad overlay state (observed by the player UI).
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.premium

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AdState {
    private val _showing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = _showing.asStateFlow()
    private val _secondsLeft = MutableStateFlow(0)
    val secondsLeft: StateFlow<Int> = _secondsLeft.asStateFlow()
    private val _text = MutableStateFlow("BeatPlay Premium - dengarkan tanpa jeda iklan.")
    val text: StateFlow<String> = _text.asStateFlow()
    private val _nextAdInMs = MutableStateFlow(0L)
    val nextAdInMs: StateFlow<Long> = _nextAdInMs.asStateFlow()

    /** Time left until the next scheduled break (0 when unknown or Premium). */
    fun setNextAdIn(ms: Long) {
        _nextAdInMs.value = ms.coerceAtLeast(0)
    }

    fun isShowing(): Boolean = _showing.value

    /** End the countdown immediately (e.g. video finished early or was skipped). */
    fun finishNow() {
        _secondsLeft.value = 0
    }

    /** Show a blocking ad overlay for [seconds], then invoke [onDone] (counter reset). */
    suspend fun showAdBlocking(
        seconds: Int,
        text: String,
        onDone: () -> Unit,
    ) {
        // A second trigger must not start a parallel countdown that hides the overlay early.
        if (_showing.value) return
        _text.value = text
        _secondsLeft.value = seconds.coerceIn(1, 120)
        _showing.value = true
        try {
            while (_secondsLeft.value > 0) {
                delay(1000)
                _secondsLeft.value = _secondsLeft.value - 1
            }
        } finally {
            _showing.value = false
            onDone()
        }
    }
}
