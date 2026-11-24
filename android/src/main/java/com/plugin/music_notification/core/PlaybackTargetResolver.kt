package com.plugin.music_notification.core

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a queue entry to a concrete playable URL. `kaulan` entries go
 * through the local discovery service; the other source kinds carry their
 * own locator.
 */
interface PlaybackTargetResolver {
    fun resolve(track: QueueSongInfo): PlaybackTarget?
}

class HttpPlaybackTargetResolver(
    private val discoveryBase: String = "http://localhost:2080"
) : PlaybackTargetResolver {

    override fun resolve(track: QueueSongInfo): PlaybackTarget? {
        return when (track.sourceKind) {
            "kaulan" -> track.deviceId?.let { resolveDeviceApiBase(it) }?.let { apiBase ->
                PlaybackTarget(
                    audioUrl = "$apiBase/music/id/${track.id}",
                    coverUrl = "$apiBase/music/id/${track.id}/cover"
                )
            }
            "local_raw" -> track.localUri?.takeIf { it.isNotBlank() }?.let {
                PlaybackTarget(audioUrl = it, coverUrl = track.coverUrl)
            }
            "temporary" -> track.tempSongUrl?.takeIf { it.isNotBlank() }?.let {
                PlaybackTarget(audioUrl = it, coverUrl = track.coverUrl)
            }
            else -> null
        }
    }

    private fun resolveDeviceApiBase(deviceId: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val encodedId = URLEncoder.encode(deviceId, "UTF-8")
            val resolverUrl = "$discoveryBase/api/discovery/resolutions/$encodedId"
            connection = URL(resolverUrl)
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 1000
            connection.readTimeout = 1000
            val responseCode = connection.responseCode
            if (responseCode != 200) {
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("api_url", "").ifBlank { null }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}

/** HTTP implementation of [LufsClient] posting to the server's precache endpoint. */
class HttpLufsClient(
    private val resolver: PlaybackTargetResolver
) : LufsClient {

    override fun requestPrecache(track: QueueSongInfo): PrecacheLufsResult? {
        val precacheUrl = buildPrecacheUrl(track) ?: return null
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(precacheUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.doOutput = false

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            } ?: return null

            val body = stream.bufferedReader().use { it.readText() }
            if (body.isBlank()) {
                return PrecacheLufsResult(statusCode in 200..299, null, null)
            }

            val json = JSONObject(body)
            PrecacheLufsResult(
                success = json.optBoolean("success", statusCode in 200..299),
                lufs = if (json.has("lufs") && !json.isNull("lufs")) json.optDouble("lufs") else null,
                cached = if (json.has("cached") && !json.isNull("cached")) json.optBoolean("cached") else null
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun buildPrecacheUrl(track: QueueSongInfo): String? {
        if (track.id <= 0L) {
            return null
        }

        val precacheBase = resolver.resolve(track)?.audioUrl ?: return null
        val parsed = Uri.parse(precacheBase)
        val path = parsed.path ?: return null
        val musicMarker = "/music/id/${track.id}"
        val markerIndex = path.indexOf(musicMarker)
        if (markerIndex < 0) {
            return null
        }

        val prefixPath = path.substring(0, markerIndex)
        return parsed.buildUpon()
            .path("${prefixPath}/music/${track.id}/precache-lufs")
            .clearQuery()
            .build()
            .toString()
    }
}
