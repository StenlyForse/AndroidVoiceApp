package com.voicecontrol.spotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.KeyEvent
import androidx.core.app.NotificationCompat

class VoiceCommandService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isActive = false

    companion object {
        const val CHANNEL_ID = "VoiceControlChannel"
        const val NOTIFICATION_ID = 1
        const val ACTION_STATUS_UPDATE = "com.voicecontrol.spotify.STATUS_UPDATE"
        const val EXTRA_STATUS_TEXT = "status_text"
        const val EXTRA_LAST_COMMAND = "last_command"
        const val EXTRA_IS_LISTENING = "is_listening"

        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Инициализация..."))
        isActive = true
        setupAndStartListening()
        return START_STICKY
    }

    private fun setupAndStartListening() {
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(createRecognitionListener())
        }
        startListening()
    }

    private fun startListening() {
        if (!isActive) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun createRecognitionListener() = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            broadcast("Слушаю...", "", true)
            updateNotification("Слушаю...")
        }

        override fun onBeginningOfSpeech() {
            broadcast("Распознаю...", "", true)
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            broadcast("Обрабатываю...", "", false)
        }

        override fun onError(error: Int) {
            val delay = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                    // Пересоздаём распознаватель при занятости
                    speechRecognizer?.destroy()
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this@VoiceCommandService).apply {
                        setRecognitionListener(createRecognitionListener())
                    }
                    1000L
                }
                else -> 500L
            }
            if (isActive) {
                mainHandler.postDelayed({ startListening() }, delay)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val command = matches?.firstOrNull() ?: ""
            if (command.isNotEmpty()) {
                processCommand(command)
            }
            if (isActive) {
                mainHandler.postDelayed({ startListening() }, 300L)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processCommand(rawCommand: String) {
        val cmd = rawCommand.lowercase().trim()
        when {
            cmd.containsAny(
                "следующий", "следующую", "дальше", "вперёд", "вперед",
                "next", "skip", "пропусти"
            ) -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
                broadcast("Следующий трек", rawCommand, false)
                updateNotification("Следующий трек")
            }

            cmd.containsAny(
                "предыдущий", "предыдущую", "назад", "прошлый", "прошлую",
                "previous", "back"
            ) -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                broadcast("Предыдущий трек", rawCommand, false)
                updateNotification("Предыдущий трек")
            }

            cmd.containsAny(
                "пауза", "стоп", "остановить", "остановись",
                "pause", "stop"
            ) -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Пауза", rawCommand, false)
                updateNotification("Пауза")
            }

            cmd.containsAny(
                "играть", "продолжить", "воспроизвести", "продолжи", "давай",
                "play", "resume"
            ) -> {
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                broadcast("Воспроизведение", rawCommand, false)
                updateNotification("Воспроизведение")
            }

            cmd.containsAny(
                "громче", "увеличь громкость", "добавь громкость", "прибавь",
                "louder", "volume up"
            ) -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                broadcast("Громче", rawCommand, false)
                updateNotification("Громкость увеличена")
            }

            cmd.containsAny(
                "тише", "убавь", "уменьши громкость", "потише",
                "quieter", "volume down", "lower"
            ) -> {
                repeat(2) {
                    audioManager.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                }
                broadcast("Тише", rawCommand, false)
                updateNotification("Громкость уменьшена")
            }
        }
    }

    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it) }

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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Голосовое управление",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновый сервис голосового управления Spotify"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
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
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        isActive = false
        isRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
