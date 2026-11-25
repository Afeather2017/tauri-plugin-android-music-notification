package com.plugin.music_notification.core

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class ChannelVolumeProcessor : BaseAudioProcessor() {
    private data class Volume(val left: Float, val right: Float)

    // The service writes on its main thread and the renderer reads on its playback thread.
    @Volatile private var volume = Volume(1f, 1f)
    private var stereoMix: ChannelMixingMatrix? = null

    fun setVolume(left: Float, right: Float) {
        volume = Volume(left, right)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        // Media3 provides stereo downmix coefficients for common surround layouts.
        val outputChannels = if (inputAudioFormat.channelCount <= 6) 2 else inputAudioFormat.channelCount
        return AudioFormat(inputAudioFormat.sampleRate, outputChannels, C.ENCODING_PCM_16BIT)
    }

    override fun onFlush() {
        stereoMix = when (inputAudioFormat.channelCount) {
            in 3..6 -> ChannelMixingMatrix.createForConstantPower(inputAudioFormat.channelCount, 2)
            else -> null
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frameCount = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(frameCount * outputAudioFormat.bytesPerFrame)
        inputBuffer.order(ByteOrder.nativeOrder())
        val gain = volume
        val matrix = stereoMix
        val otherGain = (gain.left + gain.right) / 2f
        repeat(frameCount) {
            if (matrix != null) {
                var left = 0f
                var right = 0f
                for (channel in 0 until channels) {
                    val sample = inputBuffer.getShort().toFloat()
                    left += sample * matrix.getMixingCoefficient(channel, 0)
                    right += sample * matrix.getMixingCoefficient(channel, 1)
                }
                output.putShort(scale(left, gain.left))
                output.putShort(scale(right, gain.right))
            } else {
                val left = inputBuffer.getShort().toFloat()
                val right = if (channels == 1) left else inputBuffer.getShort().toFloat()
                output.putShort(scale(left, gain.left))
                output.putShort(scale(right, gain.right))
                // Preserve larger surround layouts and apply the average gain to other speakers.
                for (channel in 2 until channels) {
                    output.putShort(scale(inputBuffer.getShort().toFloat(), otherGain))
                }
            }
        }
        output.flip()
    }

    private fun scale(sample: Float, gain: Float): Short =
        (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}
