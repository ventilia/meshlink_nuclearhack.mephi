package com.example.meshlink.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.meshlink.core.NativeCore
import com.example.meshlink.domain.model.NetworkDevice
import com.example.meshlink.domain.model.chat.ChatPreview
import com.example.meshlink.domain.model.message.TextMessage
import com.example.meshlink.ui.theme.*
import com.example.meshlink.ui.viewmodel.ChatListViewModel
import java.text.SimpleDateFormat
import java.util.*
import coil.compose.AsyncImage
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onChatClick: (String) -> Unit,
    onSettingsClick: () -> Unit,
    viewModel: ChatListViewModel = viewModel()
) {
    val context = LocalContext.current
    val shortCode = remember { NativeCore.getOwnShortCode() }
    val chatPreviews by viewModel.chatPreviews.collectAsState()
    val connectedDevices by viewModel.connectedDevices.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val deleteConfirmPeerId by viewModel.deleteConfirmPeerId.collectAsState()

    val pullRefreshState = rememberPullToRefreshState()
    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) { viewModel.refresh() }
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullRefreshState.endRefresh()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PixelBlack)
            .nestedScroll(pullRefreshState.nestedScrollConnection)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xFF0D1117), Color(0xFF0A0A0F))
                        )
                    )
                    .border(width = 1.dp, color = PixelBorder)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Логотип — пиксельный квадрат с иконкой
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(PixelAccentDark, PixelPurple)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MeshIcons.Wifi,
                            contentDescription = null,
                            tint = PixelAccent,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "MESHLINK",
                            fontFamily = PressStart2PFamily,
                            fontSize = 14.sp,
                            color = PixelText
                        )
                        Text(
                            text = "[$shortCode]",
                            fontFamily = PressStart2PFamily,
                            fontSize = 7.sp,
                            color = PixelAccent
                        )
                    }
                }


                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PixelMidGray)
                        .border(1.dp, PixelBorder, RoundedCornerShape(8.dp))
                        .clickable { onSettingsClick() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MeshIcons.Settings,
                        contentDescription = "Настройки",
                        tint = PixelTextDim,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0F0A))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val totalPeers = connectedDevices.size + chatPreviews.size
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(
                                if (totalPeers > 0) PixelAccent else PixelTextDim,
                                CircleShape
                            )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (totalPeers == 0) "СКАНИРУЮ..." else "$totalPeers ПИРОВ",
                        fontFamily = PressStart2PFamily,
                        fontSize = 7.sp,
                        color = if (totalPeers > 0) PixelAccent else PixelTextDim
                    )
                }
                Text(
                    text = "↓ обновить",
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = PixelTextHint
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {

                if (connectedDevices.isNotEmpty()) {
                    item {
                        SectionHeader("ПОБЛИЗОСТИ")
                    }
                    items(connectedDevices, key = { it.peerId }) { device ->
                        NearbyDeviceItem(device = device, onClick = { onChatClick(device.peerId) })
                    }
                    item {
                        HorizontalDivider(
                            color = PixelBorder,
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }


                if (chatPreviews.isNotEmpty()) {
                    item { SectionHeader("ЧАТЫ") }
                    items(chatPreviews, key = { it.contact.peerId }) { preview ->
                        ChatPreviewItem(
                            preview = preview,
                            filesDir = context.filesDir,
                            onClick = { onChatClick(preview.contact.peerId) },
                            onLongPress = { viewModel.requestDeleteChat(preview.contact.peerId) }
                        )
                    }
                }


                if (chatPreviews.isEmpty() && connectedDevices.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = MeshIcons.Wifi,
                                    contentDescription = null,
                                    tint = PixelTextDim.copy(alpha = 0.4f),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "НЕТ СИГНАЛА",
                                    fontFamily = PressStart2PFamily,
                                    fontSize = 10.sp,
                                    color = PixelTextDim
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    "включите wi-fi и\nожидайте пиров\nили потяните вниз",
                                    fontFamily = PressStart2PFamily,
                                    fontSize = 6.sp,
                                    color = PixelTextHint,
                                    lineHeight = 14.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = PixelMidGray,
            contentColor = PixelAccent
        )
    }


    if (deleteConfirmPeerId != null) {
        val peerId = deleteConfirmPeerId!!
        val preview = chatPreviews.find { it.contact.peerId == peerId }
        val name = (preview?.contact?.username ?: peerId.take(8)).uppercase()

        AlertDialog(
            onDismissRequest = { viewModel.cancelDeleteChat() },
            containerColor = Color(0xFF0D1117),
            shape = RoundedCornerShape(12.dp),
            title = {
                Text(
                    "УДАЛИТЬ ЧАТ",
                    fontFamily = PressStart2PFamily,
                    fontSize = 10.sp,
                    color = PixelWarn
                )
            },
            text = {
                Text(
                    "Удалить все сообщения с $name?",
                    fontFamily = PressStart2PFamily,
                    fontSize = 7.sp,
                    color = PixelText,
                    lineHeight = 14.sp
                )
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PixelWarn)
                        .clickable { viewModel.confirmDeleteChat(peerId) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("УДАЛИТЬ", fontFamily = PressStart2PFamily, fontSize = 7.sp, color = Color.White)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(PixelMidGray)
                        .border(1.dp, PixelBorder, RoundedCornerShape(8.dp))
                        .clickable { viewModel.cancelDeleteChat() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("ОТМЕНА", fontFamily = PressStart2PFamily, fontSize = 7.sp, color = PixelTextDim)
                }
            }
        )
    }
}


@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        fontFamily = PressStart2PFamily,
        fontSize = 7.sp,
        color = PixelTextDim,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
    )
}


