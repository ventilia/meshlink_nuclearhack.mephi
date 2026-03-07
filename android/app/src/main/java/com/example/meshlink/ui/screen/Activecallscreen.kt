package com.example.meshlink.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshlink.network.CallMetrics
import com.example.meshlink.network.CallQuality
import com.example.meshlink.ui.theme.*

@Composable
fun ActiveCallScreen(
    peerName: String,
    metrics: CallMetrics,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    callDurationSeconds: Long,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "active_call")

    val waveScale1 by infiniteTransition.animateFloat(
        1f, 1.3f,
        infiniteRepeatable(tween(1400, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "w1"
    )
    val waveScale2 by infiniteTransition.animateFloat(
        1f, 1.6f,
        infiniteRepeatable(tween(1800, delayMillis = 200, easing = EaseInOutCubic), RepeatMode.Reverse),
        label = "w2"
    )
    val waveAlpha1 by infiniteTransition.animateFloat(
        0.35f, 0f,
        infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "wa1"
    )
    val waveAlpha2 by infiniteTransition.animateFloat(
        0.2f, 0f,
        infiniteRepeatable(tween(1800, delayMillis = 200), RepeatMode.Reverse),
        label = "wa2"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF060A10), Color(0xFF020408)))),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
        ) {
            Spacer(Modifier.height(56.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(8.dp)
                        .alpha(animateFloat(infiniteTransition, 0.5f, 1f, 800))
                        .background(PixelAccent, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ЗВОНОК АКТИВЕН",
                    color = PixelAccent,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                formatCallDuration(callDurationSeconds),
                color = Color.White.copy(0.5f),
                fontSize = 14.sp
            )

            Spacer(Modifier.weight(1f))

            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(160.dp).scale(waveScale2).alpha(waveAlpha2)
                        .background(PixelAccent.copy(0.15f), CircleShape)
                )
                Box(
                    Modifier.size(130.dp).scale(waveScale1).alpha(waveAlpha1)
                        .background(PixelAccent.copy(0.25f), CircleShape)
                )
                Box(
                    Modifier.size(96.dp).clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF1A3A2A), Color(0xFF0D1A10)))
                        )
                        .border(2.dp, PixelAccent.copy(0.7f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        peerName.take(1).uppercase(),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = PixelAccent
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(peerName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("зашифрованный mesh-звонок", color = Color.White.copy(0.4f), fontSize = 11.sp)
            Spacer(Modifier.height(24.dp))

            // Метрики качества
            CallQualityCard(metrics)

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                CallActionButton(
                    icon = if (isMuted) MeshIcons.MicOff else MeshIcons.Microphone,
                    label = if (isMuted) "АНМУТ" else "МУТ",
                    isActive = isMuted,
                    activeColor = PixelWarn,
                    onClick = onToggleMute
                )

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(PixelWarn)
                        .clickable { onEndCall() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        MeshIcons.CallEnd, "Завершить",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                CallActionButton(
                    icon = if (isSpeakerOn) MeshIcons.SpeakerOn else MeshIcons.SpeakerOff,
                    label = if (isSpeakerOn) "ГРОМ." else "ТРУБКА",
                    isActive = isSpeakerOn,
                    activeColor = PixelAccent,
                    onClick = onToggleSpeaker
                )
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun CallActionButton(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    if (isActive) activeColor.copy(alpha = 0.2f)
                    else Color.White.copy(alpha = 0.1f)
                )
                .border(
                    1.dp,
                    if (isActive) activeColor.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.2f),
                    CircleShape
                )
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                icon, label,
                tint = if (isActive) activeColor else Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            color = if (isActive) activeColor else Color.White.copy(alpha = 0.5f),
            fontSize = 8.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun CallQualityCard(metrics: CallMetrics) {
    val qualityColor = when (metrics.quality) {
        CallQuality.GOOD -> Color(0xFF00E676)
        CallQuality.FAIR -> Color(0xFFFFB300)
        CallQuality.POOR -> Color(0xFFFF1744)
    }
    val qualityText = when (metrics.quality) {
        CallQuality.GOOD -> "ХОРОШАЯ"
        CallQuality.FAIR -> "СРЕДНЯЯ"
        CallQuality.POOR -> "ПЛОХАЯ"
    }

    Row(
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(8.dp)
                    .background(qualityColor, CircleShape)
            )
            Spacer(Modifier.height(4.dp))
            Text(qualityText, color = qualityColor, fontSize = 7.sp, fontWeight = FontWeight.Bold)
        }

        if (metrics.rttMs >= 0) {
            MetricItem("RTT", "${metrics.rttMs}мс")
        }

        if (metrics.lossRatePercent > 0.1f) {
            MetricItem(
                "ПОТЕРИ",
                "${String.format("%.1f", metrics.lossRatePercent)}%",
                warn = metrics.lossRatePercent > 3f
            )
        }

        if (metrics.jitterMs > 10) {
            MetricItem("JITTER", "${metrics.jitterMs}мс")
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, warn: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = if (warn) PixelWarn else Color.White.copy(0.9f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Text(label, color = Color.White.copy(0.4f), fontSize = 6.sp)
    }
}

@Composable
private fun animateFloat(
    transition: InfiniteTransition,
    from: Float,
    to: Float,
    durationMs: Int
): Float {
    val value by transition.animateFloat(
        from, to,
        infiniteRepeatable(tween(durationMs), RepeatMode.Reverse),
        label = "float_$durationMs"
    )
    return value
}

fun formatCallDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}