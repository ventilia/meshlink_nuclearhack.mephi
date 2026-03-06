package com.example.meshlink.ui.screen

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.meshlink.domain.model.message.*
import com.example.meshlink.ui.theme.*
import com.example.meshlink.ui.viewmodel.ChatViewModel
import com.example.meshlink.ui.viewmodel.ChatViewModelFactory
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image

// ─── Хелпер: проверка, является ли файл изображением ─────────────────────────
private fun String.isImageFile(): Boolean {
    val lower = this.lowercase()
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp")
}

@Composable
fun ChatScreen(
    peerId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(peerId))
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val messages     by viewModel.messages.collectAsState()
    val isRecording  by viewModel.isRecording.collectAsState()
    val incomingCall by viewModel.incomingCall.collectAsState()
    val callActive   by viewModel.callActive.collectAsState()
    val outgoingCall by viewModel.outgoingCall.collectAsState()
    val contactName  by viewModel.contactName.collectAsState()
    val isOnline     by viewModel.isOnline.collectAsState()
    val hopCount     by viewModel.hopCount.collectAsState()
    val ownPeerId    by viewModel.ownPeerId.collectAsState()
    val currentAlias by viewModel.currentAlias.collectAsState()
    val playingFile  by viewModel.playingFile.collectAsState()

    var showAliasDialog by remember { mutableStateOf(false) }
    var fullscreenImage by remember { mutableStateOf<File?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.sendFile(peerId, it) }
    }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording(context)
    }

    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(peerId) { viewModel.markAllRead(peerId) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(PixelBlack)
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
        ) {
            // ── TopBar ───────────────────────────────────────────────────────
            ChatTopBar(
                contactName = contactName ?: peerId.take(8).uppercase(),
                isOnline = isOnline,
                hopCount = hopCount,
                callActive = callActive,
                onBack = onBack,
                onCallClick = { if (callActive) viewModel.endCall() else viewModel.requestCall() },
                onRenameClick = { showAliasDialog = true }
            )

            // ── Активный звонок — баннер ─────────────────────────────────────
            AnimatedVisibility(
                visible = callActive,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                ActiveCallBanner(onEndCall = { viewModel.endCall() })
            }

            // ── Сообщения ────────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                state = listState,
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                var lastDate = ""
                items(messages, key = { it.messageId }) { msg ->
                    val msgDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(msg.timestamp))
                    if (msgDate != lastDate) {
                        lastDate = msgDate
                        DateDivider(date = msgDate)
                    }
                    MessageBubble(
                        message = msg,
                        isOwn = msg.senderId == ownPeerId,
                        playingFile = playingFile,
                        onPlayAudio = { viewModel.playAudio(it) },
                        onOpenFile = { fileName ->
                            try {
                                val file = viewModel.getFile(fileName)
                                if (file.exists()) {
                                    if (fileName.isImageFile()) {
                                        fullscreenImage = file
                                    } else {
                                        val uri = FileProvider.getUriForFile(
                                            context, "${context.packageName}.fileprovider", file
                                        )
                                        val mime = context.contentResolver.getType(uri) ?: "*/*"
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Открыть"))
                                    }
                                } else {
                                    Toast.makeText(context, "Файл не найден", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "Нет приложения для открытия", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onImageClick = { fileName ->
                            val file = viewModel.getFile(fileName)
                            if (file.exists()) fullscreenImage = file
                        }
                    )
                    Spacer(Modifier.height(2.dp))
                }

                if (messages.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "// ЗАЩИЩЁННЫЙ КАНАЛ",
                                    fontFamily = PressStart2PFamily,
                                    fontSize = 7.sp,
                                    color = PixelAccent
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "сообщений нет\nотправьте первое",
                                    fontFamily = PressStart2PFamily,
                                    fontSize = 6.sp,
                                    color = PixelTextDim,
                                    lineHeight = 14.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            // ── Панель ввода ─────────────────────────────────────────────────
            MessageInputBar(
                inputText = inputText,
                isRecording = isRecording,
                isOnline = isOnline,
                onTextChange = { inputText = it },
                onSendText = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendText(peerId, inputText.trim())
                        inputText = ""
                    }
                },
                onAttachFile = { filePicker.launch("*/*") },
                onRecordStart = {
                    val granted = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.startRecording(context)
                    } else {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onRecordStop = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.stopRecordingAndSend(peerId)
                }
            )
        }

        // ── Входящий звонок ──────────────────────────────────────────────────
        if (incomingCall != null) {
            IncomingCallOverlay(
                callerName = contactName ?: peerId.take(8).uppercase(),
                onAccept = { viewModel.acceptCall() },
                onReject = { viewModel.rejectCall() }
            )
        }

        // ── Исходящий звонок ─────────────────────────────────────────────────
        if (outgoingCall) {
            OutgoingCallOverlay(
                calleeName = contactName ?: peerId.take(8).uppercase(),
                onCancel = { viewModel.endCall() }
            )
        }

        // ── Полноэкранное изображение ────────────────────────────────────────
        fullscreenImage?.let { file ->
            FullscreenImageViewer(
                file = file,
                onDismiss = { fullscreenImage = null }
            )
        }
    }

    // ── Диалог переименования ─────────────────────────────────────────────────
    if (showAliasDialog) {
        AliasDialog(
            currentAlias = currentAlias,
            peerId = peerId,
            onSave = { alias ->
                viewModel.saveAlias(alias)
                showAliasDialog = false
            },
            onDismiss = { showAliasDialog = false }
        )
    }
}

