package com.plugin.music_notification.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Queue navigation and acceptance semantics of [PlayerCore]. */
class PlayerCoreQueueTest {

    private fun harnessWithQueue(vararg ids: Long): CoreHarness {
        val harness = CoreHarness()
        harness.core.setQueue(ids.map { song(it) }, 0, "sequential")
        return harness
    }

    @Test
    fun playIndex_rejectsOutOfBoundsIndex() {
        val harness = harnessWithQueue(1L, 2L)

        val result = harness.core.playIndex(5, playWhenReady = true, startAtMs = null)

        assertFalse(result.success)
        assertTrue(result.message!!.contains("out of bounds"))
        assertEquals(0, harness.engines.size)
    }

    @Test
    fun playIndex_acceptsAndResolvesRequestedTrack() {
        val harness = harnessWithQueue(1L, 2L)

        val result = harness.core.playIndex(1, playWhenReady = true, startAtMs = null)

        assertTrue(result.success)
        assertEquals("loading", harness.core.status)
        harness.stepResolve()
        assertEquals("https://example.test/audio/2", harness.engines[0].sourceUrl)
        harness.engines[0].firePrepared()
        assertEquals("playing", harness.core.status)
        assertEquals(2L, harness.core.snapshot().currentSongId)
    }

    @Test
    fun playIndex_withPlayWhenReadyFalse_staysPausedAfterPrepare() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = false, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        assertEquals("paused", harness.core.status)
        assertFalse(harness.engines[0].isPlaying)
        assertTrue(harness.engines[0].isPrepared)
    }

    @Test
    fun next_wrapsSequentialQueue() {
        val harness = harnessWithQueue(1L, 2L, 3L)

        harness.core.next()
        harness.stepResolve()
        assertEquals("https://example.test/audio/2", harness.engines[0].sourceUrl)

        harness.core.next()
        harness.core.next()
        harness.runUntilIdle()
        assertEquals("https://example.test/audio/1", harness.engines.last().sourceUrl)
    }

    @Test
    fun previous_wrapsBackward() {
        val harness = harnessWithQueue(1L, 2L)

        harness.core.previous()
        harness.stepResolve()
        assertEquals("https://example.test/audio/2", harness.engines[0].sourceUrl)
    }

    @Test
    fun next_inLoopModeReplaysCurrentTrack() {
        val harness = harnessWithQueue(1L, 2L)
        harness.core.setPlayMode("loop")

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        harness.core.next()
        harness.stepResolve()

        assertEquals("https://example.test/audio/1", harness.engines.last().sourceUrl)
    }

    @Test
    fun next_onEmptyQueueFails() {
        val harness = CoreHarness()

        val result = harness.core.next()

        assertFalse(result.success)
    }

    @Test
    fun unresolvableTrack_skipsForwardToNextTrack() {
        val harness = harnessWithQueue(1L, 2L)
        harness.resolver.unresolvableIds.add(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        // Resolution of track 1 fails; the skip continuation is posted back.
        harness.runUntilIdle()

        assertEquals("https://example.test/audio/2", harness.engines[0].sourceUrl)
        assertEquals(2L, harness.core.snapshot().currentSongId)
    }

    @Test
    fun allTracksUnresolvable_stopsWithoutClearingQueue() {
        val harness = harnessWithQueue(1L, 2L)
        harness.resolver.unresolvableIds.addAll(listOf(1L, 2L))

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.runUntilIdle()

        assertEquals("idle", harness.core.status)
        assertEquals(2, harness.core.tracks.size)
        assertEquals(0, harness.engines.size)
    }

    @Test
    fun setQueue_normalizesOutOfBoundsSelection() {
        val harness = CoreHarness()

        harness.core.setQueue(listOf(song(1L), song(2L)), currentIndex = 9, newPlayMode = "shuffle")

        assertEquals(1, harness.core.currentTrackIndex)
        assertEquals("shuffle", harness.core.playMode)
    }

    @Test
    fun setQueue_onEmptyListClearsSelection() {
        val harness = harnessWithQueue(1L)

        harness.core.setQueue(emptyList(), currentIndex = null, newPlayMode = null)

        assertEquals(-1, harness.core.currentTrackIndex)
        assertEquals("idle", harness.core.status)
    }
}
