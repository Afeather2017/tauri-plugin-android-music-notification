package com.plugin.music_notification.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PauseAfterMathTest {
    @Test
    fun computePauseAfterDeadline_returnsZeroForNonPositiveDelay() {
        assertEquals(0L, PauseAfterMath.computePauseAfterDeadline(0L, 1_000L))
        assertEquals(0L, PauseAfterMath.computePauseAfterDeadline(-5L, 1_000L))
    }

    @Test
    fun computePauseAfterDeadline_addsDelayToCurrentTime() {
        assertEquals(61_000L, PauseAfterMath.computePauseAfterDeadline(60_000L, 1_000L))
    }

    @Test
    fun computePauseAfterDeadline_saturatesInsteadOfOverflowing() {
        assertEquals(
            Long.MAX_VALUE,
            PauseAfterMath.computePauseAfterDeadline(Long.MAX_VALUE, 1_000L)
        )
    }

    @Test
    fun computeRemainingPauseAfterDelay_clampsExpiredDeadlinesToZero() {
        assertEquals(0L, PauseAfterMath.computeRemainingPauseAfterDelay(500L, 1_000L))
        assertEquals(500L, PauseAfterMath.computeRemainingPauseAfterDelay(1_500L, 1_000L))
    }
}