// ── Хедер чата (стиль Telegram + пиксельность) ────────────────────────────────
@Composable
private fun ChatTopBar(
    contactName: String,
    isOnline: Boolean,
    hopCount: Int,
    callActive: Boolean,
    onBack: () -> Unit,
    onCallClick: () -> Unit,
    onRenameClick: () -> Unit
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Кнопка назад
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

        Spacer(Modifier.width(10.dp))

        // Аватар
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(PixelAccentDark, PixelPurple)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = contactName.take(1).uppercase(),
                fontFamily = PressStart2PFamily,
                fontSize = 14.sp,
                color = PixelText
            )
        }

        Spacer(Modifier.width(10.dp))

        // Имя и статус
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = contactName,
                fontFamily = PressStart2PFamily,
                fontSize = 10.sp,
                color = PixelText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Индикатор статуса
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            when {
                                !isOnline -> PixelTextDim
                                hopCount > 1 -> Color(0xFF00CFFF)
                                else -> PixelAccent
                            },
                            CircleShape
                        )
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = when {
                        !isOnline -> "не в сети"
                        hopCount > 1 -> "mesh · $hopCount хопов"
                        else -> "в сети"
                    },
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = if (isOnline) PixelAccent else PixelTextDim
                )
            }
        }

        // Кнопка переименования
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onRenameClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = MeshIcons.Edit,
                contentDescription = "Переименовать",
                tint = PixelTextDim,
                modifier = Modifier.size(18.dp)
            )
        }

        Spacer(Modifier.width(4.dp))

        // Кнопка звонка
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    when {
                        callActive -> PixelWarn.copy(alpha = 0.2f)
                        isOnline -> PixelAccent.copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                )
                .clickable(enabled = isOnline || callActive) { onCallClick() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (callActive) MeshIcons.CallEnd else MeshIcons.Call,
                contentDescription = if (callActive) "Завершить звонок" else "Позвонить",
                tint = when {
                    callActive -> PixelWarn
                    isOnline -> PixelAccent
                    else -> PixelTextDim
                },
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ── Баннер активного звонка ───────────────────────────────────────────────────
@Composable
fun ActiveCallBanner(onEndCall: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.7f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse_alpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D2A1A))
            .border(width = 1.dp, color = PixelAccent.copy(alpha = 0.4f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .alpha(alpha)
                    .background(PixelAccent, CircleShape)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "ЗВОНОК АКТИВЕН",
                    fontFamily = PressStart2PFamily,
                    fontSize = 8.sp,
                    color = PixelAccent
                )
                Text(
                    "зашифрованный канал",
                    fontFamily = PressStart2PFamily,
                    fontSize = 5.sp,
                    color = PixelAccentDark
                )
            }
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(PixelWarn)
                .clickable { onEndCall() }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MeshIcons.CallEnd,
                    contentDescription = "Завершить",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "ЗАВЕРШИТЬ",
                    fontFamily = PressStart2PFamily,
                    fontSize = 7.sp,
                    color = Color.White
                )
            }
        }
    }
}

