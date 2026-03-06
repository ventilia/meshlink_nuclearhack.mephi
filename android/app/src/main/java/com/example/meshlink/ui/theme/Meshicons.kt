package com.example.meshlink.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Кастомные SVG иконки для MeshLink.
 * Пиксельный стиль — квадратные, чёткие линии.
 */
object MeshIcons {

    // ── Скрепка / прикрепить файл ────────────────────────────────────────────
    val Attach: ImageVector get() = ImageVector.Builder(
        name = "Attach",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Скрепка
            moveTo(21.44f, 11.05f)
            lineTo(12.25f, 20.24f)
            curveTo(10.26f, 22.23f, 7.02f, 22.23f, 5.03f, 20.24f)
            curveTo(3.04f, 18.25f, 3.04f, 15.01f, 5.03f, 13.02f)
            lineTo(15.62f, 2.43f)
            curveTo(16.95f, 1.1f, 19.12f, 1.1f, 20.45f, 2.43f)
            curveTo(21.78f, 3.76f, 21.78f, 5.93f, 20.45f, 7.26f)
            lineTo(9.85f, 17.85f)
            curveTo(9.18f, 18.52f, 8.1f, 18.52f, 7.43f, 17.85f)
            curveTo(6.76f, 17.18f, 6.76f, 16.1f, 7.43f, 15.43f)
            lineTo(16.27f, 6.6f)
        }
    }.build()

    // ── Микрофон ─────────────────────────────────────────────────────────────
    val Microphone: ImageVector get() = ImageVector.Builder(
        name = "Microphone",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Капсула микрофона
            moveTo(12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            lineTo(9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            lineTo(15f, 5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Дуга
            moveTo(19f, 10f)
            curveTo(19f, 14.42f, 15.86f, 18f, 12f, 18f)
            curveTo(8.14f, 18f, 5f, 14.42f, 5f, 10f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Ножка
            moveTo(12f, 18f)
            lineTo(12f, 22f)
            moveTo(9f, 22f)
            lineTo(15f, 22f)
        }
    }.build()

    // ── Play (треугольник) ───────────────────────────────────────────────────
    val Play: ImageVector get() = ImageVector.Builder(
        name = "Play",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White),
            pathFillType = PathFillType.NonZero
        ) {
            moveTo(8f, 5f)
            lineTo(19f, 12f)
            lineTo(8f, 19f)
            close()
        }
    }.build()

    // ── Pause (две полоски) ──────────────────────────────────────────────────
    val Pause: ImageVector get() = ImageVector.Builder(
        name = "Pause",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White)
        ) {
            // Левая полоска
            moveTo(6f, 4f)
            lineTo(10f, 4f)
            lineTo(10f, 20f)
            lineTo(6f, 20f)
            close()
            // Правая полоска
            moveTo(14f, 4f)
            lineTo(18f, 4f)
            lineTo(18f, 20f)
            lineTo(14f, 20f)
            close()
        }
    }.build()

    // ── Stop (квадрат) ───────────────────────────────────────────────────────
    val Stop: ImageVector get() = ImageVector.Builder(
        name = "Stop",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(5f, 5f)
            lineTo(19f, 5f)
            lineTo(19f, 19f)
            lineTo(5f, 19f)
            close()
        }
    }.build()

    // ── Отправить (стрелка вправо) ───────────────────────────────────────────
    val Send: ImageVector get() = ImageVector.Builder(
        name = "Send",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White)
        ) {
            moveTo(2.01f, 21f)
            lineTo(23f, 12f)
            lineTo(2.01f, 3f)
            lineTo(2f, 10f)
            lineTo(17f, 12f)
            lineTo(2f, 14f)
            close()
        }
    }.build()

    // ── Файл / документ ──────────────────────────────────────────────────────
    val File: ImageVector get() = ImageVector.Builder(
        name = "File",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(14f, 2f)
            lineTo(6f, 2f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            lineTo(4f, 20f)
            curveTo(4f, 21.1f, 4.9f, 22f, 6f, 22f)
            lineTo(18f, 22f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            lineTo(20f, 8f)
            close()
            moveTo(14f, 2f)
            lineTo(20f, 8f)
            lineTo(14f, 8f)
            lineTo(14f, 2f)
        }
        // Линии текста
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(8f, 13f); lineTo(16f, 13f)
            moveTo(8f, 17f); lineTo(13f, 17f)
        }
    }.build()

    // ── Назад (шеврон влево) ─────────────────────────────────────────────────
    val Back: ImageVector get() = ImageVector.Builder(
        name = "Back",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15f, 18f)
            lineTo(9f, 12f)
            lineTo(15f, 6f)
        }
    }.build()

    // ── Настройки (шестерёнка) ───────────────────────────────────────────────
    val Settings: ImageVector get() = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Шестерёнка: внешний контур через gear-path
            moveTo(12f, 15f)
            curveTo(13.657f, 15f, 15f, 13.657f, 15f, 12f)
            curveTo(15f, 10.343f, 13.657f, 9f, 12f, 9f)
            curveTo(10.343f, 9f, 9f, 10.343f, 9f, 12f)
            curveTo(9f, 13.657f, 10.343f, 15f, 12f, 15f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(19.4f, 15f)
            curveTo(19.26f, 15.37f, 19.37f, 15.78f, 19.63f, 16.06f)
            lineTo(20.96f, 17.39f)
            lineTo(18.97f, 19.38f)
            lineTo(17.64f, 18.05f)
            curveTo(17.36f, 17.79f, 16.95f, 17.68f, 16.58f, 17.82f)
            curveTo(16.08f, 18.01f, 15.56f, 18.15f, 15.01f, 18.24f)
            curveTo(14.62f, 18.3f, 14.32f, 18.6f, 14.27f, 18.99f)
            lineTo(14f, 21f)
            lineTo(11.25f, 21f)
            lineTo(10.98f, 19f)
            curveTo(10.93f, 18.61f, 10.63f, 18.31f, 10.24f, 18.25f)
            curveTo(9.69f, 18.16f, 9.17f, 18.02f, 8.67f, 17.83f)
            curveTo(8.3f, 17.69f, 7.89f, 17.8f, 7.61f, 18.06f)
            lineTo(6.28f, 19.39f)
            lineTo(4.29f, 17.4f)
            lineTo(5.62f, 16.07f)
            curveTo(5.88f, 15.79f, 5.99f, 15.38f, 5.85f, 15.01f)
            curveTo(5.66f, 14.51f, 5.52f, 13.99f, 5.43f, 13.44f)
            curveTo(5.37f, 13.05f, 5.07f, 12.75f, 4.68f, 12.7f)
            lineTo(3f, 12.43f)
            lineTo(3f, 9.68f)
            lineTo(5f, 9.41f)
            curveTo(5.39f, 9.36f, 5.69f, 9.06f, 5.75f, 8.67f)
            curveTo(5.84f, 8.12f, 5.98f, 7.6f, 6.17f, 7.1f)
            curveTo(6.31f, 6.73f, 6.2f, 6.32f, 5.94f, 6.04f)
            lineTo(4.61f, 4.71f)
            lineTo(6.6f, 2.72f)
            lineTo(7.93f, 4.05f)
            curveTo(8.21f, 4.31f, 8.62f, 4.42f, 8.99f, 4.28f)
            curveTo(9.49f, 4.09f, 10.01f, 3.95f, 10.56f, 3.86f)
            curveTo(10.95f, 3.8f, 11.25f, 3.5f, 11.3f, 3.11f)
            lineTo(11.57f, 1f)
            lineTo(14.32f, 1f)
            lineTo(14.59f, 3f)
            curveTo(14.64f, 3.39f, 14.94f, 3.69f, 15.33f, 3.75f)
            curveTo(15.88f, 3.84f, 16.4f, 3.98f, 16.9f, 4.17f)
            curveTo(17.27f, 4.31f, 17.68f, 4.2f, 17.96f, 3.94f)
            lineTo(19.29f, 2.61f)
            lineTo(21.28f, 4.6f)
            lineTo(19.95f, 5.93f)
            curveTo(19.69f, 6.21f, 19.58f, 6.62f, 19.72f, 6.99f)
            curveTo(19.91f, 7.49f, 20.05f, 8.01f, 20.14f, 8.56f)
            curveTo(20.2f, 8.95f, 20.5f, 9.25f, 20.89f, 9.3f)
            lineTo(23f, 9.57f)
            lineTo(23f, 12.32f)
            lineTo(21f, 12.59f)
            curveTo(20.61f, 12.64f, 20.31f, 12.94f, 20.25f, 13.33f)
            curveTo(20.16f, 13.88f, 20.02f, 14.4f, 19.83f, 14.9f)
            lineTo(19.4f, 15f)
            close()
        }
    }.build()

    // ── Переименовать (карандаш) ─────────────────────────────────────────────
    val Edit: ImageVector get() = ImageVector.Builder(
        name = "Edit",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11f, 4f)
            lineTo(4f, 4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            lineTo(2f, 20f)
            curveTo(2f, 21.1f, 2.9f, 22f, 4f, 22f)
            lineTo(18f, 22f)
            curveTo(19.1f, 22f, 20f, 21.1f, 20f, 20f)
            lineTo(20f, 13f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(18.5f, 2.5f)
            curveTo(19.33f, 1.67f, 20.67f, 1.67f, 21.5f, 2.5f)
            curveTo(22.33f, 3.33f, 22.33f, 4.67f, 21.5f, 5.5f)
            lineTo(12f, 15f)
            lineTo(8f, 16f)
            lineTo(9f, 12f)
            close()
        }
    }.build()

    // ── Телефон (позвонить) ──────────────────────────────────────────────────
    val Call: ImageVector get() = ImageVector.Builder(
        name = "Call",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White)
        ) {
            moveTo(6.6f, 10.8f)
            curveTo(7.96f, 13.45f, 10.14f, 15.62f, 12.79f, 16.99f)
            lineTo(14.79f, 14.99f)
            curveTo(15.04f, 14.74f, 15.41f, 14.66f, 15.73f, 14.77f)
            curveTo(16.72f, 15.1f, 17.79f, 15.29f, 18.9f, 15.29f)
            curveTo(19.5f, 15.29f, 19.99f, 15.78f, 19.99f, 16.38f)
            lineTo(19.99f, 19.4f)
            curveTo(19.99f, 20f, 19.5f, 20.49f, 18.9f, 20.49f)
            curveTo(10.61f, 20.49f, 3.9f, 13.78f, 3.9f, 5.49f)
            curveTo(3.9f, 4.89f, 4.39f, 4.4f, 4.99f, 4.4f)
            lineTo(8.02f, 4.4f)
            curveTo(8.62f, 4.4f, 9.11f, 4.89f, 9.11f, 5.49f)
            curveTo(9.11f, 6.61f, 9.3f, 7.67f, 9.63f, 8.66f)
            curveTo(9.74f, 8.98f, 9.66f, 9.35f, 9.41f, 9.6f)
            close()
        }
    }.build()

    // ── Завершить звонок (перечёркнутый телефон) ─────────────────────────────
    val CallEnd: ImageVector get() = ImageVector.Builder(
        name = "CallEnd",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 9f)
            curveTo(14.62f, 9f, 17.15f, 9.54f, 19.44f, 10.5f)
            lineTo(21.3f, 8.64f)
            curveTo(18.42f, 7.29f, 15.29f, 6.5f, 12f, 6.5f)
            curveTo(8.71f, 6.5f, 5.58f, 7.29f, 2.7f, 8.64f)
            lineTo(4.56f, 10.5f)
            curveTo(6.85f, 9.54f, 9.38f, 9f, 12f, 9f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 13f)
            curveTo(10.38f, 13f, 8.82f, 13.33f, 7.4f, 13.91f)
            lineTo(9.62f, 16.13f)
            curveTo(10.39f, 15.89f, 11.18f, 15.75f, 12f, 15.75f)
            curveTo(12.82f, 15.75f, 13.61f, 15.89f, 14.38f, 16.13f)
            lineTo(16.6f, 13.91f)
            curveTo(15.18f, 13.33f, 13.62f, 13f, 12f, 13f)
            close()
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 17.5f)
            curveTo(11.17f, 17.5f, 10.5f, 18.17f, 10.5f, 19f)
            curveTo(10.5f, 19.83f, 11.17f, 20.5f, 12f, 20.5f)
            curveTo(12.83f, 20.5f, 13.5f, 19.83f, 13.5f, 19f)
            curveTo(13.5f, 18.17f, 12.83f, 17.5f, 12f, 17.5f)
            close()
        }
    }.build()

    // ── Принять звонок ───────────────────────────────────────────────────────
    val CallAccept: ImageVector get() = Call

    // ── Изображение ─────────────────────────────────────────────────────────
    val Image: ImageVector get() = ImageVector.Builder(
        name = "Image",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(21f, 19f)
            curveTo(21f, 20.1f, 20.1f, 21f, 19f, 21f)
            lineTo(5f, 21f)
            curveTo(3.9f, 21f, 3f, 20.1f, 3f, 19f)
            lineTo(3f, 5f)
            curveTo(3f, 3.9f, 3.9f, 3f, 5f, 3f)
            lineTo(19f, 3f)
            curveTo(20.1f, 3f, 21f, 3.9f, 21f, 5f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(8.5f, 10f)
            curveTo(9.33f, 10f, 10f, 9.33f, 10f, 8.5f)
            curveTo(10f, 7.67f, 9.33f, 7f, 8.5f, 7f)
            curveTo(7.67f, 7f, 7f, 7.67f, 7f, 8.5f)
            curveTo(7f, 9.33f, 7.67f, 10f, 8.5f, 10f)
            close()
            moveTo(21f, 15f)
            lineTo(16f, 10f)
            lineTo(5f, 21f)
        }
    }.build()

    // ── Галочка одна ─────────────────────────────────────────────────────────
    val CheckSingle: ImageVector get() = ImageVector.Builder(
        name = "CheckSingle",
        defaultWidth = 16.dp, defaultHeight = 16.dp,
        viewportWidth = 16f, viewportHeight = 16f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(2f, 8f)
            lineTo(6f, 12f)
            lineTo(14f, 4f)
        }
    }.build()

    // ── Галочка двойная ──────────────────────────────────────────────────────
    val CheckDouble: ImageVector get() = ImageVector.Builder(
        name = "CheckDouble",
        defaultWidth = 20.dp, defaultHeight = 16.dp,
        viewportWidth = 20f, viewportHeight = 16f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1f, 8f); lineTo(5f, 12f); lineTo(13f, 4f)
            moveTo(7f, 8f); lineTo(11f, 12f); lineTo(19f, 4f)
        }
    }.build()

    // ── Wifi сигнал ──────────────────────────────────────────────────────────
    val Wifi: ImageVector get() = ImageVector.Builder(
        name = "Wifi",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(1.5f, 8.43f)
            curveTo(5.85f, 4.43f, 11.69f, 3.18f, 17.07f, 4.67f)
            moveTo(5f, 12f)
            curveTo(8f, 9.5f, 12f, 8.8f, 16f, 10.1f)
            moveTo(8.5f, 15.5f)
            curveTo(10f, 14.3f, 12f, 13.8f, 14f, 14.5f)
            moveTo(12f, 19f)
            curveTo(12f, 19f, 12f, 19f, 12f, 19f)
        }
        path(fill = SolidColor(Color.White)) {
            moveTo(12f, 18f)
            curveTo(12.83f, 18f, 13.5f, 18.67f, 13.5f, 19.5f)
            curveTo(13.5f, 20.33f, 12.83f, 21f, 12f, 21f)
            curveTo(11.17f, 21f, 10.5f, 20.33f, 10.5f, 19.5f)
            curveTo(10.5f, 18.67f, 11.17f, 18f, 12f, 18f)
            close()
        }
    }.build()

    // ── Скачать ──────────────────────────────────────────────────────────────
    val Download: ImageVector get() = ImageVector.Builder(
        name = "Download",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2f); lineTo(12f, 15f)
            moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f)
            moveTo(3f, 19f); lineTo(21f, 19f)
        }
    }.build()

    // ── Микрофон выключен (перечёркнутый) ────────────────────────────────────
    val MicOff: ImageVector get() = ImageVector.Builder(
        name = "MicOff",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Капсула микрофона (неполная — перечёркнута)
            moveTo(9f, 9f)
            lineTo(9f, 12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(12.77f, 15f, 13.47f, 14.71f, 14f, 14.24f)
            moveTo(12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            lineTo(9f, 7f)
            moveTo(15f, 7f)
            lineTo(15f, 12f)
            curveTo(15f, 12.56f, 14.87f, 13.08f, 14.65f, 13.55f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Дуга усилителя (неполная)
            moveTo(19f, 10f)
            curveTo(19f, 12.76f, 17.6f, 15.2f, 15.43f, 16.67f)
            moveTo(5f, 10f)
            curveTo(5f, 11.27f, 5.32f, 12.46f, 5.88f, 13.5f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Ножка и линия мьюта
            moveTo(12f, 18f)
            lineTo(12f, 22f)
            moveTo(9f, 22f)
            lineTo(15f, 22f)
            // Перечёркивающая линия
            moveTo(2f, 2f)
            lineTo(22f, 22f)
        }
    }.build()

    // ── Динамик (громко) ─────────────────────────────────────────────────────
    val SpeakerOn: ImageVector get() = ImageVector.Builder(
        name = "SpeakerOn",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White)
        ) {
            // Корпус динамика
            moveTo(11f, 5f)
            lineTo(6f, 9f)
            lineTo(2f, 9f)
            lineTo(2f, 15f)
            lineTo(6f, 15f)
            lineTo(11f, 19f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Малая волна
            moveTo(15.54f, 8.46f)
            curveTo(16.92f, 9.84f, 17.74f, 11.78f, 17.74f, 12f)
            curveTo(17.74f, 12.22f, 16.92f, 14.16f, 15.54f, 15.54f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Большая волна
            moveTo(18.36f, 5.64f)
            curveTo(20.73f, 8.01f, 22f, 11f, 22f, 12f)
            curveTo(22f, 13f, 20.73f, 15.99f, 18.36f, 18.36f)
        }
    }.build()

    // ── Динамик (тихо / ухо) ─────────────────────────────────────────────────
    val SpeakerOff: ImageVector get() = ImageVector.Builder(
        name = "SpeakerOff",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = SolidColor(Color.White)
        ) {
            // Корпус динамика
            moveTo(11f, 5f)
            lineTo(6f, 9f)
            lineTo(2f, 9f)
            lineTo(2f, 15f)
            lineTo(6f, 15f)
            lineTo(11f, 19f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Перечёркивающая линия
            moveTo(23f, 9f)
            lineTo(17f, 15f)
            moveTo(17f, 9f)
            lineTo(23f, 15f)
        }
    }.build()

    // ── Видеокамера включена ──────────────────────────────────────────────────
    val VideoOn: ImageVector get() = ImageVector.Builder(
        name = "VideoOn",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Корпус камеры
            moveTo(15f, 10f)
            lineTo(21f, 7f)
            lineTo(21f, 17f)
            lineTo(15f, 14f)
            close()
            // Экран камеры
            moveTo(3f, 8f)
            curveTo(3f, 6.9f, 3.9f, 6f, 5f, 6f)
            lineTo(14f, 6f)
            curveTo(15.1f, 6f, 16f, 6.9f, 16f, 8f)
            lineTo(16f, 16f)
            curveTo(16f, 17.1f, 15.1f, 18f, 14f, 18f)
            lineTo(5f, 18f)
            curveTo(3.9f, 18f, 3f, 17.1f, 3f, 16f)
            close()
        }
    }.build()

    // ── Видеокамера выключена ─────────────────────────────────────────────────
    val VideoOff: ImageVector get() = ImageVector.Builder(
        name = "VideoOff",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Камера (упрощённый контур)
            moveTo(15f, 10f)
            lineTo(21f, 7f)
            lineTo(21f, 17f)
            moveTo(3.59f, 3.59f)
            lineTo(3f, 4.17f)
            lineTo(3f, 16f)
            curveTo(3f, 17.1f, 3.9f, 18f, 5f, 18f)
            lineTo(16f, 18f)
            lineTo(3.59f, 3.59f)
            moveTo(16f, 8.12f)
            lineTo(16f, 8f)
            curveTo(16f, 6.9f, 15.1f, 6f, 14f, 6f)
            lineTo(7.12f, 6f)
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round
        ) {
            // Перечёркивающая линия
            moveTo(1f, 1f)
            lineTo(23f, 23f)
        }
    }.build()

    // ── Перевернуть камеру ────────────────────────────────────────────────────
    val FlipCamera: ImageVector get() = ImageVector.Builder(
        name = "FlipCamera",
        defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Корпус камеры
            moveTo(20f, 7f)
            lineTo(4f, 7f)
            curveTo(2.9f, 7f, 2f, 7.9f, 2f, 9f)
            lineTo(2f, 18f)
            curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
            lineTo(20f, 20f)
            curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
            lineTo(22f, 9f)
            curveTo(22f, 7.9f, 21.1f, 7f, 20f, 7f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Линза
            moveTo(12f, 17f)
            curveTo(13.66f, 17f, 15f, 15.66f, 15f, 14f)
            curveTo(15f, 12.34f, 13.66f, 11f, 12f, 11f)
            curveTo(10.34f, 11f, 9f, 12.34f, 9f, 14f)
            curveTo(9f, 15.66f, 10.34f, 17f, 12f, 17f)
            close()
        }
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            // Стрелки переключения (вверху)
            moveTo(8f, 4f)
            lineTo(12f, 7f)
            lineTo(16f, 4f)
        }
    }.build()
}