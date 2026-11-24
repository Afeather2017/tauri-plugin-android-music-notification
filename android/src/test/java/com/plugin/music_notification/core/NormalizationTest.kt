package com.plugin.music_notification.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizationTest {

    @Test
    fun calculateTrackVolume_manualModeIgnoresLufs() {
        val config = NormalizationConfig(mode = "manual", manualVolume = 0.4f)
        val track = song(1L, lufs = -10.0)

        assertEquals(0.4f, calculateTrackVolume(config, listOf(track), track))
    }

    @Test
    fun calculateTrackVolume_withoutLufsFallsBackToManualVolume() {
        val config = NormalizationConfig(mode = "auto", manualVolume = 0.7f)
        val track = song(1L, lufs = null)

        assertEquals(0.7f, calculateTrackVolume(config, listOf(track), track))
    }

    @Test
    fun calculateTrackVolume_fixedModeAttenuatesLouderTrack() {
        val config = NormalizationConfig(mode = "fixed", fixedLufs = -20.0)
        val track = song(1L, lufs = -10.0)

        // -20 - (-10) = -10 dB → 10^(-10/20) ≈ 0.316
        assertEquals(0.316f, calculateTrackVolume(config, listOf(track), track), 0.001f)
    }

    @Test
    fun calculateTrackVolume_autoModeNormalizesAgainstQuietestKnownTrack() {
        val config = NormalizationConfig(mode = "auto", manualVolume = 0.5f)
        val tracks = listOf(song(1L, lufs = -10.0), song(2L, lufs = -30.0))

        // min LUFS = -30; track 1: 10^((-30 - -10)/20) = 10^(-1) = 0.1
        assertEquals(0.1f, calculateTrackVolume(config, tracks, tracks[0]), 0.001f)
        // track 2 is the reference: 10^0 = 1.0
        assertEquals(1.0f, calculateTrackVolume(config, tracks, tracks[1]), 0.001f)
    }

    @Test
    fun calculateTrackVolume_neverExceedsOne() {
        val config = NormalizationConfig(mode = "fixed", fixedLufs = 0.0)
        val track = song(1L, lufs = -40.0)

        assertEquals(1.0f, calculateTrackVolume(config, listOf(track), track))
    }

    @Test
    fun normalizeLufsPrecacheCount_clampsToSupportedRange() {
        assertEquals(0, normalizeLufsPrecacheCount(-3))
        assertEquals(5, normalizeLufsPrecacheCount(5))
        assertEquals(20, normalizeLufsPrecacheCount(99))
    }

    @Test
    fun normalizeNormalizationMode_mapsUnknownValuesToAuto() {
        assertEquals("manual", normalizeNormalizationMode("manual"))
        assertEquals("fixed", normalizeNormalizationMode("fixed"))
        assertEquals("auto", normalizeNormalizationMode("loud"))
        assertEquals("auto", normalizeNormalizationMode(null))
    }
}
