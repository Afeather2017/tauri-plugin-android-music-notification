package com.plugin.music_notification.core

/**
 * Queue-domain models owned by the playback core. These are the things
 * [android.media.MediaPlayer] knows nothing about: the queue, the selection
 * and the resolved playback target. Transport state (playing/position/
 * duration) is intentionally absent — it is always queried from the engine
 * at snapshot time instead of being mirrored here.
 */

data class QueueSongInfo(
    val id: Long,
    val name: String,
    val deviceId: String?,
    val sourceKind: String,
    val localUri: String?,
    val tempSongUrl: String?,
    val lufs: Double?,
    val coverUrl: String?
) {
    fun hasValidLocator(): Boolean = when (sourceKind) {
        "kaulan" -> id > 0L && !deviceId.isNullOrBlank()
        "local_raw" -> !localUri.isNullOrBlank()
        "temporary" -> !tempSongUrl.isNullOrBlank()
        else -> false
    }
}

data class PlaybackTarget(
    val audioUrl: String,
    val coverUrl: String?
)

data class PlayingQueueSnapshot(
    val songs: List<QueueSongInfo>,
    val currentIndex: Int?
)

data class PlaybackRuntimeSnapshot(
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long
)

data class SessionSnapshot(
    val queue: PlayingQueueSnapshot,
    val runtime: PlaybackRuntimeSnapshot,
    val playMode: String,
    val currentSongId: Long?
)

data class PrecacheLufsResult(
    val success: Boolean,
    val lufs: Double?,
    val cached: Boolean?
)

/** Stable identity for a queue entry, used to deduplicate LUFS precache work. */
fun trackIdentityKey(track: QueueSongInfo): String = when (track.sourceKind) {
    "kaulan" -> "kaulan:${track.deviceId}:${track.id}"
    "local_raw" -> "local:${track.localUri}"
    "temporary" -> "temporary:${track.tempSongUrl}"
    else -> "invalid:${track.id}:${track.name}"
}
