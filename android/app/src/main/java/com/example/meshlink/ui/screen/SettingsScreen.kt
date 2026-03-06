package com.example.meshlink.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meshlink.ui.theme.*
import com.example.meshlink.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val username      by viewModel.username.collectAsState()
    val peerCount     by viewModel.peerCount.collectAsState()
    val meshRouteCount by viewModel.meshRouteCount.collectAsState()
    val isWifiOn      by viewModel.isWifiDirectEnabled.collectAsState()
    val ownPeerId     by viewModel.ownPeerId.collectAsState()
    val ownShortCode  by viewModel.ownShortCode.collectAsState()
    val coreVersion   by viewModel.coreVersion.collectAsState()
    val rustRouteCount by viewModel.rustRouteCount.collectAsState()

    var editingUsername by remember { mutableStateOf(false) }
    var usernameInput  by remember(username) { mutableStateOf(username) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelBlack)
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
    ) {
        // ── Хедер ──────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF0D1117), Color(0xFF0A0A0F)))
                )
                .border(1.dp, PixelBorder)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PixelMidGray)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MeshIcons.Back,
                    contentDescription = "Назад",
                    tint = PixelAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "НАСТРОЙКИ",
                fontFamily = PressStart2PFamily,
                fontSize = 12.sp,
                color = PixelText
            )
        }

        Spacer(Modifier.height(20.dp))

        // ── МОЙ ПРОФИЛЬ ────────────────────────────────────────────────────────
        SettingsCard("// МОЙ ПРОФИЛЬ") {
            // Аватар + short code
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PixelMidGray, RoundedCornerShape(12.dp))
                    .border(1.dp, PixelBorderAccent, RoundedCornerShape(12.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Аватар
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(PixelAccentDark, PixelPurple))
                        )
                        .border(2.dp, PixelAccent.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = username.take(1).uppercase().ifBlank { "?" },
                        fontFamily = PressStart2PFamily,
                        fontSize = 20.sp,
                        color = PixelText
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column {
                    Text(
                        text = ownShortCode,
                        fontFamily = PressStart2PFamily,
                        fontSize = 24.sp,
                        color = PixelAccent,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = "КОД УСТРОЙСТВА",
                        fontFamily = PressStart2PFamily,
                        fontSize = 5.sp,
                        color = PixelTextDim
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = username.ifBlank { "(не задано)" }.uppercase(),
                        fontFamily = PressStart2PFamily,
                        fontSize = 8.sp,
                        color = PixelText
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            SettingsRow("PEER ID", if (ownPeerId.isNotBlank()) ownPeerId.take(16) + "..." else "...")
        }

        Spacer(Modifier.height(12.dp))

        // ── USERNAME ───────────────────────────────────────────────────────────
        SettingsCard("// ИМЯ ПОЛЬЗОВАТЕЛЯ") {
            if (editingUsername) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = usernameInput,
                        onValueChange = { usernameInput = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = {
                            Text(
                                "имя пользователя...",
                                fontFamily = PressStart2PFamily,
                                fontSize = 7.sp,
                                color = PixelTextHint
                            )
                        },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = PixelText
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = PixelMidGray,
                            unfocusedContainerColor = PixelMidGray,
                            focusedIndicatorColor = PixelAccent,
                            unfocusedIndicatorColor = PixelBorder,
                            cursorColor = PixelAccent
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(PixelAccent)
                            .clickable {
                                viewModel.setUsername(usernameInput.trim())
                                editingUsername = false
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "OK",
                            fontFamily = PressStart2PFamily,
                            fontSize = 8.sp,
                            color = PixelBlack
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SettingsRow("ИМЯ", username.ifBlank { "(не задано)" })
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(PixelMidGray)
                            .border(1.dp, PixelBorder, RoundedCornerShape(6.dp))
                            .clickable { editingUsername = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = MeshIcons.Edit,
                                contentDescription = null,
                                tint = PixelAccentDark,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "ИЗМЕНИТЬ",
                                fontFamily = PressStart2PFamily,
                                fontSize = 6.sp,
                                color = PixelAccentDark
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── СЕТЬ ──────────────────────────────────────────────────────────────
        SettingsCard("// СОСТОЯНИЕ СЕТИ") {
            SettingsRow(
                "P2P СТАТУС",
                if (isWifiOn) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН",
                if (isWifiOn) PixelAccent else PixelWarn
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "ПРЯМЫЕ ПИРЫ",
                "$peerCount",
                if (peerCount > 0) PixelAccent else PixelTextDim
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "MESH МАРШРУТЫ",
                "$meshRouteCount",
                if (meshRouteCount > 0) Color(0xFF00CFFF) else PixelTextDim
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow("ТРАНСПОРТ", "WIFI-DIRECT + BT")
        }

        Spacer(Modifier.height(12.dp))

        // ── RUST ЯДРО ─────────────────────────────────────────────────────────
        SettingsCard("// RUST ЯДРО") {
            SettingsRow("ВЕРСИЯ", coreVersion)
            Spacer(Modifier.height(8.dp))
            SettingsRow("КРИПТОГРАФИЯ", "ED25519", PixelAccent)
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                "ТАБЛИЦА МАРШРУТОВ",
                "$rustRouteCount записей",
                if (rustRouteCount > 0) Color(0xFF00CFFF) else PixelTextDim
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow("ИДЕНТИФИКАТОР", "ПОСТОЯННЫЙ", PixelAccent)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        Text(
            title,
            fontFamily = PressStart2PFamily,
            fontSize = 6.sp,
            color = PixelTextDim,
            modifier = Modifier.padding(bottom = 8.dp, start = 2.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0D1117), RoundedCornerShape(12.dp))
                .border(1.dp, PixelBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
            content = content
        )
    }
}

@Composable
fun SettingsRow(
    label: String,
    value: String,
    valueColor: Color = PixelAccent
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "$label:",
            fontFamily = PressStart2PFamily,
            fontSize = 6.sp,
            color = PixelTextDim,
            modifier = Modifier.width(120.dp)
        )
        Text(
            value,
            fontFamily = PressStart2PFamily,
            fontSize = 7.sp,
            color = valueColor,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}