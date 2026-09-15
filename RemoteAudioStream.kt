package com.multicast.walkietalkie

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/**
 * Decodes and plays back the AAC stream coming from one specific remote
 * device (identified by its SSRC). Created lazily the first time a SETUP
 * or AUDIO packet is seen from a given sender, and torn down when that
 * device goes offline.
 */
class RemoteAudioStream(private val ssrc: Int) {

    private var decoder: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var configuredCsd: ByteArray? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile private var volume: Float = 1f

    @Synchronized
    fun onSetup(csd: ByteArray) {
        if (configuredCsd != null && configuredCsd.contentEquals(csd)) return
        releaseInternal()
        try {
            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, Protocol.SAMPLE_RATE, Protocol.CHANNEL_COUNT
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, 0)
            codec.start()
            decoder = codec

            val minBuf = AudioTrack.getMinBufferSize(
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Protocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.setVolume(volume)
            track.play()
            audioTrack = track
            configuredCsd = csd
        } catch (e: Exception) {
            releaseInternal()
        }
    }

    @Synchronized
    fun onAudioPacket(payload: ByteArray) {
        val codec = decoder ?: return // no SETUP yet for this sender; drop until it arrives
        val track = audioTrack ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(0)
            if (inIndex >= 0) {
                val inBuf = codec.getInputBuffer(inIndex) ?: return
                inBuf.clear()
                inBuf.put(payload)
                codec.queueInputBuffer(inIndex, 0, payload.size, 0, 0)
            }
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            while (outIndex >= 0) {
                val outBuf = codec.getOutputBuffer(outIndex)
                if (outBuf != null && bufferInfo.size > 0) {
                    val pcm = ByteArray(bufferInfo.size)
                    outBuf.get(pcm)
                    track.write(pcm, 0, pcm.size)
                }
                codec.releaseOutputBuffer(outIndex, false)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (e: Exception) {
            // Drop malformed/late frames silently; the next SETUP re-syncs us.
        }
    }

    @Synchronized
    fun setVolume(v: Float) {
        volume = v
        audioTrack?.setVolume(v)
    }

    @Synchronized
    fun release() {
        releaseInternal()
    }

    private fun releaseInternal() {
        try { decoder?.stop() } catch (e: Exception) { /* ignore */ }
        try { decoder?.release() } catch (e: Exception) { /* ignore */ }
        decoder = null
        try { audioTrack?.stop() } catch (e: Exception) { /* ignore */ }
        try { audioTrack?.release() } catch (e: Exception) { /* ignore */ }
        audioTrack = null
        configuredCsd = null
    }
}