// ── Оверлей входящего звонка (полноэкранный) ─────────────────────────────────
@Composable
fun IncomingCallOverlay(
    callerName: String,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rings")
    val ring1 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(1200, delayMillis = 400), RepeatMode.Restart),
        label = "ring2"
    )
    val ring1Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Restart),
        label = "ring1a"
    )
    val ring2Alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1200, delayMillis = 400), RepeatMode.Restart),
        label = "ring2a"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6060610)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "ВХОДЯЩИЙ ЗВОНОК",
                fontFamily = PressStart2PFamily,
                fontSize = 8.sp,
                color = PixelTextDim
            )

            Spacer(Modifier.height(32.dp))

            // Аватар с пульсирующими кольцами
            Box(contentAlignment = Alignment.Center) {
                // Кольцо 2
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(ring2)
                        .alpha(ring2Alpha)
                        .background(PixelCallGreen.copy(alpha = 0.2f), CircleShape)
                )
                // Кольцо 1
                Box(
                    modifier = Modifier
                        .size(110.dp)
                        .scale(ring1)
                        .alpha(ring1Alpha)
                        .background(PixelCallGreen.copy(alpha = 0.3f), CircleShape)
                )
                // Аватар
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(PixelAccentDark, PixelPurple)
                            )
                        )
                        .border(2.dp, PixelAccent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        callerName.take(1).uppercase(),
                        fontFamily = PressStart2PFamily,
                        fontSize = 24.sp,
                        color = PixelText
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                callerName,
                fontFamily = PressStart2PFamily,
                fontSize = 14.sp,
                color = PixelText
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "mesh · зашифрованный вызов",
                fontFamily = PressStart2PFamily,
                fontSize = 6.sp,
                color = PixelTextDim
            )

            Spacer(Modifier.height(48.dp))

            // Кнопки принять / отклонить
            Row(
                horizontalArrangement = Arrangement.spacedBy(40.dp)
            ) {
                // Отклонить
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(PixelWarn)
                            .clickable { onReject() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MeshIcons.CallEnd,
                            contentDescription = "Отклонить",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ОТКАЗАТЬ",
                        fontFamily = PressStart2PFamily,
                        fontSize = 6.sp,
                        color = PixelTextDim
                    )
                }

                // Принять
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(PixelCallGreen)
                            .clickable { onAccept() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MeshIcons.CallAccept,
                            contentDescription = "Принять",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "ПРИНЯТЬ",
                        fontFamily = PressStart2PFamily,
                        fontSize = 6.sp,
                        color = PixelAccent
                    )
                }
            }
        }
    }
}

// ── Оверлей исходящего звонка ──────────────────────────────────────────────────
@Composable
fun OutgoingCallOverlay(
    calleeName: String,
    onCancel: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val dotAlpha1 by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "d1"
    )
    val dotAlpha2 by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 200), RepeatMode.Reverse), label = "d2"
    )
    val dotAlpha3 by infiniteTransition.animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(600, delayMillis = 400), RepeatMode.Reverse), label = "d3"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6060610)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                "ИСХОДЯЩИЙ ЗВОНОК",
                fontFamily = PressStart2PFamily,
                fontSize = 8.sp,
                color = PixelTextDim
            )

            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(PixelAccentDark, PixelPurple)))
                    .border(2.dp, PixelAccent.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    calleeName.take(1).uppercase(),
                    fontFamily = PressStart2PFamily,
                    fontSize = 24.sp,
                    color = PixelText
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                calleeName,
                fontFamily = PressStart2PFamily,
                fontSize = 14.sp,
                color = PixelText
            )

            Spacer(Modifier.height(12.dp))

            // Анимированные точки "звоним..."
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ЗВОНИМ", fontFamily = PressStart2PFamily, fontSize = 7.sp, color = PixelAccentDark)
                Spacer(Modifier.width(4.dp))
                listOf(dotAlpha1, dotAlpha2, dotAlpha3).forEach { a ->
                    Box(
                        Modifier
                            .size(6.dp)
                            .alpha(a)
                            .background(PixelAccent, CircleShape)
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(PixelWarn)
                        .clickable { onCancel() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MeshIcons.CallEnd,
                        contentDescription = "Отмена",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "ОТМЕНА",
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = PixelTextDim
                )
            }
        }
    }
}

