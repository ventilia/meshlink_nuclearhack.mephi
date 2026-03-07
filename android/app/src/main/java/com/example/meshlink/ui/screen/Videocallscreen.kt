package com.example.meshlink.ui.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meshlink.network.CallQuality
import com.example.meshlink.network.VideoMetrics
import com.example.meshlink.ui.theme.*
import com.example.meshlink.ui.viewmodel.VideoCallViewModel
import com.example.meshlink.ui.viewmodel.VideoCallViewModelFactory
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer

@Composable
fun VideoCallScreen(
    peerId: String,
    peerName: String,
    isIncoming: Boolean,
    onDismiss: () -> Unit,
    viewModel: VideoCallViewModel = viewModel(factory = VideoCallViewModelFactory(peerId))
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val isMuted by viewModel.isMuted.collectAsState()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val isCameraOn by viewModel.isCameraOn.collectAsState()
    val callDuration by viewModel.callDuration.collectAsState()
    var controlsVisible by remember { mutableStateOf(true) }

    // Запрос разрешений
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.all { it.value }) {
            viewModel.onPermissionsGranted(isIncoming)
        }
    }

    LaunchedEffect(Unit) {
        val missing = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        ).filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            viewModel.onPermissionsGranted(isIncoming)
        }
    }

    // Автоскрытие контролов через 4 секунды при активном звонке
    LaunchedEffect(controlsVisible, callState) {
        if (controlsVisible && callState == VideoCallState.ACTIVE) {
            delay(4000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable { controlsVisible = !controlsVisible }
    ) {
        // ── Удалённое видео (на весь экран) ───────────────────────────────────
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    // ИСПРАВЛЕНО: Убрана двойная инициализация и создание EGL
                    viewModel.attachRemoteRenderer(this)
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { renderer ->
                viewModel.detachRemoteRenderer(renderer)
            }
        )

        // ── Заглушка если нет видео ────────────────────────────────────────────
        if (callState != VideoCallState.ACTIVE || !isCameraOn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0A0A0F)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF1A3A2A), Color(0xFF0D1117))
                                )
                            )
                            .border(2.dp, PixelAccent.copy(alpha = 0.4f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            peerName.take(1).uppercase(),
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = PixelAccent
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(peerName, fontSize = 18.sp, color = Color.White, fontWeight = FontWeight.Medium)
                }
            }
        }

        // ── Локальное превью PiP (Picture-in-Picture) ─────────────────────────
        if (isCameraOn && callState == VideoCallState.ACTIVE) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 100.dp, height = 140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(2.dp, PixelAccent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).apply {
                            // ИСПРАВЛЕНО: Убрана двойная инициализация и создание EGL
                            viewModel.attachLocalRenderer(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { renderer ->
                        viewModel.detachLocalRenderer(renderer)
                    }
                )
            }
        }

        // ── Оверлеи: INCOMING / OUTGOING / ENDED ──────────────────────────────
        when (callState) {
            VideoCallState.INCOMING -> VideoIncomingOverlay(
                callerName = peerName,
                onAccept = { viewModel.acceptCall() },
                onReject = { viewModel.rejectCall(); onDismiss() }
            )
            VideoCallState.OUTGOING -> VideoOutgoingOverlay(
                calleeName = peerName,
                onCancel = { viewModel.cancelCall(); onDismiss() }
            )
            VideoCallState.ACTIVE -> Unit
            VideoCallState.ENDED -> {
                LaunchedEffect(Unit) { delay(500); onDismiss() }
            }
        }

        // ── Верхний HUD (имя + метрики) ────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible && callState == VideoCallState.ACTIVE,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    VideoSignalQualityDot(metrics)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        peerName,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatDuration(callDuration),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                VideoMetricsRow(metrics)
            }
        }

        // ── Нижние кнопки управления ───────────────────────────────────────────
        AnimatedVisibility(
            visible = controlsVisible && callState == VideoCallState.ACTIVE,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (isMuted) MeshIcons.MicOff else MeshIcons.Microphone,
                        label = if (isMuted) "UNMUTE" else "MUTE",
                        tint = if (isMuted) PixelWarn else Color.White,
                        background = Color.White.copy(alpha = 0.15f),
                        onClick = { viewModel.toggleMute() }
                    )
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(PixelWarn)
                            .clickable { viewModel.endCall(); onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            MeshIcons.CallEnd, "Завершить",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    CallControlButton(
                        icon = MeshIcons.FlipCamera,
                        label = "FLIP",
                        tint = Color.White,
                        background = Color.White.copy(alpha = 0.15f),
                        onClick = { viewModel.flipCamera() }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControlButton(
                        icon = if (isCameraOn) MeshIcons.VideoOn else MeshIcons.VideoOff,
                        label = if (isCameraOn) "CAM OFF" else "CAM ON",
                        tint = if (!isCameraOn) PixelWarn else Color.White,
                        background = Color.White.copy(alpha = 0.15f),
                        onClick = { viewModel.toggleCamera() }
                    )
                    Spacer(Modifier.size(52.dp))
                    CallControlButton(
                        icon = if (isSpeakerOn) MeshIcons.SpeakerOn else MeshIcons.SpeakerOff,
                        label = if (isSpeakerOn) "SPEAKER" else "EAR",
                        tint = if (isSpeakerOn) PixelAccent else Color.White,
                        background = Color.White.copy(alpha = 0.15f),
                        onClick = { viewModel.toggleSpeaker() }
                    )
                }
            }
        }
    }
}

