package com.example.meshlink.network.webrtc

/**
 * Метрики качества WebRTC-звонка.
 * ЕДИНОЕ определение для всего проекта.
 */
data class WebRtcMetrics(
    val rttMs: Long = -1L,
    val lossRatePercent: Float = 0f,
    val jitterMs: Long = 0L,
    val jitterBufferSizeMs: Int = 0,
    val availableBitrateBps: Long = 0L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val framesPerSecond: Float = 0f
) {
    val quality: CallQualityLevel get() = when {
        rttMs in 0..150 && lossRatePercent < 2f && jitterMs < 50 -> CallQualityLevel.GOOD
        (rttMs < 0 || rttMs < 300) && lossRatePercent < 5f       -> CallQualityLevel.FAIR
        else                                                       -> CallQualityLevel.POOR
    }

    val videoResolution: String get() = if (frameWidth > 0) "${frameWidth}×${frameHeight}" else ""
}

enum class CallQualityLevel {
    GOOD,
    FAIR,
    POOR
}