// ── Разделитель по дате ───────────────────────────────────────────────────────
@Composable
private fun DateDivider(date: String) {
    val today = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
    val yesterday = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
        .format(Date(System.currentTimeMillis() - 86_400_000))

    val label = when (date) {
        today -> "СЕГОДНЯ"
        yesterday -> "ВЧЕРА"
        else -> date
    }
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontFamily = PressStart2PFamily,
            fontSize = 6.sp,
            color = PixelTextDim,
            modifier = Modifier
                .background(Color(0xFF1A1A28), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

// ── Пузырь сообщения ──────────────────────────────────────────────────────────
@Composable
fun MessageBubble(
    message: Message,
    isOwn: Boolean,
    playingFile: String?,
    onPlayAudio: (String) -> Unit,
    onOpenFile: (String) -> Unit,
    onImageClick: (String) -> Unit
) {
    val alignment = if (isOwn) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalAlignment = alignment
    ) {
        when (message) {
            is TextMessage -> TextBubble(message, isOwn)
            is AudioMessage -> AudioBubble(message, isOwn, playingFile, onPlayAudio)
            is FileMessage -> {
                if (message.fileName.isImageFile()) {
                    ImageBubble(message, isOwn, onImageClick)
                } else {
                    FileBubble(message, isOwn, onOpenFile)
                }
            }
        }
    }
}

@Composable
private fun TextBubble(message: TextMessage, isOwn: Boolean) {
    val bubbleBg = if (isOwn) PixelBubbleOut else PixelBubbleIn
    val bubbleBorder = if (isOwn) PixelBubbleOutBorder else PixelBubbleInBorder
    val textColor = if (isOwn) PixelText else PixelText
    val shape = RoundedCornerShape(
        topStart = if (isOwn) 16.dp else 4.dp,
        topEnd = if (isOwn) 4.dp else 16.dp,
        bottomStart = 16.dp,
        bottomEnd = 16.dp
    )

    Box(
        modifier = Modifier
            .widthIn(min = 80.dp, max = 280.dp)
            .background(bubbleBg, shape)
            .border(1.dp, bubbleBorder, shape)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column {
            Text(
                text = message.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = textColor,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    fontFamily = PressStart2PFamily,
                    fontSize = 5.sp,
                    color = PixelTextDim
                )
                if (isOwn) {
                    MessageStateIcon(message.messageState)
                }
            }
        }
    }
}

