package com.multicast.walkietalkie

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Runs the whole walkie-talkie channel: joins the LAN multicast group,
 * announces this device's presence, listens for other devices' audio and
 * plays it back, and — while push-to-talk is held — captures the mic,
 * encodes it to AAC, and sends it to the group.
 *
 * There is no server: every instance of this app on the LAN is a peer.
 */
class AudioMulticastService : Service() {

    interface Listener {
        fun onDevicesChanged(devices: List<DeviceInfo>)
    }

    inner class LocalBinder : Binder() {
        fun getService(): AudioMulticastService = this@AudioMulticastService
    }

    private val binder = LocalBinder()
    var listener: Listener? = null

    /** Random per-launch identifier for this device's stream. */
    val ssrc: Int = Random.nextInt(1, Int.MAX_VALUE)

    @Volatile var deviceName: String = Build.MODEL ?: "Device"

    private var socket: MulticastSocket? = null
    private lateinit var group: InetAddress
    private var multicastLock: WifiManager.MulticastLock? = null

    private val devices = ConcurrentHashMap<Int, DeviceInfo>()
    private val remoteStreams = ConcurrentHashMap<Int, RemoteAudioStream>()

    @Volatile private var running = false
    @Volatile private var talking = false
    private var receiveThread: Thread? = null
    private var announceThread: Thread? = null
    private var transmitterThread: Thread? = null