@Composable
private fun NearbyDeviceItem(device: NetworkDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(Color(0xFF0F1A14))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            if (device.isDirect) PixelAccentDark else PixelPurple,
                            if (device.isDirect) Color(0xFF1A4A30) else Color(0xFF2A1A4A)
                        )
                    )
                )
                .border(
                    1.dp,
                    if (device.isDirect) PixelAccent.copy(alpha = 0.5f) else PixelPurpleLight.copy(alpha = 0.5f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                device.username.ifBlank { "?" }.take(1).uppercase(),
                fontFamily = PressStart2PFamily,
                fontSize = 14.sp,
                color = PixelText
            )
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.username.ifBlank { "НЕИЗВЕСТНО" }.uppercase(),
                fontFamily = PressStart2PFamily,
                fontSize = 9.sp,
                color = PixelText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(5.dp)
                        .background(
                            if (device.isDirect) PixelAccent else Color(0xFF00CFFF),
                            CircleShape
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "[${device.shortCode}]",
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = PixelAccentDark
                )
                if (device.isMeshRelay) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "~${device.hopCount} хоп",
                        fontFamily = PressStart2PFamily,
                        fontSize = 6.sp,
                        color = Color(0xFF00CFFF)
                    )
                }
            }
        }


        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PixelAccent.copy(alpha = 0.15f))
                .border(1.dp, PixelAccent.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                "ЧАТ",
                fontFamily = PressStart2PFamily,
                fontSize = 6.sp,
                color = PixelAccent
            )
        }
    }
}


@Composable
private fun ChatPreviewItem(
    preview: ChatPreview,
    filesDir: File,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongPress() }
                )
            }
            .background(PixelBlack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {

        ContactAvatarImage(
            displayName = preview.contact.username ?: preview.contact.peerId,
            imageFileName = preview.contact.imageFileName,
            filesDir = filesDir,
            size = 50.dp,
            fontSize = 16.sp
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = (preview.contact.username ?: preview.contact.peerId.take(8)).uppercase(),
                    fontFamily = PressStart2PFamily,
                    fontSize = 10.sp,
                    color = PixelText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                preview.lastMessage?.let { msg ->
                    Text(
                        text = formatTimestamp(msg.timestamp),
                        fontFamily = PressStart2PFamily,
                        fontSize = 6.sp,
                        color = if (preview.unreadCount > 0) PixelAccent else PixelTextDim
                    )
                }
            }
            Spacer(Modifier.height(5.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = when (val msg = preview.lastMessage) {
                        is TextMessage -> msg.text.take(40)
                        null -> "нет сообщений"
                        else -> "[файл]"
                    },
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = PixelTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (preview.unreadCount > 0) {
                    Box(
                        modifier = Modifier
                            .defaultMinSize(minWidth = 20.dp)
                            .clip(CircleShape)
                            .background(PixelAccent)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "${preview.unreadCount}",
                            fontFamily = PressStart2PFamily,
                            fontSize = 7.sp,
                            color = PixelBlack
                        )
                    }
                }
            }
        }
    }
    HorizontalDivider(
        color = PixelBorder.copy(alpha = 0.5f),
        thickness = 1.dp,
        modifier = Modifier.padding(start = 78.dp)
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "сейчас"
        diff < 3_600_000 -> "${diff / 60_000}м"
        diff < 86_400_000 -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        else -> SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(timestamp))
    }
}


@Composable
fun ContactAvatarImage(
    displayName: String,
    imageFileName: String?,
    filesDir: File,
    size: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit
) {
    val initial = displayName.take(1).uppercase()
    val imagePath = imageFileName?.let { File(filesDir, it).absolutePath }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(PixelPurple, Color(0xFF2A1A4A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (imagePath != null && File(imagePath).exists()) {
            AsyncImage(
                model = imagePath,
                contentDescription = "Аватар",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Text(
                text = initial,
                fontFamily = PressStart2PFamily,
                fontSize = fontSize,
                color = PixelText
            )
        }
    }
}