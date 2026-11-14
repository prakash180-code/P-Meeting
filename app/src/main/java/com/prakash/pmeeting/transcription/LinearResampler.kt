package com.prakash.pmeeting.transcription

import kotlin.math.floor

/**
 * Streaming linear-interpolation resampler used by [AudioDecoder].
 *
 * Input is fed in arbitrary chunks; output samples are emitted as soon as both
 * neighbours of an output position are available. Pure Kotlin (no Android
 * dependencies) so it can be unit-tested on the JVM.
 */
internal class LinearResampler(
    private val inputRate: Int,
    private val outputRate: Int
) {
    private val step: Double =
        if (inputRate > 0 && outputRate > 0) inputRate.toDouble() / outputRate else 1.0

    /** Position of the next output sample, measured in input samples. */
    private var position = 0.0

    /** Global index of the next input sample that will be fed. */
    private var nextInputIndex = 0L
    private var lastInputIndex = -1L
    private var lastSample: Float? = null

    /** Feeds [count] samples from [samples], emitting any completed output samples. */
    fun feed(samples: FloatArray, count: Int = samples.size, sink: (Float) -> Unit) {
        if (count <= 0) return
        val start = nextInputIndex
        val end = start + count

        while (true) {
            val left = floor(position).toLong()
            val right = left + 1
            if (right >= end) break // need the following sample

            val a: Float
            val b: Float
            if (left < start) {
                // The left neighbour lives in the previous chunk.
                a = lastSample ?: samples[0]
                b = samples[0]
            } else {
                a = samples[(left - start).toInt()]
                b = samples[(right - start).toInt()]
            }
            val fraction = (position - left).toFloat()
            sink(a + (b - a) * fraction)
            position += step
        }

        nextInputIndex = end
        lastInputIndex = end - 1
        lastSample = samples[count - 1]
    }

    /**
     * Emits the final output position(s) that were still waiting for a
     * right-hand neighbour, padding with the last input sample.
     */
    fun flush(sink: (Float) -> Unit) {
        val sample = lastSample ?: return
        // Tolerate floating-point drift so a position that lands exactly on the
        // final input index (e.g. 44100 → 16000) is not emitted twice.
        while (floor(position + 1e-6).toLong() <= lastInputIndex) {
            sink(sample)
            position += step
        }
    }
}
