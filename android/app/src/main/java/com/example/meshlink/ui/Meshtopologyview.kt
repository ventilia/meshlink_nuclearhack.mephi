package com.example.meshlink.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meshlink.domain.model.NetworkDevice
import kotlin.math.*

/**
 * MeshTopologyView — интерактивная визуализация топологии меш-сети.
 *
 * - Текущий узел отображается в центре
 * - Прямые пиры (hop=1) на первом кольце, зелёным
 * - Mesh-пиры (hop>1) на втором кольце, голубым
 * - Линии показывают маршруты: сплошные = прямое, пунктир = через ретранслятор
 * - Пульсирующий эффект на активных узлах
 */
@Composable
fun MeshTopologyView(
    ownShortCode: String,
    devices: Map<String, NetworkDevice>,
    modifier: Modifier = Modifier
) {
    val directPeers = devices.values.filter { it.isDirect }
    val meshPeers = devices.values.filter { it.isMeshRelay }

    // Анимация пульса для "живых" узлов
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by pulseAnim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = EaseOutCubic),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_radius"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(Color(0xFF060E0A), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF1A3A24), RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            // ── Фоновые концентрические кольца ────────────────────────────
            drawCircle(
                color = Color(0xFF0D2018),
                radius = 70.dp.toPx(),
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color(0xFF0A180F),
                radius = 130.dp.toPx(),
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )

            // ── Пульс вокруг центра ────────────────────────────────────────
            if (devices.isNotEmpty()) {
                drawCircle(
                    color = Color(0xFF00FF88).copy(alpha = (1f - pulseRadius) * 0.3f),
                    radius = (20 + pulseRadius * 50).dp.toPx(),
                    center = Offset(centerX, centerY)
                )
            }

            // ── Позиции прямых пиров ───────────────────────────────────────
            val directPositions = computeCirclePositions(
                centerX, centerY,
                radius = 70.dp.toPx(),
                count = directPeers.size
            )

            // ── Позиции mesh-пиров ─────────────────────────────────────────
            val meshPositions = computeCirclePositions(
                centerX, centerY,
                radius = 125.dp.toPx(),
                count = meshPeers.size,
                angleOffset = if (directPeers.isNotEmpty()) PI.toFloat() / directPeers.size else 0f
            )

            // ── Линии к прямым пирам ───────────────────────────────────────
            directPositions.forEach { pos ->
                drawLine(
                    color = Color(0xFF00FF88).copy(alpha = 0.5f),
                    start = Offset(centerX, centerY),
                    end = pos,
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // ── Пунктирные линии к mesh-пирам ─────────────────────────────
            val dashPathEffect = PathEffect.dashPathEffect(
                floatArrayOf(8.dp.toPx(), 6.dp.toPx()), 0f
            )
            meshPeers.forEachIndexed { idx, meshPeer ->
                val meshPos = meshPositions.getOrNull(idx) ?: return@forEachIndexed

                // Линия от ретранслятора к mesh-пиру
                val relayPeer = directPeers.find { it.peerId == meshPeer.viaPeerId }
                val relayIdx = directPeers.indexOf(relayPeer)
                val relayPos = if (relayIdx >= 0) directPositions.getOrNull(relayIdx) else null

                if (relayPos != null) {
                    drawLine(
                        color = Color(0xFF00CFFF).copy(alpha = 0.4f),
                        start = relayPos,
                        end = meshPos,
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashPathEffect,
                        cap = StrokeCap.Round
                    )
                } else {
                    // Если не знаем ретранслятор — пунктир от центра
                    drawLine(
                        color = Color(0xFF00CFFF).copy(alpha = 0.25f),
                        start = Offset(centerX, centerY),
                        end = meshPos,
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = dashPathEffect,
                        cap = StrokeCap.Round
                    )
                }

                // Узел mesh-пира
                drawPeerNode(meshPos, Color(0xFF00CFFF), 14.dp.toPx())
            }

            // ── Узлы прямых пиров ─────────────────────────────────────────
            directPositions.forEach { pos ->
                drawPeerNode(pos, Color(0xFF00FF88), 16.dp.toPx())
            }
        }

        // ── Текстовые метки на прямых пирах ───────────────────────────────
        val directPositions = computeCirclePositionsForCompose(
            devices.values.count { it.isDirect }
        )
        directPeers.forEachIndexed { idx, peer ->
            val (fx, fy) = directPositions.getOrNull(idx) ?: return@forEachIndexed
            PeerLabel(
                label = peer.shortCode.ifBlank { peer.peerId.take(4).uppercase() },
                hopText = null,
                isDirectPeer = true,
                fractionX = fx,
                fractionY = fy
            )
        }

        // ── Текстовые метки на mesh-пирах ─────────────────────────────────
        val meshPositions = computeCirclePositionsForCompose(
            devices.values.count { it.isMeshRelay },
            radiusFraction = 0.80f,
            angleOffset = if (directPeers.isNotEmpty()) PI.toFloat() / directPeers.size else 0f
        )
        meshPeers.forEachIndexed { idx, peer ->
            val (fx, fy) = meshPositions.getOrNull(idx) ?: return@forEachIndexed
            PeerLabel(
                label = peer.shortCode.ifBlank { peer.peerId.take(4).uppercase() },
                hopText = "${peer.hopCount}H",
                isDirectPeer = false,
                fractionX = fx,
                fractionY = fy
            )
        }

        // ── Центральный узел (наше устройство) ────────────────────────────
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(38.dp)
                .clip(CircleShape)
                .background(Color(0xFF00FF88).copy(alpha = 0.15f))
                .border(2.dp, Color(0xFF00FF88), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = ownShortCode.take(4).ifBlank { "ME" },
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = Color(0xFF00FF88)
            )
        }

        // ── Легенда ───────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            LegendItem(Color(0xFF00FF88), "прямой (hop 1)")
            LegendItem(Color(0xFF00CFFF), "mesh (hop 2+)")
        }

        // ── Счётчик ───────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(10.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = "${directPeers.size + meshPeers.size} узлов",
                fontFamily = FontFamily.Monospace,
                fontSize = 8.sp,
                color = Color(0xFF00FF88)
            )
            if (meshPeers.isNotEmpty()) {
                Text(
                    text = "${meshPeers.size} relay",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 7.sp,
                    color = Color(0xFF00CFFF)
                )
            }
        }
    }
}

