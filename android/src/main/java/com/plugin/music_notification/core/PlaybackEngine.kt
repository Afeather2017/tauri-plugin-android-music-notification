package com.plugin.music_notification.core

/**
 * Transport adapter over the platform player. The core owns at most one
 * engine at a time; engine lifecycle questions (prepared-ness, error state)
 * that [android.media.MediaPlayer] tracks internally but does not expose
 * via queries live here, updated only by the player's own callbacks.
 */
interface PlaybackEngine {
    /** Asynchronously prepare [url]. Must not be called twice without an intervening [release]. */
    fun prepare(url: String, callbacks: EngineCallbacks)

    fun start()

    fun pause()

    fun seekTo(positionMs: Int)

    /** Applies a stereo pair volume in [0, 1]. */
    fun setVolume(volume: Float)

    fun release()

    /** True once preparation finished successfully and before an error/release. */
    val isPrepared: Boolean

    /** True after [EngineCallbacks.onError] until the next [prepare]. */
    val isInErrorState: Boolean

    val isPlaying: Boolean

    val currentPositionMs: Int

    val durationMs: Int

    /** The URL this engine was prepared with, or null before [prepare]. */
    val sourceUrl: String?
}

interface EngineCallbacks {
    fun onPrepared()

    /** [what]/[extra] are the platform error codes; see [android.media.MediaPlayer]. */
    fun onError(what: Int, extra: Int)

    fun onCompletion()
}
