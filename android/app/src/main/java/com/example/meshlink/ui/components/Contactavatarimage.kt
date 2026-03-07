package com.example.meshlink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.meshlink.ui.theme.PixelPurple
import com.example.meshlink.ui.theme.PixelText
import com.example.meshlink.ui.theme.PressStart2PFamily
import java.io.File


@Composable
fun ContactAvatarImage(
    displayName: String,
    imageFileName: String?,
    filesDir: File,
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    fontSize: TextUnit = 16.sp
) {
    val imageFile = imageFileName?.let { File(filesDir, it) }
    val hasValidImage = imageFile != null && imageFile.exists() && imageFile.length() > 0

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(PixelPurple, Color(0xFF2A1A4A))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (hasValidImage) {
            AsyncImage(
                model = imageFile,
                contentDescription = "Фото $displayName",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
            val initial = displayName.trim().take(1).uppercase().ifBlank { "?" }
            Text(
                text = initial,
                fontFamily = PressStart2PFamily,
                fontSize = fontSize,
                color = PixelText
            )
        }
    }
}