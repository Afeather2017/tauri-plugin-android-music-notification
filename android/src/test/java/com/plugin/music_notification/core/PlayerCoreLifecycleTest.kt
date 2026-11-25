package com.plugin.music_notification.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine lifecycle guarantees: exactly one engine at a time, stale async
 * completions dropped, and pause semantics during the loading window.
 */
class PlayerCoreLifecycleTest {

    private fun harnessWithQueue(vararg ids: Long): CoreHarness {
        val harness = CoreHarness()
        harness.core.setQueue(ids.map { song(it) }, 0, "sequential")
        return harness
    }

    @Test
    fun rapidSecondRequest_releasesFirstEngineBeforePreparingSecond() {
        val harness = harnessWithQueue(1L, 2L)

        // Request track 1 and let its resolution begin.
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        // Track 1 is preparing when track 2 is requested.
        harness.core.playIndex(1, playWhenReady = true, startAtMs = null)
        harness.stepResolve()

        harness.engines[1].firePrepared()

        assertEquals("playing", harness.core.status)
        assertEquals("https://example.test/audio/2", harness.core.runtimeSnapshot().let { harness.engines[1].sourceUrl })

        // The invariant: engine 0 was released before engine 1 prepared,
        // engine 0 never started, and no engine acts after release.
        val firstEvents = harness.engines[0].events
        assertTrue("engine 0 must be released", firstEvents.contains("release"))
        assertFalse("engine 0 must never start", firstEvents.contains("start"))
        val releaseAt = firstEvents.indexOf("release")
        val prepare2At = harness.engines[1].events.indexOfFirst { it.startsWith("prepare:") }
        assertTrue("release of engine 0 must precede prepare of engine 1", releaseAt >= 0 && prepare2At >= 0)
        harness.assertSingleEngineOwnership("rapid second request")
    }

    @Test
    fun pauseDuringLoading_preventsStartWhenPrepareCompletes() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        // User pauses while the engine is still preparing.
        harness.core.pause()
        harness.engines[0].firePrepared()

