// ==========================================
// ФАЙЛ: FileManager.kt
// ИСПРАВЛЕНИЯ:
// 1. Разделены перегрузки getFileBase64(String) и getFileBase64(File)
// 2. Добавлена поддержка чанков для передачи файлов
// 3. Улучшена обработка ошибок и логирование
// ==========================================
package com.example.meshlink.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

class FileManager(private val context: Context) {

    companion object {
        private const val TAG = "FileManager"
        const val CHUNK_SIZE = 8 * 1024 // 8 KB — баланс между overhead и эффективностью

        /**
         * Вычислить SHA-256 хэш данных в hex-формате
         */
        fun computeSha256(data: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(data)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }

    // ── Base64 утилиты ───────────────────────────────────────────────────

    /**
     * Получить Base64 строку из файла по имени
     */
    fun getFileBase64(fileName: String): String? {
        val file = File(context.filesDir, fileName)
        return getFileBase64(file)
    }

    /**
     * Получить Base64 строку из файла (внутренняя перегрузка)
     */
    @OptIn(ExperimentalEncodingApi::class)
    private fun getFileBase64(file: File): String? {
        return try {
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "File not readable: ${file.absolutePath}")
                return null
            }
            val bytes = FileInputStream(file).use { it.readBytes() }
            Base64.encode(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "getFileBase64 failed for ${file.name}: ${e.message}", e)
            null
        }
    }

    // ── Сохранение файлов ─────────────────────────────────────────────────

    private fun saveFile(inputFileUri: Uri, outputFileName: String): String? {
        val contentResolver: ContentResolver = context.contentResolver
        val inputStream: InputStream? = contentResolver.openInputStream(inputFileUri)
        val file = File(context.filesDir, outputFileName)
        return try {
            FileOutputStream(file).use { outputStream ->
                inputStream?.copyTo(outputStream)
            }
            file.name
        } catch (e: Exception) {
            Log.e(TAG, "saveFile failed: ${e.message}", e)
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
            Log.e(TAG, "saveFileFromBase64 failed: ${e.message}", e)
            null
        } finally {
            tempFile.delete()
        }
    }

    // ── Профиль и изображения ────────────────────────────────────────────

    fun saveProfileImage(imageUri: Uri, peerId: String): String? {
        return saveFile(imageUri, "profile_image_${peerId.take(8)}.jpg")
    }

    fun saveNetworkProfileImage(peerId: String, base64: String): String? {
        return saveFileFromBase64(base64, "profile_image_${peerId.take(8)}.jpg")
    }

    // ── Сообщения: файлы ─────────────────────────────────────────────────

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

    // ── Сообщения: аудио ─────────────────────────────────────────────────

    fun saveMessageAudio(tempFile: File, peerId: String, timestamp: Long): String? {
        val outputFileName = "audio_${peerId.take(8)}_${timestamp}.3gp"
        return try {
            val outputFile = File(context.filesDir, outputFileName)
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()
            outputFileName
        } catch (e: Exception) {
            Log.e(TAG, "saveMessageAudio failed: ${e.message}", e)
            null
        }
    }

    fun saveNetworkAudio(peerId: String, timestamp: Long, audioBase64: String): String? {
        return saveFileFromBase64(audioBase64, "audio_${peerId.take(8)}_${timestamp}.3gp")
    }

    // ── Chunked file transfer support ────────────────────────────────────

    /**
     * Разбить файл на чанки с вычислением хэшей
     * @return список пар (чанк_данные, хэш_чанка)
     */
    fun chunkFile(file: File, chunkSize: Int = CHUNK_SIZE): List<Pair<ByteArray, String>> {
        val chunks = mutableListOf<Pair<ByteArray, String>>()
        try {
            if (!file.exists() || !file.canRead()) {
                Log.w(TAG, "chunkFile: file not readable: ${file.absolutePath}")
                return emptyList()
            }
            FileInputStream(file).use { fis ->
                val buffer = ByteArray(chunkSize)
                var read: Int
                var index = 0
                while (fis.read(buffer).also { read = it } > 0) {
                    val chunk = if (read == chunkSize) {
                        buffer.copyOf()
                    } else {
                        buffer.copyOf(read)
                    }
                    val hash = computeSha256(chunk)
                    chunks.add(chunk to hash)
                    Log.d(TAG, "Chunked[$index]: ${chunk.size}B, hash=${hash.take(8)}...")
                    index++
                }
                Log.i(TAG, "chunkFile: total ${chunks.size} chunks for ${file.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "chunkFile failed: ${e.message}", e)
        }
        return chunks
    }

    /**
     * Сохранить чанк во временное хранилище для последующей сборки
     */
    fun saveChunkForAssembly(
        transferId: String,
        chunkIndex: Int,
        chunkData: ByteArray,
        expectedHash: String
    ): Boolean {
        return try {
            // Проверка целостности чанка
            val actualHash = computeSha256(chunkData)
            if (actualHash != expectedHash) {
                Log.w(TAG, "Chunk hash mismatch! idx=$chunkIndex expected=${expectedHash.take(8)}... got=${actualHash.take(8)}...")
                return false
            }

            val chunkDir = File(context.cacheDir, "file_chunks/$transferId").apply {
                if (!exists()) mkdirs()
            }
            val chunkFile = File(chunkDir, "chunk_${chunkIndex.toString().padStart(4, '0')}.dat")

            FileOutputStream(chunkFile).use { it.write(chunkData) }
            Log.d(TAG, "Saved chunk $chunkIndex for transfer $transferId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveChunkForAssembly failed: ${e.message}", e)
            false
        }
    }

    /**
     * Собрать файл из чанков и проверить целостность всего файла
     */
    fun assembleFile(
        transferId: String,
        outputFileName: String,
        expectedFileHash: String
    ): File? {
        return try {
            val chunkDir = File(context.cacheDir, "file_chunks/$transferId")
            if (!chunkDir.exists()) {
                Log.w(TAG, "Chunk directory not found: $chunkDir")
                return null
            }

            // Сортируем чанки по индексу (учитываем паддинг)
            val chunkFiles = chunkDir.listFiles { f -> f.name.startsWith("chunk_") }
                ?.sortedBy { f ->
                    f.name.substringAfter("chunk_").substringBefore(".").toIntOrNull() ?: 0
                }
                ?: emptyList()

            if (chunkFiles.isEmpty()) {
                Log.w(TAG, "No chunks found for transfer $transferId")
                return null
            }

            Log.i(TAG, "Assembling ${chunkFiles.size} chunks into $outputFileName")

            // Собираем файл и считаем хэш на лету
            val outputFile = File(context.filesDir, outputFileName)
            FileOutputStream(outputFile).use { fos ->
                val md = MessageDigest.getInstance("SHA-256")
                for (chunkFile in chunkFiles) {
                    FileInputStream(chunkFile).use { fis ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (fis.read(buffer).also { read = it } > 0) {
                            fos.write(buffer, 0, read)
                            md.update(buffer, 0, read)
                        }
                    }
                }

                // Финальная проверка хэша всего файла
                val actualHash = md.digest().joinToString("") { "%02x".format(it) }
                if (actualHash != expectedFileHash) {
                    Log.e(TAG, "File hash mismatch! expected=${expectedFileHash.take(16)}... got=${actualHash.take(16)}...")
                    outputFile.delete()
                    return null
                }
            }

            // Очищаем временные чанки только после успешной сборки
            chunkDir.deleteRecursively()

            Log.i(TAG, "✓ Assembled file: $outputFileName (${outputFile.length()} bytes), hash=${expectedFileHash.take(16)}...")
            outputFile
        } catch (e: Exception) {
            Log.e(TAG, "assembleFile failed: ${e.message}", e)
            null
        }
    }

    /**
     * Получить список уже полученных чанков для resume
     */
    fun getReceivedChunks(transferId: String): List<Int> {
        return try {
            val chunkDir = File(context.cacheDir, "file_chunks/$transferId")
            if (!chunkDir.exists()) return emptyList()

            chunkDir.listFiles { f -> f.name.startsWith("chunk_") }
                ?.mapNotNull { f ->
                    f.name.substringAfter("chunk_").substringBefore(".").toIntOrNull()
                }
                ?.sorted()
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "getReceivedChunks failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Очистить временные данные передачи
     */
    fun cleanupTransferChunks(transferId: String) {
        try {
            val chunkDir = File(context.cacheDir, "file_chunks/$transferId")
            if (chunkDir.exists()) {
                val count = chunkDir.listFiles()?.size ?: 0
                chunkDir.deleteRecursively()
                Log.d(TAG, "Cleaned up $count chunks for transfer $transferId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanupTransferChunks failed: ${e.message}")
        }
    }

    // ── Утилиты ─────────────────────────────────────────────────────────

    fun getFile(fileName: String): File = File(context.filesDir, fileName)

    fun getAudioTempFile(): File = File(context.filesDir, "audio_${System.currentTimeMillis()}.3gp")

    /**
     * Получить размер файла в байтах
     */
    fun getFileSize(fileName: String): Long {
        return try {
            getFile(fileName).length()
        } catch (e: Exception) {
            Log.w(TAG, "getFileSize failed: ${e.message}")
            0L
        }
    }

    /**
     * Проверить существование файла
     */
    fun fileExists(fileName: String): Boolean {
        return getFile(fileName).exists()
    }
}