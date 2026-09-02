package com.example.whispersubtitles

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {
    private const val TARGET_SAMPLE_RATE = 16000

    fun decodeTo16kHzFloatPcm(context: Context, uri: Uri): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = f
                break
            }
        }
        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("Audio track missing")
        }

        extractor.selectTrack(audioTrackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("MIME type missing")
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val pcmList = mutableListOf<Float>()
        val info = MediaCodec.BufferInfo()
        var isEOS = false

        while (!isEOS) {
            val inIndex = codec.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex)
                if (buf != null) {
                    val sampleSize = extractor.readSampleData(buf, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            var outIndex = codec.dequeueOutputBuffer(info, 10000)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && info.size > 0) {
                    val chunk = ByteArray(info.size)
                    outBuf.get(chunk)
                    outBuf.clear()

                    val shorts = ShortArray(chunk.size / 2)
                    ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)

                    val monoCount = shorts.size / channels
                    for (i in 0 until monoCount) {
                        var sum = 0f
                        for (c in 0 until channels) {
                            sum += shorts[i * channels + c] / 32768.0f
                        }
                        pcmList.add(sum / channels)
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(info, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        val rawData = pcmList.toFloatArray()
        return if (sampleRate != TARGET_SAMPLE_RATE) {
            resampleLinear(rawData, sampleRate, TARGET_SAMPLE_RATE)
        } else {
            rawData
        }
    }

    private fun resampleLinear(input: FloatArray, srcRate: Int, dstRate: Int): FloatArray {
        val ratio = srcRate.toDouble() / dstRate.toDouble()
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val srcIndex = i * ratio
            val index = srcIndex.toInt()
            val frac = (srcIndex - index).toFloat()
            val nextIndex = if (index + 1 < input.size) index + 1 else index
            out[i] = input[index] * (1.0f - frac) + input[nextIndex] * frac
        }
        return out
    }
}