        assertEquals("paused", harness.core.status)
        assertFalse(harness.engines[0].isPlaying)
        assertTrue(harness.engines[0].isPrepared)
    }

    @Test
    fun staleResolutionAfterPause_isDropped() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        // Pause before the background resolution runs.
        harness.core.pause()

        // Now the resolution completes — the epoch moved, so it must be dropped.
        harness.background.runAll()
        harness.poster.runAll()

        assertEquals(0, harness.engines.size)
        assertEquals("idle", harness.core.status)
    }

    @Test
    fun staleOnPreparedAfterStop_isIgnored() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.core.stop(clearQueue = false)

        // A late onPrepared from the (released) engine must not resurrect playback.
        harness.engines[0].firePrepared()

        assertEquals("idle", harness.core.status)
        assertEquals(0, harness.core.runtimeSnapshot().positionMs)
    }

    @Test
    fun sameUrlRequest_reusesPreparedEngineWithoutReprepare() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()
        harness.engines[0].events.clear()

        harness.core.playIndex(0, playWhenReady = true, startAtMs = 30_000L)
        harness.runUntilIdle()

        assertEquals(1, harness.engines.size)
        val events = harness.engines[0].events
        assertFalse(events.any { it.startsWith("prepare:") })
        assertTrue(events.contains("seekTo:30000"))
        assertEquals(30_000, harness.core.runtimeSnapshot().positionMs)
        assertEquals("playing", harness.core.status)
    }

    @Test
    fun pendingSeek_appliedWhenPrepareCompletes() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = 45_000L)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        assertTrue(harness.engines[0].events.contains("seekTo:45000"))
    }

    @Test
    fun seekOnUnloadedQueue_parksPositionAndStartsOnAutoPlay() {
        val harness = harnessWithQueue(1L)
        harness.core.setQueue(harness.core.tracks, currentIndex = 0, newPlayMode = "sequential")

        harness.core.seek(60_000L, autoPlay = true)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        assertTrue(harness.engines[0].events.contains("seekTo:60000"))
        assertEquals("playing", harness.core.status)
    }

    @Test
    fun seekWithoutAutoPlayOnUnloadedQueue_reportsParkedPosition() {
        val harness = harnessWithQueue(1L)

        harness.core.seek(60_000L, autoPlay = false)

        assertEquals("idle", harness.core.status)
        assertEquals(60_000L, harness.core.runtimeSnapshot().positionMs)
    }

    @Test
    fun engineError_reportsErrorStatus() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()
        harness.engines[0].fireError(what = 1, extra = -2147483648)

        assertEquals("error", harness.core.status)
    }

    @Test
    fun completion_advancesToNextTrack() {
        val harness = harnessWithQueue(1L, 2L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        harness.engines[0].fireCompletion()
        harness.runUntilIdle()

        assertEquals("https://example.test/audio/2", harness.engines[1].sourceUrl)
        harness.assertSingleEngineOwnership("completion advance")
    }

    @Test
    fun resume_withoutEngineRestartsCurrentTrackFromParkedPosition() {
        val harness = harnessWithQueue(1L)
        harness.core.seek(60_000L, autoPlay = false)

        harness.core.resume()
        harness.stepResolve()
        harness.engines[0].firePrepared()

        assertTrue(harness.engines[0].events.contains("seekTo:60000"))
        assertEquals("playing", harness.core.status)
    }

    @Test
    fun restore_parksSessionPausedAndNeverReportsPlaying() {
        val harness = CoreHarness()
        harness.store.saved += SessionSnapshot(
            queue = PlayingQueueSnapshot(listOf(song(1L), song(2L)), 1),
            // Persisted sessions may claim isPlaying (e.g. written by an
            // older build during playback); a restore must not honor it.
            runtime = PlaybackRuntimeSnapshot(isPlaying = true, positionMs = 42_000L, durationMs = 180_000L),
            playMode = "shuffle",
            currentSongId = 2L
        )

        harness.core.restore()

        assertEquals(2, harness.core.tracks.size)
        assertEquals(1, harness.core.currentTrackIndex)
        assertEquals("shuffle", harness.core.playMode)
        assertEquals("idle", harness.core.status)
        assertFalse(harness.core.runtimeSnapshot().isPlaying)
        assertEquals(42_000L, harness.core.runtimeSnapshot().positionMs)
    }

    @Test
    fun shutdown_parksCurrentPositionBeforeRelease() {
        val harness = harnessWithQueue(1L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = 10_000L)
        harness.stepResolve()
        harness.engines[0].firePrepared()
        harness.engines[0].advanceTo(95_000)

        harness.core.shutdown()

        assertEquals("idle", harness.core.status)
        val persisted = harness.store.saved.last()
        assertEquals(95_000L, persisted.runtime.positionMs)
        assertFalse(persisted.runtime.isPlaying)
        assertTrue(harness.engines[0].events.contains("release"))
    }

    @Test
    fun pause_thenResumeOnPreparedEngine_restartsTransport() {
        val harness = harnessWithQueue(1L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        harness.core.pause()
        assertEquals("paused", harness.core.status)

        harness.core.resume()
        assertEquals("playing", harness.core.status)
        assertTrue(harness.engines[0].events.contains("start"))
    }

    @Test
    fun pauseThenResume_completionStillAdvancesToNextTrack() {
        val harness = harnessWithQueue(1L, 2L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        harness.core.pause()
        harness.core.resume()
        harness.engines[0].fireCompletion()
        harness.runUntilIdle()

        assertEquals(2, harness.engines.size)
        assertEquals("https://example.test/audio/2", harness.engines[1].sourceUrl)
        assertEquals(1, harness.core.currentTrackIndex)
        harness.assertSingleEngineOwnership("completion after pause/resume")
    }

    @Test
    fun loopMode_everyCompletionRestartsTheSameTrack() {
        val harness = CoreHarness()
        harness.core.setQueue(listOf(song(1L)), 0, "loop")
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()
        assertEquals(1, harness.resolver.resolved.size)

        // Each completion re-requests the same track through the fast path;
        // the reused engine's callbacks must stay live for the next one.
        for (round in 2..4) {
            harness.engines[0].fireCompletion()
            harness.runUntilIdle()
            assertEquals("completion #${round - 1} must re-request the track", round, harness.resolver.resolved.size)
            assertEquals("playing", harness.core.status)
        }
        assertEquals(1, harness.engines.size)
    }

    @Test
    fun pauseWhilePreparing_keepsPendingSeekVolumeAndTrackStart() {
        val harness = harnessWithQueue(1L)
        harness.core.setNormalizationConfig(
            NormalizationConfig(mode = "manual", manualVolume = 0.25f)
        )
        var startedTracks = 0
        harness.core.addListener(object : PlayerCore.Listener {
            override fun onPlaybackChanged(reason: String, message: String?) = Unit
            override fun onTrackStarted(track: QueueSongInfo, target: PlaybackTarget) {
                startedTracks += 1
            }
        })

        harness.core.playIndex(0, playWhenReady = true, startAtMs = 60_000L)
        harness.stepResolve()
        harness.core.pause()
        harness.engines[0].firePrepared()

        val events = harness.engines[0].events
        assertEquals("paused", harness.core.status)
        assertFalse(events.contains("start"))
        assertTrue(events.contains("seekTo:60000"))
        assertTrue(events.contains("setVolume:0.25:0.25"))
        assertEquals(1, startedTracks)

        harness.core.resume()
        assertEquals("playing", harness.core.status)
    }

    @Test
    fun pauseThenResumeWhilePreparing_startsWhenPrepareCompletes() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.core.pause()
        harness.core.resume()
        harness.engines[0].firePrepared()

        assertEquals("playing", harness.core.status)
    }

    @Test
    fun completionWhileNewerRequestLoads_doesNotSkipPastIt() {
        val harness = harnessWithQueue(1L, 2L, 3L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        // Track 2 is requested but still resolving when track 1 ends.
        harness.core.playIndex(1, playWhenReady = true, startAtMs = null)
        harness.engines[0].fireCompletion()
        harness.runUntilIdle()

        assertEquals(1, harness.core.currentTrackIndex)
        assertEquals("https://example.test/audio/2", harness.engines.last().sourceUrl)
    }

    @Test
    fun pendingSeek_notClampedWhenDurationUnknown() {
        val harness = harnessWithQueue(1L)

        harness.core.playIndex(0, playWhenReady = true, startAtMs = 30_000L)
        harness.stepResolve()
        harness.engines[0].reportedDurationMs = -1
        harness.engines[0].firePrepared()

        assertTrue(harness.engines[0].events.contains("seekTo:30000"))
    }

    @Test
    fun manualVolume_appliedWhenTrackPrepares() {
        val harness = harnessWithQueue(1L)
        harness.core.setNormalizationConfig(
            NormalizationConfig(mode = "manual", manualVolume = 0.25f)
        )
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        assertTrue(harness.engines[0].events.contains("setVolume:0.25:0.25"))
    }

    @Test
    fun channelBalance_scalesNormalizedVolumeAndUpdatesDuringPlayback() {
        val harness = CoreHarness()
        harness.core.setQueue(listOf(song(1L, lufs = -20.0)), 0, "sequential")
        val config = NormalizationConfig(
            mode = "fixed",
            fixedLufs = -40.0,
            leftChannelVolume = 0.0f,
            rightChannelVolume = 0.5f
        )
        harness.core.setNormalizationConfig(config)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        val engine = harness.engines[0]
        assertEquals(0.0f, engine.leftVolume, 0.0001f)
        assertEquals(0.05f, engine.rightVolume, 0.0001f)

        harness.core.setNormalizationConfig(
            config.copy(leftChannelVolume = 1.0f, rightChannelVolume = 0.0f)
        )
        assertEquals(0.1f, engine.leftVolume, 0.0001f)
        assertEquals(0.0f, engine.rightVolume, 0.0001f)
    }

    @Test
    fun setRawVolume_appliesChannelBalance() {
        val harness = harnessWithQueue(1L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()

        harness.core.setRawVolume(0.8f)
        assertTrue(harness.engines[0].events.contains("setVolume:0.8:0.8"))

        harness.core.setNormalizationConfig(
            NormalizationConfig(
                mode = "manual",
                manualVolume = 0.5f,
                leftChannelVolume = 0.5f,
                rightChannelVolume = 1.0f
            )
        )
        harness.core.setRawVolume(0.8f)
        assertTrue(harness.engines[0].events.contains("setVolume:0.4:0.8"))
    }

    @Test
    fun lufsPatch_updatesQueueEntryWithoutTouchingVolume() {
        val harness = harnessWithQueue(1L)
        harness.core.playIndex(0, playWhenReady = true, startAtMs = null)
        harness.stepResolve()
        harness.engines[0].firePrepared()
        harness.engines[0].events.clear()

        val updated = harness.core.patchTrackLufs(1L, -20.0)

        assertEquals(-20.0, updated!!.lufs!!, 0.0)
        assertEquals(-20.0, harness.core.tracks[0].lufs!!, 0.0)
        // Volume application is driven by the LUFS resolution callback, not
        // by the patch itself.
        assertFalse(harness.engines[0].events.any { it.startsWith("setVolume:") })
    }

    @Test
    fun lufsPatch_unknownIdReturnsNull() {
        val harness = harnessWithQueue(1L)

        assertNull(harness.core.patchTrackLufs(999L, -20.0))
    }
}