@Composable
private fun AudioBubble(
    message: AudioMessage,
    isOwn: Boolean,
    playingFile: String?,
    onPlay: (String) -> Unit
) {
    val isPlaying = playingFile == message.fileName
    val bubbleBg = if (isOwn) PixelBubbleOut else PixelBubbleIn
    val bubbleBorder = if (isOwn) PixelBubbleOutBorder else PixelBubbleInBorder
    val shape = RoundedCornerShape(
        topStart = if (isOwn) 16.dp else 4.dp,
        topEnd = if (isOwn) 4.dp else 16.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp
    )

    // Анимация волн при воспроизведении — через animateFloatAsState,
    // т.к. infiniteRepeatable нельзя использовать условно внутри InfiniteTransition
    val waveAnim by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = if (isPlaying) 1000 else 300),
        label = "wave"
    )

    Box(
        modifier = Modifier
            .widthIn(min = 160.dp, max = 240.dp)
            .background(bubbleBg, shape)
            .border(1.dp, bubbleBorder, shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Play/Pause кнопка
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isOwn) PixelAccent.copy(alpha = 0.2f) else PixelPurple)
                    .border(1.dp, if (isOwn) PixelAccent else PixelPurpleLight, CircleShape)
                    .clickable { onPlay(message.fileName) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isPlaying) MeshIcons.Pause else MeshIcons.Play,
                    contentDescription = if (isPlaying) "Пауза" else "Воспроизвести",
                    tint = if (isOwn) PixelAccent else PixelText,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Визуализация волны
                Row(
                    modifier = Modifier.height(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val bars = 12
                    val heights = remember { List(bars) { (0.2f + Math.random() * 0.8f).toFloat() } }
                    heights.forEachIndexed { idx, h ->
                        val animH = if (isPlaying) {
                            val phase = (waveAnim + idx.toFloat() / bars) % 1f
                            h * (0.3f + 0.7f * kotlin.math.sin(phase * Math.PI.toFloat()).coerceAtLeast(0f))
                        } else h * 0.4f
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight(animH.coerceIn(0.1f, 1f))
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (isOwn) PixelAccent.copy(alpha = if (isPlaying) 1f else 0.5f)
                                    else PixelText.copy(alpha = if (isPlaying) 0.9f else 0.4f)
                                )
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (isPlaying) "воспроизведение..." else "голосовое",
                        fontFamily = PressStart2PFamily,
                        fontSize = 5.sp,
                        color = PixelTextDim
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(message.timestamp),
                            fontFamily = PressStart2PFamily,
                            fontSize = 5.sp,
                            color = PixelTextDim
                        )
                        if (isOwn) {
                            Spacer(Modifier.width(3.dp))
                            MessageStateIcon(message.messageState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImageBubble(
    message: FileMessage,
    isOwn: Boolean,
    onImageClick: (String) -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = if (isOwn) 16.dp else 4.dp,
        topEnd = if (isOwn) 4.dp else 16.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp
    )
    val bubbleBorder = if (isOwn) PixelBubbleOutBorder else PixelBubbleInBorder

    Box(
        modifier = Modifier
            .widthIn(max = 240.dp)
            .clip(shape)
            .border(1.dp, bubbleBorder, shape)
            .clickable { onImageClick(message.fileName) }
    ) {
        Column {
            AsyncImage(
                model = "/data/data/com.example.meshlink/files/${message.fileName}",
                contentDescription = "Изображение",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .background(PixelMidGray)
            )
            // Подпись
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = MeshIcons.Image,
                        contentDescription = null,
                        tint = PixelTextDim,
                        modifier = Modifier.size(12.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(message.timestamp),
                            fontFamily = PressStart2PFamily,
                            fontSize = 5.sp,
                            color = PixelTextDim
                        )
                        if (isOwn) {
                            Spacer(Modifier.width(3.dp))
                            MessageStateIcon(message.messageState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileBubble(
    message: FileMessage,
    isOwn: Boolean,
    onOpenFile: (String) -> Unit
) {
    val bubbleBg = if (isOwn) PixelBubbleOut else PixelBubbleIn
    val bubbleBorder = if (isOwn) PixelBubbleOutBorder else PixelBubbleInBorder
    val shape = RoundedCornerShape(
        topStart = if (isOwn) 16.dp else 4.dp,
        topEnd = if (isOwn) 4.dp else 16.dp,
        bottomStart = 16.dp, bottomEnd = 16.dp
    )

    Box(
        modifier = Modifier
            .widthIn(min = 140.dp, max = 260.dp)
            .background(bubbleBg, shape)
            .border(1.dp, bubbleBorder, shape)
            .clickable { onOpenFile(message.fileName) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PixelPurple),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MeshIcons.File,
                    contentDescription = null,
                    tint = PixelText,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.fileName.take(20),
                    fontFamily = PressStart2PFamily,
                    fontSize = 6.sp,
                    color = PixelText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "открыть",
                        fontFamily = PressStart2PFamily,
                        fontSize = 5.sp,
                        color = if (isOwn) PixelAccent else Color(0xFF00CFFF)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatTime(message.timestamp),
                            fontFamily = PressStart2PFamily,
                            fontSize = 5.sp,
                            color = PixelTextDim
                        )
                        if (isOwn) {
                            Spacer(Modifier.width(3.dp))
                            MessageStateIcon(message.messageState)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageStateIcon(state: MessageState) {
    Icon(
        imageVector = when (state) {
            MessageState.MESSAGE_SENT -> MeshIcons.CheckSingle
            MessageState.MESSAGE_RECEIVED -> MeshIcons.CheckDouble
            MessageState.MESSAGE_READ -> MeshIcons.CheckDouble
        },
        contentDescription = null,
        tint = when (state) {
            MessageState.MESSAGE_READ -> PixelAccent
            else -> PixelTextDim
        },
        modifier = Modifier.size(12.dp)
    )
}

// ── Полноэкранный просмотр изображения ───────────────────────────────────────
@Composable
private fun FullscreenImageViewer(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = file.absolutePath,
                contentDescription = "Изображение",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
            // Закрыть
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { onDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Text("✕", fontSize = 16.sp, color = Color.White)
            }
        }
    }
}

// ── Панель ввода ──────────────────────────────────────────────────────────────
@Composable
fun MessageInputBar(
    inputText: String,
    isRecording: Boolean,
    isOnline: Boolean,
    onTextChange: (String) -> Unit,
    onSendText: () -> Unit,
    onAttachFile: () -> Unit,
    onRecordStart: () -> Unit,
    onRecordStop: () -> Unit
) {
    // PTT анимация — пульс при записи через animateFloatAsState
    val recPulse by animateFloatAsState(
        targetValue = if (isRecording) 1.15f else 1f,
        animationSpec = if (isRecording)
            infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse)
        else tween(200),
        label = "rec_scale"
    )
    val recGlow by animateFloatAsState(
        targetValue = if (isRecording) 1f else 0.5f,
        animationSpec = if (isRecording)
            infiniteRepeatable(tween(400), RepeatMode.Reverse)
        else tween(200),
        label = "rec_glow"
    )

    // Таймер записи
    var recordSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (true) {
                delay(1000)
                recordSeconds++
            }
        } else {
            recordSeconds = 0
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D0D16))
            .border(width = 1.dp, color = PixelBorder)
    ) {
        // Панель записи (появляется при записи)
        AnimatedVisibility(visible = isRecording) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A0A0A))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .alpha(recGlow)
                        .background(PixelWarn, CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "ЗАПИСЬ ${"%02d:%02d".format(recordSeconds / 60, recordSeconds % 60)}",
                    fontFamily = PressStart2PFamily,
                    fontSize = 7.sp,
                    color = PixelWarn
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "отпустите для отправки",
                    fontFamily = PressStart2PFamily,
                    fontSize = 5.sp,
                    color = PixelTextDim
                )
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            // Кнопка прикрепить
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(PixelMidGray)
                    .clickable { onAttachFile() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = MeshIcons.Attach,
                    contentDescription = "Прикрепить",
                    tint = PixelTextDim,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(8.dp))

            // Поле ввода
            TextField(
                value = inputText,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isOnline) "сообщение..." else "не в сети...",
                        fontFamily = PressStart2PFamily,
                        fontSize = 7.sp,
                        color = PixelTextHint
                    )
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = PixelText
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PixelMidGray,
                    unfocusedContainerColor = PixelMidGray,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PixelAccent
                ),
                shape = RoundedCornerShape(20.dp),
                maxLines = 4
            )

            Spacer(Modifier.width(8.dp))

            // Кнопка отправки или PTT
            if (inputText.isNotBlank()) {
                // Отправить
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(PixelAccent)
                        .clickable { onSendText() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = MeshIcons.Send,
                        contentDescription = "Отправить",
                        tint = PixelBlack,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // PTT — удерживать для записи
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .scale(recPulse)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) PixelWarn else PixelMidGray
                        )
                        .border(
                            width = if (isRecording) 2.dp else 1.dp,
                            color = if (isRecording) PixelWarn.copy(alpha = recGlow) else PixelBorder,
                            shape = CircleShape
                        )
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = {
                                    onRecordStart()
                                    val released = tryAwaitRelease()
                                    if (released) onRecordStop()
                                    else onRecordStop() // отменено
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) MeshIcons.Stop else MeshIcons.Microphone,
                        contentDescription = if (isRecording) "Записываю" else "Голосовое сообщение",
                        tint = if (isRecording) Color.White else PixelTextDim,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ── Диалог псевдонима ─────────────────────────────────────────────────────────
