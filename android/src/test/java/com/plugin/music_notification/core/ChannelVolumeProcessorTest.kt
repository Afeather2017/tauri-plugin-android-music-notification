package com.plugin.music_notification.core

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ChannelVolumeProcessorTest {
    @Test
    fun stereoGainChangesMuteOnlyTheSelectedChannel() {
        val processor = ChannelVolumeProcessor()
        processor.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()

        processor.setVolume(0.5f, 0f)
        assertArrayEquals(shortArrayOf(500, 0, 0, 0), process(processor, 1000, 0, 0, 2000))

        processor.setVolume(0f, 1f)
        assertArrayEquals(shortArrayOf(0, 0, 0, 2000), process(processor, 1000, 0, 0, 2000))
    }

    @Test
    fun monoIsExpandedBeforeApplyingBalance() {
        val processor = ChannelVolumeProcessor()
        processor.setVolume(0f, 0.5f)
        val output = processor.configure(AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        processor.flush()

        assertEquals(2, output.channelCount)
        assertArrayEquals(shortArrayOf(0, -1000, 0, 500), process(processor, -2000, 1000))
    }

    private fun process(processor: ChannelVolumeProcessor, vararg samples: Short): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
        samples.forEach { input.putShort(it) }
        input.flip()
        processor.queueInput(input)
        val output = processor.getOutput()
        return ShortArray(output.remaining() / Short.SIZE_BYTES) { output.getShort() }
    }
}
