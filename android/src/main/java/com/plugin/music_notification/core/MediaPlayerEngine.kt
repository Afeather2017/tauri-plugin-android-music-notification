package com.plugin.music_notification.core

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.util.Log

/**
 * [PlaybackEngine] backed by [android.media.MediaPlayer].
 *
 * Ownership rules that keep double playback impossible:
 * - the wrapped player is created only inside [prepare] and torn down only
 *   by [release] (or by [prepare] itself when construction fails);
 * - listeners are detached before any stop/release so a dying player can
 *   never fire callbacks into the core.
 */
class MediaPlayerEngine(
    private val context: Context
) : PlaybackEngine {

    private var player: MediaPlayer? = null
    private var callbacks: EngineCallbacks? = null

    override var isPrepared: Boolean = false
        private set

    override var isInErrorState: Boolean = false
        private set

    override var sourceUrl: String? = null
        private set

    override fun prepare(url: String, callbacks: EngineCallbacks) {
        release()
        sourceUrl = url
        this.callbacks = callbacks
        isInErrorState = false
        isPrepared = false

        val mp = MediaPlayer()
        player = mp
        try {
            val sourceUri = Uri.parse(url)
            when (sourceUri.scheme?.lowercase()) {
                "content", "file" -> mp.setDataSource(context, sourceUri)
                else -> mp.setDataSource(url)
            }

            mp.setOnPreparedListener {
                isPrepared = true
                callbacks.onPrepared()
            }

            mp.setOnErrorListener { _, what, extra ->
                isPrepared = false
                isInErrorState = true
                callbacks.onError(what, extra)
                true
            }

            mp.setOnCompletionListener {
                callbacks.onCompletion()
            }

            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare MediaPlayer for $url", e)
            // A half-built player must not linger holding audio resources.
            release()
            isInErrorState = true
            callbacks.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        }
    }

    override fun start() {
        player?.start()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Int) {
        player?.seekTo(positionMs)
    }

    override fun setVolume(volume: Float) {
        player?.setVolume(volume, volume)
    }

    override fun release() {
        player?.let { mp ->
            try {
                mp.setOnPreparedListener(null)
                mp.setOnErrorListener(null)
                mp.setOnCompletionListener(null)
                if (mp.isPlaying) {
                    mp.stop()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping MediaPlayer", e)
            }
            try {
                mp.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing MediaPlayer", e)
            }
        }
        player = null
        isPrepared = false
    }

    override val isPlaying: Boolean
        get() = player?.isPlaying == true

    override val currentPositionMs: Int
        get() = try {
            player?.currentPosition ?: 0
        } catch (e: IllegalStateException) {
            0
        }

    override val durationMs: Int
        get() = if (!isPrepared) 0 else try {
            player?.duration ?: 0
        } catch (e: IllegalStateException) {
            0
        }

    companion object {
        private const val TAG = "MediaPlayerEngine"
    }
}
