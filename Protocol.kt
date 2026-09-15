package com.multicast.walkietalkie

import java.nio.ByteBuffer

/**
 * Wire format for the walkie-talkie channel.
 *
 * All devices join the same multicast group/port and exchange three kinds
 * of small UDP packets. There is no central server: every device is both
 * a potential sender and a potential receiver, and devices discover each
 * other purely by listening to the group.
 *
 * Packet layouts (all multi-byte fields big-endian):
 *
 *  ANNOUNCE  [type=2][ssrc:4][nameLen:1][nameBytes]
 *      Sent periodically by every device so others can show it in the
 *      device list, independent of whether it is currently talking.
 *
 *  SETUP     [type=3][ssrc:4][csd bytes...]
 *      Sent by a device right before/while it talks. Carries the AAC
 *      decoder configuration (codec-specific data) so any listener can
 *      configure a decoder for this sender's stream, including devices
 *      that join mid-transmission. Re-sent periodically while talking
 *      since UDP is not reliable.
 *
 *  AUDIO     [type=1][ssrc:4][seq:2][timestamp:4][aac frame bytes...]
 *      One AAC-encoded audio frame from a talking device.
 */
object Protocol {

    const val MULTICAST_ADDRESS = "239.48.48.1"
    const val PORT = 5004

    const val SAMPLE_RATE = 16000
    const val CHANNEL_COUNT = 1
    const val AAC_BITRATE = 32000

    const val ANNOUNCE_INTERVAL_MS = 2000L
    const val SETUP_RESEND_MS = 1000L
    const val DEVICE_TIMEOUT_MS = 6000L
    const val SPEAKING_TIMEOUT_MS = 700L

    const val TYPE_AUDIO: Byte = 1
    const val TYPE_ANNOUNCE: Byte = 2
    const val TYPE_SETUP: Byte = 3

    const val AUDIO_HEADER_SIZE = 11 // type(1) + ssrc(4) + seq(2) + timestamp(4)
    const val MAX_PACKET_SIZE = 1400
    const val MAX_NAME_BYTES = 32

    fun buildAudioPacket(ssrc: Int, seq: Int, timestampMs: Int, payload: ByteArray, payloadLen: Int): ByteArray {
        val buf = ByteBuffer.allocate(AUDIO_HEADER_SIZE + payloadLen)
        buf.put(TYPE_AUDIO)
        buf.putInt(ssrc)
        buf.putShort(seq.toShort())
        buf.putInt(timestampMs)
        buf.put(payload, 0, payloadLen)
        return buf.array()
    }

    fun buildSetupPacket(ssrc: Int, csd: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(5 + csd.size)
        buf.put(TYPE_SETUP)
        buf.putInt(ssrc)
        buf.put(csd)
        return buf.array()
    }

    fun buildAnnouncePacket(ssrc: Int, name: String): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).copyOf(
            minOf(name.toByteArray(Charsets.UTF_8).size, MAX_NAME_BYTES)
        )
        val buf = ByteBuffer.allocate(6 + nameBytes.size)
        buf.put(TYPE_ANNOUNCE)
        buf.putInt(ssrc)
        buf.put(nameBytes.size.toByte())
        buf.put(nameBytes)
        return buf.array()
    }
}
