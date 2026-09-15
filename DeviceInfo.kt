package com.multicast.walkietalkie

/**
 * Represents another device seen on the multicast channel.
 * [ssrc] is a randomly generated per-launch identifier for that device
 * (not a stable hardware ID) that is unique enough to disambiguate
 * simultaneous speakers.
 */
data class DeviceInfo(
    val ssrc: Int,
    var name: String,
    var lastSeenMs: Long,
    var lastAudioMs: Long = 0L
) {
    fun isSpeaking(nowMs: Long) = nowMs - lastAudioMs < Protocol.SPEAKING_TIMEOUT_MS
    fun isOnline(nowMs: Long) = nowMs - lastSeenMs < Protocol.DEVICE_TIMEOUT_MS
}
