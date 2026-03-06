package com.example.meshlink.network

import android.app.Application
import android.media.MediaPlayer
import android.util.Log
import com.example.meshlink.core.MeshLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File


class AudioPlaybackManager(private val app: Application) {

    companion object {
        private const val TAG = "AudioPlayback"
    }

    private var player: MediaPlayer? = null

    private val _playingFile = MutableStateFlow<String?>(null)
    val playingFile: StateFlow<String?> = _playingFile

    val isPlaying: StateFlow<Boolean> get() = _isPlaying
    private val _isPlaying = MutableStateFlow(false)

    fun play(file: File) {
        val fileName = file.name

        if (_playingFile.value == fileName) {
            val currentPlayer = player
            if (currentPlayer != null && currentPlayer.isPlaying) {
                currentPlayer.pause()
                _isPlaying.value = false
                MeshLogger.аудиоВоспроизведение(fileName, "ПАУЗА")
                return
            } else if (currentPlayer != null) {
                currentPlayer.start()
                _isPlaying.value = true
                MeshLogger.аудиоВоспроизведение(fileName, "ПРОДОЛЖЕНИЕ")
                return
            }
        }


        stopInternal()

        if (!file.exists()) {
            Log.w(TAG, "Файл не найден: ${file.absolutePath}")
            return
        }

        MeshLogger.аудиоВоспроизведение(fileName, "СТАРТ")

        try {
            val newPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    _playingFile.value = null
                    _isPlaying.value = false
                    MeshLogger.аудиоВоспроизведение(fileName, "ЗАВЕРШЕНО")
                    release()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Ошибка воспроизведения: what=$what extra=$extra")
                    _playingFile.value = null
                    _isPlaying.value = false
                    true
                }
                start()
            }
            player = newPlayer
            _playingFile.value = fileName
            _isPlaying.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска воспроизведения: ${e.message}")
            _playingFile.value = null
            _isPlaying.value = false
        }
    }

    fun stop(fileName: String) {
        if (_playingFile.value == fileName) {
            stopInternal()
        }
    }

    private fun stopInternal() {
        try {
            player?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (_: Exception) {}
        player = null
        _playingFile.value = null
        _isPlaying.value = false
    }

    fun release() {
        stopInternal()
    }
}