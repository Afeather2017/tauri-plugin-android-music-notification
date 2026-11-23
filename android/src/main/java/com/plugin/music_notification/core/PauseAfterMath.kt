package com.plugin.music_notification.core

/** Pure deadline arithmetic for the sleep-timer ("pause after") feature. */
object PauseAfterMath {

    fun computePauseAfterDeadline(delayMs: Long, nowMs: Long): Long {
        if (delayMs <= 0L) {
            return 0L
        }

        return if (Long.MAX_VALUE - nowMs < delayMs) {
            Long.MAX_VALUE
        } else {
            nowMs + delayMs
        }
    }

    fun computeRemainingPauseAfterDelay(deadlineMs: Long, nowMs: Long): Long {
        if (deadlineMs <= 0L) {
            return 0L
        }

        return maxOf(0L, deadlineMs - nowMs)
    }
}
