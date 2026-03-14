package com.voicecontrol.spotify

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ModelManager {

    private const val TAG = "VoiceApp"
    private const val MODEL_NAME = "vosk-model-small-ru-0.22"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"

    fun getModelPath(context: Context): String =
        File(context.filesDir, MODEL_NAME).absolutePath

    fun isModelReady(context: Context): Boolean =
        File(getModelPath(context)).exists()

    fun downloadAndExtract(
        context: Context,
        onProgress: (String) -> Unit,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) {
        Thread {
            val zipFile = File(context.filesDir, "model.zip")
            try {
                onProgress("Скачиваю языковую модель (~45 МБ)...")
                Log.d(TAG, "Downloading model from $MODEL_URL")

                val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.connect()

                val totalBytes = connection.contentLength.toLong()

                FileOutputStream(zipFile).use { output ->
                    BufferedInputStream(connection.inputStream).use { input ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val percent = (downloaded * 100 / totalBytes).toInt()
                                val mb = downloaded / 1_048_576
                                onProgress("Скачиваю: $percent% (${mb} МБ)")
                            }
                        }
                    }
                }
                connection.disconnect()
                Log.d(TAG, "Download complete, extracting...")

                onProgress("Распаковываю модель...")
                extractZip(zipFile, context.filesDir)
                zipFile.delete()

                Log.d(TAG, "Model ready at ${getModelPath(context)}")
                onComplete()
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}")
                zipFile.delete()
                onError("Ошибка загрузки модели: ${e.message}")
            }
        }.start()
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (zip.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
}
