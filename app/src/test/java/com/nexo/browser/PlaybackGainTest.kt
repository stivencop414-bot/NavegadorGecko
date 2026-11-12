package com.nexo.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackGainTest {
    private fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { bytes ->
        samples.forEachIndexed { i, v -> bytes[i * 2] = (v and 255).toByte(); bytes[i * 2 + 1] = ((v shr 8) and 255).toByte() }
    }

    @Test fun quietSpeechIsRaisedGraduallyWithoutClipping() {
        val gain = PlaybackPcm.Gain()
        val block = pcm(1000, -1000, 500)
        gain.apply(block)
        // Sube como mucho un 15 % por bloque.
        assertEquals(1150.0, PlaybackPcm.peak(block).toDouble(), 2.0)
        repeat(30) { gain.apply(pcm(1000, -1000)) }
        assertEquals(8f, gain.gain, 0.01f)
        val loud = pcm(20000, -20000)
        gain.apply(loud)
        assertTrue("una voz fuerte baja la ganancia al momento", gain.gain <= 1f)
        assertTrue(PlaybackPcm.peak(loud) <= Short.MAX_VALUE)
    }

    @Test fun backgroundNoiseIsNotAmplified() {
        val gain = PlaybackPcm.Gain()
        val noise = pcm(50, -60, 40)
        gain.apply(noise)
        assertEquals(60, PlaybackPcm.peak(noise))
        assertEquals(1f, gain.gain, 0f)
    }
}
