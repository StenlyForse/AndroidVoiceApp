package com.voicecontrol.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

class VoiceCommandService : Service() {

    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isActive = false

    private var audioRecord: AudioRecord? = null
    private var recognitionThread: Thread? = null
    private var voskModel: Model? = null

    companion object {
        const val CHANNEL_ID = "VoiceControlChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATUS_UPDATE = "com.voicecontrol.spotify.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_LAST_COMMAND = "last_command"
        const val EXTRA_IS_LISTENING = "is_listening"
        const val TAG = "VoiceApp"
        private const val SAMPLE_RATE = 16000

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))
        isActive = true

        if (ModelManager.isModelReady(this)) {
            loadModelAndStart()
        } else {
            downloadAndStart()
        }

        return START_STICKY
    }

    private fun downloadAndStart() {
        ModelManager.downloadAndExtract(
            context = this,
            onProgress = { msg ->
                mainHandler.post {
                    updateNotification(msg)
                    broadcast(msg, "", false)
                }
            },
            onComplete = {
                mainHandler.post { loadModelAndStart() }
            },
            onError = { error ->
                mainHandler.post {
                    updateNotification(error)
                    broadcast(error, "", false)
                }
            }
        )
    }

    private fun loadModelAndStart() {
        broadcast("Загружаю модель...", "", false)
        updateNotification("Загружаю модель...")

        Thread {
            try {
                val model = Model(ModelManager.getModelPath(this))
                voskModel = model
                Log.d(TAG, "Vosk model loaded successfully")
                mainHandler.post { startRecognitionLoop(model) }
            } catch (e: Exception) {
                Log.e(TAG, "Model load error: ${e.message}")
                val msg = "Ошибка загрузки модели: ${e.message}"
                mainHandler.post {
                    broadcast(msg, "", false)
                    updateNotification(msg)
                }
            }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun startRecognitionLoop(model: Model) {
        if (!isActive) return

        recognitionThread = Thread {
            val bufferSizeBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val buffer = ShortArray(bufferSizeBytes / 2)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes * 4
            )
            audioRecord = recorder

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                mainHandler.post {
                    val msg = "Не удалось открыть микрофон"
                    broadcast(msg, "", false)
                    updateNotification(msg)
                }
                return@Thread
            }

            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
            recorder.startRecording()
            Log.d(TAG, "Recording started")

            mainHandler.post {
                broadcast("Слушаю...", "", true)
                updateNotification("Слушаю...")
            }

            try {
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0 && recognizer.acceptWaveForm(buffer, read)) {
                        val text = JSONObject(recognizer.result).optString("text", "")
                        Log.d(TAG, "Recognized: '$text'")
                        if (text.isNotEmpty()) {
                            mainHandler.post {
                                processCommand(text)
                                mainHandler.postDelayed({
                                    if (isActive) broadcast("Слушаю...", "", true)
                                }, 600L)
                            }
                        }
                    }
                }
            } finally {
                recognizer.close()
                try {
                    recorder.stop()
                    recorder.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
                }
                audioRecord = null
                Log.d(TAG, "Recording stopped and released")
            }
        }
        recognitionThread?.start()
    }

    private fun processCommand(rawCommand: String) {
        val cmd = rawCommand.lowercase().trim()
        Log.d(TAG, "Processing: '$cmd'")
        when {
            cmd.containsAny("следующий", "следующую", "дальше", "вперёд", "вперед", "next", "skip", "пропусти") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                broadcast("Следующий трек", rawCommand, false)
                updateNotification("Следующий трек")
            }
            cmd.containsAny("предыдущий", "предыдущую", "назад", "прошлый", "прошлую", "previous", "back") -> {
                // Двойное нажатие: первое — начало трека, второе — предыдущий трек
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                mainHandler.postDelayed({ dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150L)
                broadcast("Предыдущий трек", rawCommand, false)
                updateNotification("Предыдущий трек")
            }
            cmd.containsAny("пауза", "стоп", "остановить", "остановись", "pause", "stop") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Пауза", rawCommand, false)
                updateNotification("Пауза")
            }
            cmd.containsAny("играть", "играй", "продолжить", "воспроизвести", "продолжай", "продолжи", "давай", "play", "resume") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Воспроизведение", rawCommand, false)
                updateNotification("Воспроизведение")
            }
            cmd.containsAny("громче", "увеличь", "прибавь", "louder", "volume up") -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                }
                broadcast("Громче", rawCommand, false)
                updateNotification("Громкость увеличена")
            }
            cmd.containsAny("тише", "убавь", "потише", "quieter", "volume down") -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                }
                broadcast("Тише", rawCommand, false)
                updateNotification("Громкость уменьшена")
            }
        }
    }

    private fun String.containsAny(vararg keywords: String) = keywords.any { this.contains(it) }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun broadcast(statusText: String, lastCommand: String, isListening: Boolean) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_LAST_COMMAND, lastCommand)
            putExtra(EXTRA_IS_LISTENING, isListening)
        })
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Голосовое управление", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Фоновый сервис голосового управления"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Голосовое управление")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        isActive = false
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        // AudioRecord останавливается в finally блоке потока распознавания
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