// ── Вспомогательные Composable ────────────────────────────────────────────────
@Composable
private fun CallControlButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    background: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(background)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 9.sp)
    }
}

@Composable
private fun VideoSignalQualityDot(metrics: VideoMetrics) {
    val color = when (metrics.quality) {
        CallQuality.GOOD -> Color(0xFF00E676)
        CallQuality.FAIR -> Color(0xFFFFB300)
        CallQuality.POOR -> Color(0xFFFF1744)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val alpha by infiniteTransition.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "dot_alpha"
    )
    Box(Modifier.size(8.dp).alpha(alpha).background(color, CircleShape))
}

@Composable
private fun VideoMetricsRow(metrics: VideoMetrics) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (metrics.rttMs >= 0) {
            VideoMetricChip("RTT ${metrics.rttMs}ms", warn = metrics.rttMs > 200)
        }
        if (metrics.lossRatePercent > 0.1f) {
            VideoMetricChip(
                "loss ${String.format("%.1f", metrics.lossRatePercent)}%",
                warn = metrics.lossRatePercent > 3f
            )
        }
        if (metrics.framesPerSecond > 0f) {
            VideoMetricChip("${metrics.framesPerSecond.toInt()}fps")
        }
        if (metrics.videoResolution.isNotEmpty()) {
            VideoMetricChip(metrics.videoResolution)
        }
        if (metrics.bitrateKbps > 0) {
            VideoMetricChip("${metrics.bitrateKbps}kbps")
        }
    }
}

@Composable
private fun VideoMetricChip(text: String, warn: Boolean = false) {
    Text(
        text,
        color = if (warn) PixelWarn else Color.White.copy(alpha = 0.65f),
        fontSize = 10.sp,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun VideoIncomingOverlay(
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rings")
    val ring1 by infiniteTransition.animateFloat(
        0.6f, 1.4f, infiniteRepeatable(tween(1200), RepeatMode.Restart), "r1"
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        0.5f, 0f, infiniteRepeatable(tween(1200), RepeatMode.Restart), "r1a"
    )
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "ВХОДЯЩИЙ ВИДЕОЗВОНОК",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(32.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(120.dp).scale(ring1).alpha(ring1Alpha)
                        .background(Color(0xFF00E5FF).copy(alpha = 0.2f), CircleShape)
                )
                Box(
                    Modifier.size(80.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF1A3A2A), Color(0xFF0D1117))))
                        .border(2.dp, Color(0xFF00E5FF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        callerName.take(1).uppercase(),
                        fontSize = 28.sp,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(callerName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(48.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(PixelWarn)
                            .clickable { onReject() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(MeshIcons.CallEnd, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("ОТКАЗАТЬ", color = Color.White.copy(0.7f), fontSize = 9.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(Color(0xFF00C853))
                            .clickable { onAccept() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(MeshIcons.VideoOn, null, tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("ПРИНЯТЬ", color = Color(0xFF00C853), fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun VideoOutgoingOverlay(calleeName: String, onCancel: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        1f, 1.05f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse),
        "sc"
    )
    Box(
        Modifier.fillMaxSize().background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text("ВИДЕОЗВОНОК...", color = Color.White.copy(0.7f), fontSize = 11.sp)
            Spacer(Modifier.height(32.dp))
            Box(
                Modifier.size(80.dp).scale(scale).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF1A3A2A), Color(0xFF0D1117))))
                    .border(2.dp, PixelAccent.copy(0.6f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    calleeName.take(1).uppercase(),
                    fontSize = 28.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(calleeName, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Звоним...", color = Color.White.copy(0.5f), fontSize = 13.sp)
            Spacer(Modifier.height(48.dp))
            Box(
                Modifier.size(68.dp).clip(CircleShape).background(PixelWarn).clickable { onCancel() },
                contentAlignment = Alignment.Center
            ) {
                Icon(MeshIcons.CallEnd, null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("ОТМЕНА", color = Color.White.copy(0.7f), fontSize = 9.sp)
        }
    }
}

fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%02d:%02d".format(m, s)
}

enum class VideoCallState { INCOMING, OUTGOING, ACTIVE, ENDED }

private val EaseInOutSine = CubicBezierEasing(0.45f, 0.05f, 0.55f, 0.95f)