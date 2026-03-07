// Удалить data class WebRtcMetrics и enum CallQualityLevel из этого файла
// Они теперь в VideoCallManager.kt

// Оставить только класс WebRtcSession:

package com.example.meshlink.network.webrtc

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.RTCStatsReport
import java.util.concurrent.CopyOnWriteArrayList

class WebRtcSession(
    val peerId: String,
    val isInitiator: Boolean
) {
    companion object {
        private const val TAG = "WebRtcSession"
        private const val METRICS_INTERVAL_MS = 2000L
    }

    lateinit var peerConnection: PeerConnection
    val pendingCandidates = CopyOnWriteArrayList<IceCandidate>()
    var metricsCallback: ((WebRtcMetrics) -> Unit)? = null

    private val metricsHandler = Handler(Looper.getMainLooper())
    private var metricsRunnable: Runnable? = null

    private var prevPacketsSent = 0L
    private var prevPacketsLost = 0L
    private var prevBytesSent = 0L
    private var prevTimestampUs = 0L

    fun flushPendingCandidates() {
        val candidates = pendingCandidates.toList()
        pendingCandidates.clear()
        candidates.forEach { candidate ->
            peerConnection.addIceCandidate(candidate)
            Log.v(TAG, "Flushed ICE candidate for ${peerId.take(8)}: ${candidate.sdp.take(60)}")
        }
        Log.d(TAG, "Flushed ${candidates.size} pending ICE candidates for ${peerId.take(8)}")
    }

    fun startMetricsCollection() {
        Log.d(TAG, "Starting metrics collection for ${peerId.take(8)}")
        val runnable = object : Runnable {
            override fun run() {
                if (!::peerConnection.isInitialized) return
                peerConnection.getStats { report ->
                    processStats(report)
                }
                metricsHandler.postDelayed(this, METRICS_INTERVAL_MS)
            }
        }
        metricsRunnable = runnable
        metricsHandler.postDelayed(runnable, METRICS_INTERVAL_MS)
    }

    private fun processStats(report: RTCStatsReport) {
        var rttMs = -1L
        var packetsLostDelta = 0L
        var packetsSentDelta = 0L
        var jitterMs = 0L
        var jitterBufferSize = 0
        var frameWidth = 0
        var frameHeight = 0
        var framesPerSecond = 0f
        var availableBitrateBps = 0L

        for ((_, stats) in report.statsMap) {
            when (stats.type) {
                "outbound-rtp" -> {
                    val members = stats.members
                    val rttSec = (members["roundTripTime"] as? Double) ?: -1.0
                    if (rttSec >= 0) rttMs = (rttSec * 1000).toLong()

                    val packetsSent = (members["packetsSent"] as? Long) ?: 0L
                    val bytesSent   = (members["bytesSent"] as? Long)   ?: 0L
                    val timestamp   = stats.timestampUs.toLong()

                    if (prevTimestampUs > 0 && timestamp > prevTimestampUs) {
                        packetsSentDelta = packetsSent - prevPacketsSent
                        val dtSec = (timestamp - prevTimestampUs) / 1_000_000.0
                        if (dtSec > 0) {
                            availableBitrateBps = ((bytesSent - prevBytesSent) * 8 / dtSec).toLong()
                        }
                    }
                    prevPacketsSent = packetsSent
                    prevBytesSent = bytesSent
                    prevTimestampUs = timestamp

                    frameWidth  = (members["frameWidth"]  as? Long)?.toInt() ?: frameWidth
                    frameHeight = (members["frameHeight"] as? Long)?.toInt() ?: frameHeight
                    framesPerSecond = (members["framesPerSecond"] as? Double)?.toFloat() ?: framesPerSecond
                }
                "remote-inbound-rtp" -> {
                    val members = stats.members
                    val rttSec = (members["roundTripTime"] as? Double) ?: -1.0
                    if (rttSec >= 0) rttMs = (rttSec * 1000).toLong()

                    val packetsLost = (members["packetsLost"] as? Long) ?: 0L
                    packetsLostDelta = packetsLost - prevPacketsLost
                    prevPacketsLost = packetsLost

                    val jitterSec = (members["jitter"] as? Double) ?: 0.0
                    jitterMs = (jitterSec * 1000).toLong()
                }
                "inbound-rtp" -> {
                    val members = stats.members
                    val jitterBuf = (members["jitterBufferDelay"] as? Double)
                    val emittedCount = (members["jitterBufferEmittedCount"] as? Long) ?: 0L
                    if (jitterBuf != null && emittedCount > 0) {
                        jitterBufferSize = ((jitterBuf / emittedCount) * 1000).toInt()
                    }
                }
                "candidate-pair" -> {
                    val members = stats.members
                    val state = members["state"] as? String
                    if (state == "succeeded") {
                        val rttSec = (members["currentRoundTripTime"] as? Double) ?: -1.0
                        if (rttSec >= 0 && rttMs < 0) rttMs = (rttSec * 1000).toLong()
                        val bitrate = (members["availableOutgoingBitrate"] as? Double)?.toLong() ?: 0L
                        if (bitrate > 0) availableBitrateBps = bitrate
                    }
                }
            }
        }

        val lossRate = if (packetsSentDelta > 0 && packetsLostDelta >= 0) {
            (packetsLostDelta.toFloat() / (packetsSentDelta + packetsLostDelta) * 100f).coerceIn(0f, 100f)
        } else 0f

        val metrics = WebRtcMetrics(
            rttMs              = rttMs,
            lossRatePercent    = lossRate,
            jitterMs           = jitterMs,
            jitterBufferSizeMs = jitterBufferSize,
            availableBitrateBps = availableBitrateBps,
            frameWidth         = frameWidth,
            frameHeight        = frameHeight,
            framesPerSecond    = framesPerSecond
        )

        Log.v(TAG, "Metrics ${peerId.take(8)}: RTT=${rttMs}ms loss=${String.format("%.1f", lossRate)}% jitter=${jitterMs}ms fps=${framesPerSecond}")
        metricsCallback?.invoke(metrics)
    }

    fun dispose() {
        Log.d(TAG, "Disposing session for ${peerId.take(8)}")
        metricsRunnable?.let { metricsHandler.removeCallbacks(it) }
        metricsRunnable = null
        metricsCallback = null
        pendingCandidates.clear()
        if (::peerConnection.isInitialized) {
            peerConnection.close()
            peerConnection.dispose()
        }
    }
}