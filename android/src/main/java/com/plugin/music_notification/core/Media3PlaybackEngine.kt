package com.plugin.music_notification.core

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class Media3PlaybackEngine(private val context: Context) : PlaybackEngine {
    private val channelVolume = ChannelVolumeProcessor()
    private var player: ExoPlayer? = null
    private var listener: Player.Listener? = null

    override var isPrepared = false
        private set
    override var isInErrorState = false
        private set
    override var sourceUrl: String? = null
        private set

    override fun prepare(url: String, callbacks: EngineCallbacks) {
        release()
        sourceUrl = url
        isInErrorState = false
        try {
            val renderers = object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(
                    context: Context,
                    enableFloatOutput: Boolean,
                    enableAudioTrackPlaybackParams: Boolean
                ): AudioSink {
                    val sink = DefaultAudioSink.Builder(context)
                        .setAudioProcessors(arrayOf(channelVolume))
                        .setEnableFloatOutput(false)
                        .setAudioOffloadSupportProvider { _, _ ->
                            androidx.media3.exoplayer.audio.AudioOffloadSupport.DEFAULT_UNSUPPORTED
                        }
                        .build()
                    // Encoded passthrough would bypass the PCM processor.
                    return object : ForwardingAudioSink(sink) {
                        override fun supportsFormat(format: Format): Boolean =
                            getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

                        override fun getFormatSupport(format: Format): Int =
                            if (format.sampleMimeType == MimeTypes.AUDIO_RAW) {
                                super.getFormatSupport(format)
                            } else {
                                AudioSink.SINK_FORMAT_UNSUPPORTED
                            }
                    }
                }
            }
            val exoPlayer = ExoPlayer.Builder(context, renderers).build()
            player = exoPlayer
            val playbackListener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY && !isPrepared) {
                        isPrepared = true
                        callbacks.onPrepared()
                    } else if (state == Player.STATE_ENDED) {
                        callbacks.onCompletion()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "Playback failed", error)
                    isPrepared = false
                    isInErrorState = true
                    callbacks.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, error.errorCode)
                }
            }
            listener = playbackListener
            exoPlayer.addListener(playbackListener)
            exoPlayer.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
            exoPlayer.setMediaItem(MediaItem.fromUri(url))
            exoPlayer.prepare()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare player", e)
            release()
            isInErrorState = true
            callbacks.onError(MediaPlayer.MEDIA_ERROR_UNKNOWN, MediaPlayer.MEDIA_ERROR_IO)
        }
    }

    override fun start() {
        player?.play()
    }

    override fun pause() {
        player?.pause()
    }

    override fun seekTo(positionMs: Int) {
        player?.seekTo(positionMs.toLong())
    }

    override fun setVolume(leftVolume: Float, rightVolume: Float) {
        channelVolume.setVolume(leftVolume, rightVolume)
    }

    override fun release() {
        player?.let { exoPlayer ->
            listener?.let(exoPlayer::removeListener)
            exoPlayer.release()
        }
        listener = null
        player = null
        isPrepared = false
        sourceUrl = null
    }

    override val isPlaying: Boolean
        get() = player?.isPlaying == true

    override val currentPositionMs: Int
        get() = player?.currentPosition?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0

    override val durationMs: Int
        get() = player?.duration?.takeUnless { it == C.TIME_UNSET }
            ?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: 0

    companion object {
        private const val TAG = "Media3PlaybackEngine"
    }
}
