package com.plugin.music_notification.core

import android.content.Context
import org.json.JSONObject

/**
 * LUFS loudness normalization: configuration, persistence and the pure
 * per-track volume calculation. The network precache client is abstracted
 * behind [LufsClient] so the queue-warming logic stays testable.
 */
data class NormalizationConfig(
    val mode: String = "auto",
    val manualVolume: Float = 0.5f,
    val fixedLufs: Double = -27.0,
    val lufsPrecacheCount: Int = DEFAULT_LUFS_PRECACHE_COUNT
) {
    companion object {
        const val DEFAULT_LUFS_PRECACHE_COUNT = 5
        const val MIN_LUFS_PRECACHE_COUNT = 0
        const val MAX_LUFS_PRECACHE_COUNT = 20
        const val DEFAULT_MIN_LUFS = -29.0
    }
}

fun normalizeLufsPrecacheCount(count: Int): Int {
    return count.coerceIn(
        NormalizationConfig.MIN_LUFS_PRECACHE_COUNT,
        NormalizationConfig.MAX_LUFS_PRECACHE_COUNT
    )
}

fun normalizeNormalizationMode(mode: String?): String {
    return when (mode) {
        "manual", "fixed" -> mode
        else -> "auto"
    }
}

fun normalizePlayMode(rawMode: String?): String {
    return when (rawMode) {
        "shuffle" -> "shuffle"
        "loop" -> "loop"
        else -> "sequential"
    }
}

/**
 * Volume for [track] under [config], given the LUFS values currently known
 * for [tracks]. Pure: callers pass the live queue so the "auto" mode can
 * normalize against the quietest resolved track.
 */
fun calculateTrackVolume(
    config: NormalizationConfig,
    tracks: List<QueueSongInfo>,
    track: QueueSongInfo
): Float {
    val manualVolume = config.manualVolume.coerceIn(0.0f, 1.0f)
    val trackLufs = track.lufs ?: return manualVolume

    val rawVolume = when (config.mode) {
        "fixed" -> {
            Math.pow(10.0, (config.fixedLufs - trackLufs) / 20.0)
        }
        "manual" -> {
            manualVolume.toDouble()
        }
        else -> {
            var minLufs = NormalizationConfig.DEFAULT_MIN_LUFS
            for (t in tracks) {
                if (t.lufs != null) {
                    minLufs = Math.min(t.lufs, minLufs)
                }
            }
            // If no tracks have LUFS (all null), use manual volume
            if (minLufs == NormalizationConfig.DEFAULT_MIN_LUFS && tracks.all { it.lufs == null }) {
                return manualVolume
            }
            Math.pow(10.0, (minLufs - trackLufs) / 20.0)
        }
    }

    return rawVolume.coerceIn(0.0, 1.0).toFloat()
}

/** Fetches (or triggers server-side computation of) a track's LUFS value. */
interface LufsClient {
    fun requestPrecache(track: QueueSongInfo): PrecacheLufsResult?
}

object NormalizationConfigCodec {
    fun load(context: Context): NormalizationConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_NORMALIZATION, null) ?: return NormalizationConfig()

        return try {
            val json = JSONObject(raw)
            NormalizationConfig(
                mode = normalizeNormalizationMode(json.optString("mode", "auto")),
                manualVolume = json.optDouble("manualVolume", 0.5).toFloat(),
                fixedLufs = json.optDouble("fixedLufs", -27.0),
                lufsPrecacheCount = normalizeLufsPrecacheCount(
                    json.optInt("lufsPrecacheCount", NormalizationConfig.DEFAULT_LUFS_PRECACHE_COUNT)
                )
            )
        } catch (e: Exception) {
            NormalizationConfig()
        }
    }

    fun save(context: Context, config: NormalizationConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = JSONObject()
        json.put("mode", config.mode)
        json.put("manualVolume", config.manualVolume.toDouble())
        json.put("fixedLufs", config.fixedLufs)
        json.put("lufsPrecacheCount", normalizeLufsPrecacheCount(config.lufsPrecacheCount))
        prefs.edit()
            .putString(PREF_NORMALIZATION, json.toString())
            .apply()
    }

    const val PREFS_NAME = "music_notification"
    const val PREF_NORMALIZATION = "normalization_config"
}
