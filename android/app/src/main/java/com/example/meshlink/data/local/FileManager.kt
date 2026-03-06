package com.example.meshlink.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FileManager(private val context: Context) {

    fun getAudioTempFile(): File = File(context.filesDir, "audio_${System.currentTimeMillis()}.3gp")

    @OptIn(ExperimentalEncodingApi::class)
    fun getFileBase64(file: File): String? {
        return try {
            val bytes = FileInputStream(file).use { it.readBytes() }
            Base64.encode(bytes)
        } catch (_: Exception) {
            null
        }
    }

    fun getFileBase64(fileName: String): String? = getFileBase64(File(context.filesDir, fileName))

    private fun saveFile(inputFileUri: Uri, outputFileName: String): String? {
        val contentResolver: ContentResolver = context.contentResolver
        val inputStream: InputStream? = contentResolver.openInputStream(inputFileUri)
        val file = File(context.filesDir, outputFileName)
        return try {
            FileOutputStream(file).use { outputStream ->
                inputStream?.copyTo(outputStream)
            }
            file.name
        } catch (_: Exception) {
            null
        } finally {
            inputStream?.close()
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun saveFileFromBase64(base64: String, outputFileName: String): String? {
        val tempFile = File.createTempFile("tmp", null, context.cacheDir).apply { deleteOnExit() }
        return try {
            FileOutputStream(tempFile).use { it.write(Base64.decode(base64)) }
            saveFile(Uri.fromFile(tempFile), outputFileName)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            tempFile.delete()
        }
    }

    fun saveProfileImage(imageUri: Uri, peerId: String): String? {
        return saveFile(imageUri, "profile_image_${peerId.take(8)}.jpg")
    }

    fun saveNetworkProfileImage(peerId: String, base64: String): String? {
        return saveFileFromBase64(base64, "profile_image_${peerId.take(8)}.jpg")
    }

    fun saveMessageFile(originalFileUri: Uri): String? {
        val contentResolver: ContentResolver = context.contentResolver
        val fileName: String? = contentResolver.query(
            originalFileUri, null, null, null, null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
        return fileName?.let { saveFile(originalFileUri, it) }
    }

    fun saveNetworkFile(fileName: String, fileBase64: String): String? {
        return saveFileFromBase64(fileBase64, fileName)
    }

    fun saveMessageAudio(tempFile: File, peerId: String, timestamp: Long): String? {
        val outputFileName = "audio_${peerId.take(8)}_${timestamp}.3gp"
        return try {
            val outputFile = File(context.filesDir, outputFileName)
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
            outputFileName
        } catch (_: Exception) {
            null
        }
    }

    fun saveNetworkAudio(peerId: String, timestamp: Long, audioBase64: String): String? {
        return saveFileFromBase64(audioBase64, "audio_${peerId.take(8)}_${timestamp}.3gp")
    }

    fun getFile(fileName: String): File = File(context.filesDir, fileName)
}