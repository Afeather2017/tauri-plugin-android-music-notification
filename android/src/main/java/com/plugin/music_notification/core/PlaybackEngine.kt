package com.plugin.music_notification.core

/**
 * Transport adapter over the platform player. The core owns at most one
 * engine at a time. The adapter tracks preparation and errors through
 * callbacks from its player.
 */
interface PlaybackEngine {
    /** Asynchronously prepare [url]. Must not be called twice without an intervening [release]. */
    fun prepare(url: String, callbacks: EngineCallbacks)

    fun start()

    fun pause()

    fun seekTo(positionMs: Int)

    /** Applies an independent left/right volume pair in [0, 1], including before preparation. */
    fun setVolume(leftVolume: Float, rightVolume: Float)

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
