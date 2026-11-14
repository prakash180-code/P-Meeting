package com.prakash.pmeeting

import com.prakash.pmeeting.transcription.LinearResampler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinearResamplerTest {

    private fun resample(
        input: FloatArray,
        inputRate: Int,
        outputRate: Int,
        chunkSize: Int = input.size
    ): FloatArray {
        val resampler = LinearResampler(inputRate, outputRate)
        val output = ArrayList<Float>()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(chunkSize, input.size - offset)
            resampler.feed(input.copyOfRange(offset, offset + count), count) { output.add(it) }
            offset += count
        }
        resampler.flush { output.add(it) }
        return output.toFloatArray()
    }

    @Test
    fun sameRate_preservesLengthAndValues() {
        val input = FloatArray(1000) { it / 1000f }
        val output = resample(input, 16_000, 16_000)
        assertEquals(input.size, output.size)
        for (i in input.indices) {
            assertEquals(input[i], output[i], 0.0001f)
        }
    }

    @Test
    fun downsampling_48kTo16k_producesOneThirdOfSamples() {
        val input = FloatArray(48_000) { 0.5f }
        val output = resample(input, 48_000, 16_000)
        assertEquals(16_000, output.size)
    }

    @Test
    fun downsampling_44100To16k_producesExpectedLength() {
        val input = FloatArray(44_100) { 0.25f }
        val output = resample(input, 44_100, 16_000)
        assertEquals(16_000, output.size)
    }

    @Test
    fun constantSignal_staysConstantAfterResampling() {
        val input = FloatArray(48_000) { 0.5f }
        val output = resample(input, 48_000, 16_000)
        assertTrue(output.isNotEmpty())
        output.forEach { assertEquals(0.5f, it, 0.0001f) }
    }

    @Test
    fun chunkedFeeding_matchesSinglePass() {
        val input = FloatArray(10_000) { (it % 100) / 100f }
        val single = resample(input, 44_100, 16_000)
        val chunked = resample(input, 44_100, 16_000, chunkSize = 337)
        assertEquals(single.size, chunked.size)
        for (i in single.indices) {
            assertEquals(single[i], chunked[i], 0.0001f)
        }
    }
}