    @Volatile private var playbackVolume: Float = 1f

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupNetwork()
        running = true
        startReceiveLoop()
        startAnnounceLoop()
    }

    override fun onDestroy() {
        running = false
        stopTalking()
        receiveThread?.interrupt()
        announceThread?.interrupt()
        remoteStreams.values.forEach { it.release() }
        remoteStreams.clear()
        try {
            socket?.leaveGroup(InetSocketAddress(group, Protocol.PORT), null)
        } catch (e: Exception) { /* ignore */ }
        socket?.close()
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------------------------------------------------------
    // Setup
    // ---------------------------------------------------------------

    private fun setupNetwork() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("walkietalkie-lock").apply {
            setReferenceCounted(true)
            acquire()
        }
        group = InetAddress.getByName(Protocol.MULTICAST_ADDRESS)
        try {
            val s = MulticastSocket(Protocol.PORT)
            s.reuseAddress = true
            s.joinGroup(InetSocketAddress(group, Protocol.PORT), findWifiInterface())
            socket = s
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open multicast socket", e)
        }
    }

    /** Best-effort: bind the join to the Wi-Fi interface so it doesn't try to go over cellular. */
    private fun findWifiInterface(): NetworkInterface? {
        return try {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(active) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val linkProps = cm.getLinkProperties(active) ?: return null
            val name = linkProps.interfaceName ?: return null
            NetworkInterface.getByName(name)
        } catch (e: Exception) {
            null
        }
    }

    private fun startForegroundNotification() {
        val channelId = "walkietalkie_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    // ---------------------------------------------------------------
    // Receive
    // ---------------------------------------------------------------

    private fun startReceiveLoop() {
        receiveThread = thread(name = "wt-receive") {
            val buf = ByteArray(Protocol.MAX_PACKET_SIZE)
            val packet = DatagramPacket(buf, buf.size)
            while (running) {
                try {
                    val s = socket ?: break
                    s.receive(packet)
                    handleIncoming(buf, packet.length)
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "receive loop error: ${e.message}")
                }
            }
        }
    }

    private fun handleIncoming(buf: ByteArray, length: Int) {
        if (length < 5) return
        val bb = ByteBuffer.wrap(buf, 0, length)
        val type = bb.get()
        val senderSsrc = bb.int
        if (senderSsrc == ssrc) return // our own packet looped back
        val now = System.currentTimeMillis()

        when (type) {
            Protocol.TYPE_ANNOUNCE -> {
                if (bb.remaining() < 1) return
                val nameLen = bb.get().toInt() and 0xFF
                if (bb.remaining() < nameLen) return
                val nameBytes = ByteArray(nameLen)
                bb.get(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                val existing = devices[senderSsrc]
                if (existing != null) {
                    existing.name = name
                    existing.lastSeenMs = now
                } else {
                    devices[senderSsrc] = DeviceInfo(senderSsrc, name, now)
                }
                notifyDevicesChanged()
            }

            Protocol.TYPE_SETUP -> {
                val csd = ByteArray(bb.remaining())
                bb.get(csd)
                val stream = remoteStreams.getOrPut(senderSsrc) {
                    RemoteAudioStream(senderSsrc).also { it.setVolume(playbackVolume) }
                }
                stream.onSetup(csd)
                touchDevicePlaceholder(senderSsrc, now)
            }

            Protocol.TYPE_AUDIO -> {
                if (bb.remaining() < 6) return
                bb.short // seq — reserved for future loss detection, unused for now
                bb.int   // timestamp — reserved for future jitter handling, unused for now
                val payload = ByteArray(bb.remaining())
                bb.get(payload)
                remoteStreams[senderSsrc]?.onAudioPacket(payload)
                touchDevicePlaceholder(senderSsrc, now, isAudio = true)
                notifyDevicesChanged()
            }
        }
    }

    private fun touchDevicePlaceholder(senderSsrc: Int, now: Long, isAudio: Boolean = false) {
        val info = devices.getOrPut(senderSsrc) { DeviceInfo(senderSsrc, "Device", now) }
        info.lastSeenMs = now
        if (isAudio) info.lastAudioMs = now
    }

    // ---------------------------------------------------------------
    // Announce / prune
    // ---------------------------------------------------------------

    private fun startAnnounceLoop() {
        announceThread = thread(name = "wt-announce") {
            while (running) {
                sendAnnounce()
                pruneStaleDevices()
                notifyDevicesChanged() // also refreshes "speaking" timeout in the UI
                try {
                    Thread.sleep(Protocol.ANNOUNCE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    private fun sendAnnounce() {
        sendPacket(Protocol.buildAnnouncePacket(ssrc, deviceName))
    }

    private fun pruneStaleDevices() {
        val now = System.currentTimeMillis()
        val it = devices.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (!e.value.isOnline(now)) {
                it.remove()
                remoteStreams.remove(e.key)?.release()
            }
        }
    }

    private fun notifyDevicesChanged() {
        listener?.onDevicesChanged(devices.values.sortedBy { it.name.lowercase() })
    }

    fun currentDevices(): List<DeviceInfo> = devices.values.sortedBy { it.name.lowercase() }

    // ---------------------------------------------------------------
    // Push to talk
    // ---------------------------------------------------------------

    fun startTalking() {
        if (talking) return
        talking = true
        transmitterThread = thread(name = "wt-transmit") { runTransmitter() }
    }

    fun stopTalking() {
        if (!talking) return
        talking = false
        transmitterThread?.join(500)
        transmitterThread = null
    }

    fun isTalking() = talking

    private fun runTransmitter() {
        var audioRecord: AudioRecord? = null
        var encoder: MediaCodec? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                Protocol.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                Protocol.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 4096) * 2
            )

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, Protocol.SAMPLE_RATE, Protocol.CHANNEL_COUNT
            )
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_BIT_RATE, Protocol.AAC_BITRATE)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            audioRecord.startRecording()

            val startTime = System.currentTimeMillis()
            var seq = 0
            var lastSetupSent = 0L
            var lastCsd: ByteArray? = null
            val pcmBuf = ShortArray(1024)
            val bufferInfo = MediaCodec.BufferInfo()
            val leBuf = ByteBuffer.allocate(pcmBuf.size * 2).order(ByteOrder.LITTLE_ENDIAN)

            while (talking) {
                val samplesRead = audioRecord.read(pcmBuf, 0, pcmBuf.size)
                if (samplesRead <= 0) continue

                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    leBuf.clear()
                    for (i in 0 until samplesRead) leBuf.putShort(pcmBuf[i])
                    leBuf.flip()
                    val inputBuffer = encoder.getInputBuffer(inIndex)
                    if (inputBuffer != null) {
                        inputBuffer.clear()
                        inputBuffer.put(leBuf)
                        encoder.queueInputBuffer(inIndex, 0, samplesRead * 2, System.nanoTime() / 1000, 0)
                    }
                }

                drainLoop@ while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outIndex >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIndex)
                            if (outBuf != null && bufferInfo.size > 0) {
                                val chunk = ByteArray(bufferInfo.size)
                                outBuf.get(chunk)
                                seq = (seq + 1) and 0xFFFF
                                val ts = (System.currentTimeMillis() - startTime).toInt()
                                sendPacket(Protocol.buildAudioPacket(ssrc, seq, ts, chunk, chunk.size))
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                        }
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val csd0 = encoder.outputFormat.getByteBuffer("csd-0")
                            if (csd0 != null) {
                                val csd = ByteArray(csd0.remaining())
                                csd0.get(csd)
                                lastCsd = csd
                                sendPacket(Protocol.buildSetupPacket(ssrc, csd))
                                lastSetupSent = System.currentTimeMillis()
                            }
                        }
                        else -> break@drainLoop
                    }
                }

                val now = System.currentTimeMillis()
                val csdSnapshot = lastCsd
                if (csdSnapshot != null && now - lastSetupSent > Protocol.SETUP_RESEND_MS) {
                    sendPacket(Protocol.buildSetupPacket(ssrc, csdSnapshot))
                    lastSetupSent = now
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "transmitter error", e)
        } finally {
            try { audioRecord?.stop() } catch (e: Exception) { /* ignore */ }
            try { audioRecord?.release() } catch (e: Exception) { /* ignore */ }
            try { encoder?.stop() } catch (e: Exception) { /* ignore */ }
            try { encoder?.release() } catch (e: Exception) { /* ignore */ }
        }
    }

    // ---------------------------------------------------------------
    // Volume
    // ---------------------------------------------------------------

    fun setPlaybackVolume(v: Float) {
        playbackVolume = v.coerceIn(0f, 1f)
        remoteStreams.values.forEach { it.setVolume(playbackVolume) }
    }

    // ---------------------------------------------------------------
    // Send helper
    // ---------------------------------------------------------------

    private fun sendPacket(bytes: ByteArray) {
        val s = socket ?: return
        try {
            s.send(DatagramPacket(bytes, bytes.size, group, Protocol.PORT))
        } catch (e: Exception) {
            Log.w(TAG, "send failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "AudioMulticastSvc"
    }
}
