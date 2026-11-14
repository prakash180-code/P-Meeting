package com.prakash.pmeeting.transcription

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Decodes an audio file to 16 kHz mono float PCM using MediaExtractor and
 * MediaCodec. Handles AAC, MP3, WAV, Opus and the audio track of MP4/MKV video.
 *
 * Whisper requires 16 kHz mono input, so stereo tracks are down-mixed and the
 * decoded sample rate is converted with linear interpolation while decoding
 * (to avoid buffering the whole file at its native rate).
 */
class AudioDecoder(@Suppress("UNUSED_PARAMETER") context: Context) {

    /**
     * Decodes [audioFile] to mono float samples at [targetSampleRate] Hz.
     * Runs on the calling thread; callers should use withContext(Dispatchers.IO).
     * Returns an empty array on failure.
     *
     * Synchronized: a cancelled transcription keeps running inside its native
     * block, so a new decode can start while the previous one is still winding
     * down. The decoder reuses a scratch buffer, which must not be shared
     * between concurrent calls.
     */
    @Synchronized
    fun decodeToFloatMono(audioFile: File, targetSampleRate: Int = 16000): FloatArray {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val output = FloatSink()
        try {
            extractor.setDataSource(audioFile.absolutePath)
            val trackIndex = selectAudioTrack(extractor) ?: return FloatArray(0)
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var inputRate = inputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var channels = inputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: LinearResampler? = null

            var sawEos = false
            var decoderDone = false

            while (!decoderDone) {
                if (!sawEos) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        val sampleSize = if (inputBuffer == null) -1 else extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawEos = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder may output a different rate/channel layout
                        // than the container claimed (e.g. HE-AAC upsampling).
                        val outputFormat = decoder.outputFormat
                        inputRate = outputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, inputRate)
                        channels = outputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            .coerceAtLeast(1)
                        pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            outputFormat.getIntOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                        resampler = LinearResampler(inputRate, targetSampleRate)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> if (outputIndex >= 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            // INFO_OUTPUT_FORMAT_CHANGED is always delivered before
                            // the first output buffer, but keep a fallback.
                            val activeResampler = resampler ?: LinearResampler(
                                inputRate,
                                targetSampleRate
                            ).also { resampler = it }
                            feedBuffer(
                                buffer = outputBuffer,
                                info = bufferInfo,
                                channels = channels,
                                pcmEncoding = pcmEncoding,
                                resampler = activeResampler,
                                sink = { output.add(it) }
                            )
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                        }
                    }
                }
            }

            resampler?.flush { output.add(it) }
            return output.toFloatArray()
        } catch (e: Exception) {
            return FloatArray(0)
        } finally {
            runCatching { decoder?.stop() }
            decoder?.release()
            extractor.release()
        }
    }

    /** Down-mixes one decoder output buffer to mono and feeds the resampler. */
    private fun feedBuffer(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        channels: Int,
        pcmEncoding: Int,
        resampler: LinearResampler,
        sink: (Float) -> Unit
    ) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val channelCount = channels.coerceAtLeast(1)

        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val frames = floats.remaining() / channelCount
            ensureScratch(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += floats.get()
                scratch[frame] = sum / channelCount
            }
            resampler.feed(scratch, frames, sink)
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = shorts.remaining() / channelCount
            ensureScratch(frames)
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channelCount) sum += shorts.get() / 32768.0f
                scratch[frame] = sum / channelCount
            }
            resampler.feed(scratch, frames, sink)
        }
    }

    private var scratch = FloatArray(0)

    private fun ensureScratch(size: Int) {
        if (scratch.size < size) scratch = FloatArray(maxOf(size, scratch.size * 2, 4096))
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    private fun MediaFormat.getIntOr(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default

    /** Growable float array used to accumulate the 16 kHz mono output. */
    private class FloatSink {
        private var data = FloatArray(1 shl 16)
        private var size = 0

        fun add(value: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toFloatArray(): FloatArray = data.copyOf(size)
    }
}