@Composable
private fun AliasDialog(
    currentAlias: String,
    peerId: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var aliasInput by remember(currentAlias) { mutableStateOf(currentAlias) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF0D1117), RoundedCornerShape(12.dp))
                .border(1.dp, PixelBorder, RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                "ПЕРЕИМЕНОВАТЬ",
                fontFamily = PressStart2PFamily,
                fontSize = 9.sp,
                color = PixelAccent
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "[${peerId.take(8).uppercase()}]",
                fontFamily = PressStart2PFamily,
                fontSize = 6.sp,
                color = PixelTextDim
            )
            Spacer(Modifier.height(14.dp))
            TextField(
                value = aliasInput,
                onValueChange = { aliasInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = {
                    Text(
                        "псевдоним...",
                        fontFamily = PressStart2PFamily,
                        fontSize = 7.sp,
                        color = PixelTextHint
                    )
                },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = PixelText),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = PixelMidGray,
                    unfocusedContainerColor = PixelMidGray,
                    focusedIndicatorColor = PixelAccent,
                    unfocusedIndicatorColor = PixelBorder,
                    cursorColor = PixelAccent
                )
            )
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PixelMidGray)
                        .clickable { onDismiss() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("ОТМЕНА", fontFamily = PressStart2PFamily, fontSize = 7.sp, color = PixelTextDim)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PixelAccent)
                        .clickable { onSave(aliasInput.trim()) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("СОХРАНИТЬ", fontFamily = PressStart2PFamily, fontSize = 7.sp, color = PixelBlack)
                }
            }
        }
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))