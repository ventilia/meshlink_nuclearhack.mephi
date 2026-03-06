package com.example.meshlink.network

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder.AudioSource
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CallManager(private val context: Context) {
    companion object {
        private const val TAG = "CallManager"
        private const val SAMPLE_RATE = 44100
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 10
        private val BUFFER_SIZE =
            AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, ENCODING) * BUFFER_SIZE_FACTOR
    }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val audioTrackFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
        .setEncoding(ENCODING)
        .build()

    private val audioRecordFormat = AudioFormat.Builder()
        .setSampleRate(SAMPLE_RATE)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(ENCODING)
        .build()

    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var isSpeakerOn: Boolean = false
        private set

    fun hasAudioPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }


    fun playFragment(data: ByteArray) {
        if (audioTrack == null) {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioTrackFormat)
                .setBufferSizeInBytes(BUFFER_SIZE)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            audioTrack?.play()

            loudnessEnhancer = LoudnessEnhancer(audioTrack!!.audioSessionId).apply {
                setTargetGain(800)
                enabled = true
            }
        }
        audioTrack?.write(data, 0, data.size)
    }

    fun startRecording(onFragment: (ByteArray) -> Unit) {
        if (recordingJob?.isActive == true) return
        if (!hasAudioPermission(context)) {
            Log.w(TAG, "No RECORD_AUDIO permission")
            return
        }

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioSource(AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(audioRecordFormat)
                .setBufferSizeInBytes(BUFFER_SIZE)
                .build()

            val sessionId = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.enabled = true
            }

            audioRecord!!.startRecording()

            recordingJob = scope.launch {
                val buffer = ByteArray(BUFFER_SIZE)
                while (isActive) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (read > 0) {
                        onFragment(buffer.copyOf(read))
                    }
                }
            }
            Log.i(TAG, "Recording started")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException starting recording: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: ${e.message}")
        }
    }


    fun stopRecording() {
        scope.launch {
            recordingJob?.cancelAndJoin()
            recordingJob = null
        }
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.w(TAG, "stopRecording error: ${e.message}")
        }
        Log.i(TAG, "Recording stopped")
    }

    fun stopPlaying() {
        try {
            loudnessEnhancer?.release()
            loudnessEnhancer = null
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "stopPlaying error: ${e.message}")
        }
        Log.i(TAG, "Playing stopped")
    }

    fun toggleSpeaker() {
        isSpeakerOn = !isSpeakerOn
        // TODO: AudioManager setSpeakerphoneOn
    }
}