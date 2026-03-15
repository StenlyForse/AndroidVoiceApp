package com.voicecontrol.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
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

    private enum class RecognitionState { NORMAL, WAITING_FOR_COMMAND }
    private var state = RecognitionState.NORMAL
    private var audioFocusRequest: AudioFocusRequest? = null

    private val wakeWordTimeout = Runnable { restoreFromWakeMode() }

    companion object {
        const val CHANNEL_ID = "VoiceControlChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATUS_UPDATE = "com.voicecontrol.spotify.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_LAST_COMMAND = "last_command"
        const val EXTRA_IS_LISTENING = "is_listening"
        const val EXTRA_IS_WAITING = "is_waiting"
        const val TAG = "VoiceApp"
        private const val SAMPLE_RATE = 16000
        private const val WAKE_WORD = "телефон"
        private const val WAKE_TIMEOUT_MS = 5000L

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
                            mainHandler.post { processCommand(text) }
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

    private fun processCommand(rawText: String) {
        val cmd = rawText.lowercase().trim()
        Log.d(TAG, "processCommand state=$state text='$cmd'")
        when (state) {
            RecognitionState.NORMAL -> {
                if (cmd.contains(WAKE_WORD)) {
                    activateWakeMode()
                } else if (executeCommand(cmd, rawText)) {
                    mainHandler.postDelayed({
                        if (isActive && state == RecognitionState.NORMAL) broadcast("Слушаю...", "", true)
                    }, 1500L)
                }
            }
            RecognitionState.WAITING_FOR_COMMAND -> {
                if (cmd.contains(WAKE_WORD)) return  // игнорируем повторное кодовое слово
                if (executeCommand(cmd, rawText)) restoreFromWakeMode()
                // команда не распознана — ждём таймаута
            }
        }
    }

    // Возвращает true если команда найдена и выполнена
    private fun executeCommand(cmd: String, rawText: String): Boolean {
        return when {
            cmd.containsAny("следующий", "следующую", "дальше", "вперёд", "вперед", "next", "skip", "пропусти") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                broadcast("Следующий трек", rawText, false)
                updateNotification("Следующий трек")
                true
            }
            cmd.containsAny("предыдущий", "предыдущую", "назад", "прошлый", "прошлую", "previous", "back") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                mainHandler.postDelayed({ dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, 150L)
                broadcast("Предыдущий трек", rawText, false)
                updateNotification("Предыдущий трек")
                true
            }
            cmd.containsAny("пауза", "стоп", "остановить", "остановись", "pause", "stop") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Пауза", rawText, false)
                updateNotification("Пауза")
                true
            }
            cmd.containsAny("играть", "играй", "продолжить", "воспроизвести", "продолжай", "продолжи", "давай", "play", "resume") -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Воспроизведение", rawText, false)
                updateNotification("Воспроизведение")
                true
            }
            cmd.containsAny("громче", "увеличь", "прибавь", "louder", "volume up") -> {
                repeat(2) { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI) }
                broadcast("Громче", rawText, false)
                updateNotification("Громкость увеличена")
                true
            }
            cmd.containsAny("тише", "убавь", "потише", "quieter", "volume down") -> {
                repeat(2) { audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI) }
                broadcast("Тише", rawText, false)
                updateNotification("Громкость уменьшена")
                true
            }
            else -> false
        }
    }

    private fun activateWakeMode() {
        state = RecognitionState.WAITING_FOR_COMMAND
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setWillPauseWhenDucked(false)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        Log.d(TAG, "Wake mode activated, AudioFocus result=$result")
        broadcast("Жду команду...", "", true, isWaiting = true)
        updateNotification("Жду команду...")
        mainHandler.removeCallbacks(wakeWordTimeout)
        mainHandler.postDelayed(wakeWordTimeout, WAKE_TIMEOUT_MS)
    }

    private fun restoreFromWakeMode() {
        state = RecognitionState.NORMAL
        mainHandler.removeCallbacks(wakeWordTimeout)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        Log.d(TAG, "Wake mode restored, AudioFocus abandoned")
        if (isActive) {
            broadcast("Слушаю...", "", true)
            updateNotification("Слушаю...")
        }
    }

    private fun String.containsAny(vararg keywords: String) = keywords.any { this.contains(it) }

    private fun dispatchMediaKey(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun broadcast(statusText: String, lastCommand: String, isListening: Boolean, isWaiting: Boolean = false) {
        sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
            putExtra(EXTRA_STATUS_TEXT, statusText)
            putExtra(EXTRA_LAST_COMMAND, lastCommand)
            putExtra(EXTRA_IS_LISTENING, isListening)
            putExtra(EXTRA_IS_WAITING, isWaiting)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        mainHandler.removeCallbacksAndMessages(null)
        // AudioRecord останавливается в finally блоке потока распознавания
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
