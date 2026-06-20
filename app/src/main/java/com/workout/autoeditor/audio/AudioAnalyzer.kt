package com.workout.autoeditor.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.workout.autoeditor.data.AudioEnvelope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Decodes the audio track of a video file and computes an RMS envelope at
 * `windowMs` resolution (default 100 ms).
 *
 * The envelope is used by TimelineBuilder as a corroborating signal for set
 * boundaries (silence vs activity).
 */
class AudioAnalyzer(private val ctx: Context) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val codecDispatcher = Dispatchers.IO.limitedParallelism(1)

    suspend fun analyze(
        videoUri: Uri,
        windowMs: Int = 100,
    ): AudioEnvelope = withContext(codecDispatcher) {
        // MediaCodec in synchronous mode requires all dequeue/queue/release
        // calls to happen on the same thread. limitedParallelism(1) gives us
        // a single-threaded dispatcher confined to one IO worker.
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, videoUri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex == -1 || format == null) {
            extractor.release()
            return@withContext AudioEnvelope(0, windowMs, emptyList())
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val rmsList = ArrayList<Float>()
        val samplesPerWindow = (sampleRate.toLong() * windowMs / 1000L).toInt() * channelCount
        var pcmBuf = ShortArray(samplesPerWindow * 4)
        var pcmFill = 0

        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val needed = sb.remaining()
                        if (pcmFill + needed > pcmBuf.size) {
                            pcmBuf = pcmBuf.copyOf((pcmFill + needed) * 2)
                        }
                        sb.get(pcmBuf, pcmFill, needed)
                        pcmFill += needed
                    }
                    codec.releaseOutputBuffer(outIdx, false)

                    while (pcmFill >= samplesPerWindow) {
                        val rms = computeRms(pcmBuf, samplesPerWindow)
                        rmsList += rms
                        System.arraycopy(pcmBuf, samplesPerWindow, pcmBuf, 0, pcmFill - samplesPerWindow)
                        pcmFill -= samplesPerWindow
                    }

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
            if (pcmFill > 0) {
                rmsList += computeRms(pcmBuf, pcmFill)
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            try { codec.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }
        AudioEnvelope(sampleRate, windowMs, rmsList)
    }

    private fun computeRms(buf: ShortArray, len: Int): Float {
        if (len == 0) return 0f
        var sumSq = 0.0
        for (i in 0 until len) {
            val v = buf[i].toDouble() / Short.MAX_VALUE
            sumSq += v * v
        }
        return sqrt(sumSq / len).toFloat()
    }
}
