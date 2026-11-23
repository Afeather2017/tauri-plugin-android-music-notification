package com.plugin.music_notification.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session persistence codec: round-trip and legacy-format compatibility. */
class SessionCodecTest {

    @Test
    fun durableSessionSnapshot_filtersTemporaryTracksAndRemapsCurrentIndex() {
        val snapshot = SessionSnapshot(
            queue = PlayingQueueSnapshot(
                listOf(song(-1, "temporary"), song(10, "kaulan"), song(11, "local_raw")),
                2
            ),
            runtime = PlaybackRuntimeSnapshot(false, 0L, 0L),
            playMode = "sequential",
            currentSongId = 11
        )

        val durable = JsonSessionCodec.durableSessionSnapshot(snapshot)

        assertEquals(listOf(10L, 11L), durable.queue.songs.map { it.id })
        assertEquals(1, durable.queue.currentIndex)
        assertEquals(11L, durable.currentSongId)
    }

    @Test
    fun durableSessionSnapshot_clearsSelectionWhenTemporaryTrackIsCurrent() {
        val snapshot = SessionSnapshot(
            queue = PlayingQueueSnapshot(
                listOf(song(10, "kaulan"), song(-1, "temporary")),
                1
            ),
            runtime = PlaybackRuntimeSnapshot(true, 5L, 10L),
            playMode = "loop",
            currentSongId = -1
        )

        val durable = JsonSessionCodec.durableSessionSnapshot(snapshot)

        assertEquals(listOf(10L), durable.queue.songs.map { it.id })
        assertNull(durable.queue.currentIndex)
        assertNull(durable.currentSongId)
        assertEquals(false, durable.runtime.isPlaying)
        assertEquals(0L, durable.runtime.positionMs)
        assertEquals(0L, durable.runtime.durationMs)
    }

    @Test
    fun sessionJson_roundTripsThroughPersistedFormat() {
        val snapshot = SessionSnapshot(
            queue = PlayingQueueSnapshot(
                listOf(
                    song(10, "kaulan", lufs = -18.5),
                    song(11, "local_raw")
                ),
                1
            ),
            runtime = PlaybackRuntimeSnapshot(false, 65_000L, 180_000L),
            playMode = "shuffle",
            currentSongId = 11L
        )

        val restored = JsonSessionCodec.jsonToSession(JsonSessionCodec.sessionToJson(snapshot))

        assertEquals(2, restored.queue.songs.size)
        assertEquals(10L, restored.queue.songs[0].id)
        assertEquals("device-10", restored.queue.songs[0].deviceId)
        assertEquals(-18.5, restored.queue.songs[0].lufs!!, 0.0)
        assertEquals("content://media/11", restored.queue.songs[1].localUri)
        assertEquals(1, restored.queue.currentIndex)
        assertEquals(65_000L, restored.runtime.positionMs)
        assertEquals(180_000L, restored.runtime.durationMs)
        assertEquals("shuffle", restored.playMode)
        assertEquals(11L, restored.currentSongId)
    }

    @Test
    fun jsonToSession_readsLegacyFormatWrittenByEarlierVersions() {
        // Exact shape persisted by pre-refactor builds (camelCase keys,
        // null sentinels, no tempSongUrl on durable entries).
        val legacy = """
        {
          "queue": {
            "songs": [
              {
                "id": 42,
                "name": "Legacy Track",
                "deviceId": "device-42",
                "sourceKind": "kaulan",
                "localUri": null,
                "lufs": null,
                "coverUrl": null
              }
            ],
            "currentIndex": 0
          },
          "runtime": {
            "isPlaying": false,
            "positionMs": 1234,
            "durationMs": 9999
          },
          "playMode": "loop",
          "currentSongId": 42
        }
        """.trimIndent()

        val restored = JsonSessionCodec.jsonToSession(JSONObject(legacy))

        assertEquals(1, restored.queue.songs.size)
        assertEquals("Legacy Track", restored.queue.songs[0].name)
        assertEquals(0, restored.queue.currentIndex)
        assertEquals(1234L, restored.runtime.positionMs)
        assertEquals("loop", restored.playMode)
        assertEquals(42L, restored.currentSongId)
    }

    @Test
    fun sessionToJson_neverPersistsTemporaryTracks() {
        val snapshot = SessionSnapshot(
            queue = PlayingQueueSnapshot(listOf(song(-1, "temporary"), song(10, "kaulan")), 0),
            runtime = PlaybackRuntimeSnapshot(true, 0L, 0L),
            playMode = "sequential",
            currentSongId = -1
        )

        val json = JsonSessionCodec.sessionToJson(snapshot)

        val songs = json.getJSONObject("queue").getJSONArray("songs")
        assertEquals(1, songs.length())
        assertEquals(10L, songs.getJSONObject(0).getLong("id"))
        assertTrue(json.getJSONObject("queue").isNull("currentIndex"))
    }
}
