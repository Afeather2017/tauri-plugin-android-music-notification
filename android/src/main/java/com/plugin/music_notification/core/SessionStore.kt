package com.plugin.music_notification.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence boundary for the playback session. [JsonSessionCodec] is pure
 * and byte-compatible with the format written by earlier plugin versions, so
 * sessions persisted before the refactor still restore.
 */
interface SessionStore {
    fun load(): SessionSnapshot
    fun save(snapshot: SessionSnapshot)
}

object JsonSessionCodec {

    fun emptySessionSnapshot(): SessionSnapshot = SessionSnapshot(
        queue = PlayingQueueSnapshot(emptyList(), null),
        runtime = PlaybackRuntimeSnapshot(false, 0L, 0L),
        playMode = "sequential",
        currentSongId = null
    )

    /**
     * Strip everything that cannot survive a process restart: `temporary`
     * tracks (their URLs are ephemeral) and entries without a valid locator.
     * The selection is remapped, or cleared when it pointed at a dropped
     * track — a restored selection never fabricates playback state.
     */
    fun durableSessionSnapshot(snapshot: SessionSnapshot): SessionSnapshot {
        val retained = snapshot.queue.songs.withIndex()
            .filter { it.value.sourceKind != "temporary" && it.value.hasValidLocator() }
        val durableIndex = snapshot.queue.currentIndex?.let { selected ->
            retained.indexOfFirst { it.index == selected }.takeIf { it >= 0 }
        }
        val durableSongs = retained.map { it.value }
        return snapshot.copy(
            queue = PlayingQueueSnapshot(durableSongs, durableIndex),
            runtime = if (durableIndex == null) {
                PlaybackRuntimeSnapshot(false, 0L, 0L)
            } else {
                snapshot.runtime
            },
            currentSongId = durableIndex?.let { durableSongs[it].id }
        )
    }

    fun sessionToJson(snapshot: SessionSnapshot): JSONObject {
        val durableSnapshot = durableSessionSnapshot(snapshot)
        val queueJson = JSONObject()
        val songsJson = JSONArray()

        durableSnapshot.queue.songs.forEach { song ->
            val songJson = JSONObject()
            songJson.put("id", song.id)
            songJson.put("name", song.name)
            if (song.deviceId == null) songJson.put("deviceId", JSONObject.NULL) else songJson.put("deviceId", song.deviceId)
            songJson.put("sourceKind", song.sourceKind)
            if (song.localUri == null) songJson.put("localUri", JSONObject.NULL) else songJson.put("localUri", song.localUri)
            if (song.lufs == null) {
                songJson.put("lufs", JSONObject.NULL)
            } else {
                songJson.put("lufs", song.lufs)
            }
            if (song.coverUrl == null) {
                songJson.put("coverUrl", JSONObject.NULL)
            } else {
                songJson.put("coverUrl", song.coverUrl)
            }
            songsJson.put(songJson)
        }

        queueJson.put("songs", songsJson)
        if (durableSnapshot.queue.currentIndex == null) {
            queueJson.put("currentIndex", JSONObject.NULL)
        } else {
            queueJson.put("currentIndex", durableSnapshot.queue.currentIndex)
        }

        val runtimeJson = JSONObject()
        runtimeJson.put("isPlaying", durableSnapshot.runtime.isPlaying)
        runtimeJson.put("positionMs", durableSnapshot.runtime.positionMs)
        runtimeJson.put("durationMs", durableSnapshot.runtime.durationMs)

        val json = JSONObject()
        json.put("queue", queueJson)
        json.put("runtime", runtimeJson)
        json.put("playMode", durableSnapshot.playMode)
        if (durableSnapshot.currentSongId == null) {
            json.put("currentSongId", JSONObject.NULL)
        } else {
            json.put("currentSongId", durableSnapshot.currentSongId)
        }
        return json
    }

    /**
     * Android's org.json returns the literal string "null" from optString
     * for a JSON null, so nullable fields must be checked with isNull first.
     */
    private fun optNullableString(json: JSONObject, key: String): String? {
        if (json.isNull(key)) {
            return null
        }
        return json.optString(key, "").ifBlank { null }
    }

    fun jsonToSession(json: JSONObject): SessionSnapshot {
        val queueJson = json.optJSONObject("queue") ?: JSONObject()
        val songsJson = queueJson.optJSONArray("songs") ?: JSONArray()
        val songs = mutableListOf<QueueSongInfo>()
        for (i in 0 until songsJson.length()) {
            val songJson = songsJson.optJSONObject(i) ?: continue
            val song = QueueSongInfo(
                id = songJson.optLong("id", -1L),
                name = songJson.optString("name", ""),
                deviceId = optNullableString(songJson, "deviceId"),
                sourceKind = songJson.optString("sourceKind", ""),
                localUri = optNullableString(songJson, "localUri"),
                tempSongUrl = optNullableString(songJson, "tempSongUrl"),
                lufs = if (songJson.has("lufs") && !songJson.isNull("lufs")) songJson.optDouble("lufs") else null,
                coverUrl = optNullableString(songJson, "coverUrl")
            )
            if (song.hasValidLocator()) {
                songs.add(song)
            }
        }

        val runtimeJson = json.optJSONObject("runtime") ?: JSONObject()
        val currentIndex = if (queueJson.has("currentIndex") && !queueJson.isNull("currentIndex")) {
            queueJson.optInt("currentIndex")
        } else {
            null
        }

        return SessionSnapshot(
            queue = PlayingQueueSnapshot(songs, currentIndex),
            runtime = PlaybackRuntimeSnapshot(
                isPlaying = runtimeJson.optBoolean("isPlaying", false),
                positionMs = runtimeJson.optLong("positionMs", 0L),
                durationMs = runtimeJson.optLong("durationMs", 0L)
            ),
            playMode = json.optString("playMode", "sequential"),
            currentSongId = if (json.has("currentSongId") && !json.isNull("currentSongId")) {
                json.optLong("currentSongId")
            } else {
                null
            }
        )
    }
}

/** [android.content.SharedPreferences]-backed [SessionStore]. */
class PrefsSessionStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SessionSnapshot {
        val raw = prefs.getString(PREF_SESSION, null) ?: return JsonSessionCodec.emptySessionSnapshot()
        return try {
            JsonSessionCodec.jsonToSession(JSONObject(raw))
        } catch (e: Exception) {
            JsonSessionCodec.emptySessionSnapshot()
        }
    }

    override fun save(snapshot: SessionSnapshot) {
        prefs.edit()
            .putString(PREF_SESSION, JsonSessionCodec.sessionToJson(snapshot).toString())
            .apply()
    }

    companion object {
        const val PREFS_NAME = "music_notification"
        const val PREF_SESSION = "playback_session"
    }
}