@Composable
private fun PeerLabel(
    label: String,
    hopText: String?,
    isDirectPeer: Boolean,
    fractionX: Float,
    fractionY: Float
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(
                    x = (fractionX * 100).coerceIn(2f, 90f).dp - 16.dp,
                    y = (fractionY * 100).coerceIn(2f, 90f).dp - 8.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (hopText != null) {
                Text(
                    text = hopText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 5.sp,
                    color = Color(0xFF00CFFF).copy(alpha = 0.7f)
                )
            }
            Text(
                text = label,
                fontFamily = FontFamily.Monospace,
                fontSize = 6.sp,
                color = if (isDirectPeer) Color(0xFF00FF88) else Color(0xFF00CFFF)
            )
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 6.sp, color = color.copy(0.7f))
    }
}

// ── Вычисление позиций узлов на окружности (для Canvas) ───────────────────────

private fun DrawScope.drawPeerNode(center: Offset, color: Color, radius: Float) {
    drawCircle(
        color = color.copy(alpha = 0.12f),
        radius = radius * 1.4f,
        center = center
    )
    drawCircle(
        color = color,
        radius = radius,
        center = center,
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
    )
    drawCircle(
        color = color.copy(alpha = 0.25f),
        radius = radius * 0.5f,
        center = center
    )
}

private fun computeCirclePositions(
    cx: Float, cy: Float,
    radius: Float,
    count: Int,
    angleOffset: Float = 0f
): List<Offset> {
    if (count == 0) return emptyList()
    return (0 until count).map { i ->
        val angle = (2 * PI / count * i + angleOffset - PI / 2).toFloat()
        Offset(cx + radius * cos(angle), cy + radius * sin(angle))
    }
}

private fun computeCirclePositionsForCompose(
    count: Int,
    radiusFraction: Float = 0.46f,
    angleOffset: Float = 0f
): List<Pair<Float, Float>> {
    if (count == 0) return emptyList()
    return (0 until count).map { i ->
        val angle = (2 * PI / count * i + angleOffset - PI / 2).toFloat()
        val fx = 0.5f + radiusFraction * cos(angle) * 0.5f
        val fy = 0.5f + radiusFraction * sin(angle) * 0.5f
        Pair(fx, fy)
    }
}

// ── NetworkStatusCard — компактная карточка статуса сети ─────────────────────

@Composable
fun NetworkStatusCard(
    ownShortCode: String,
    totalPeers: Int,
    directPeers: Int,
    meshPeers: Int,
    isWifiOn: Boolean,
    isBleActive: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF0A1A10), RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF1A3A24), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Транспорты
        Column {
            TransportIndicator("WI-FI", isWifiOn)
            Spacer(Modifier.height(3.dp))
            TransportIndicator("BLE", isBleActive)
        }

        // Разделитель
        Box(modifier = Modifier.width(1.dp).height(30.dp).background(Color(0xFF1A3A24)))

        // Счётчики
        StatChip("ВСЕГО", "$totalPeers", Color(0xFF00FF88))
        StatChip("ПРЯМЫХ", "$directPeers", Color(0xFF66FF99))
        StatChip("RELAY", "$meshPeers", Color(0xFF00CFFF))

        // Код
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF00FF88).copy(0.1f))
                .border(1.dp, Color(0xFF00FF88).copy(0.3f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
            Text(
                text = ownShortCode.take(4).ifBlank { "----" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF00FF88),
                letterSpacing = 3.sp
            )
        }
    }
}

@Composable
private fun TransportIndicator(name: String, isActive: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (isActive) Color(0xFF00FF88) else Color(0xFF444444))
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = name,
            fontFamily = FontFamily.Monospace,
            fontSize = 6.sp,
            color = if (isActive) Color(0xFF00FF88) else Color(0xFF555555)
        )
    }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            color = color
        )
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 5.sp,
            color = color.copy(alpha = 0.6f)
        )
    }
}