package com.voicecontrol.spotify

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.ImageView
import android.widget.TextView
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var ivMic: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvLastCommand: TextView
    private lateinit var fabToggle: FloatingActionButton

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("VoiceApp", "Broadcast received in activity")
            val statusText = intent?.getStringExtra(VoiceCommandService.EXTRA_STATUS_TEXT) ?: return
            val lastCommand = intent.getStringExtra(VoiceCommandService.EXTRA_LAST_COMMAND) ?: ""
            val isListening = intent.getBooleanExtra(VoiceCommandService.EXTRA_IS_LISTENING, false)

            tvStatus.text = statusText

            if (lastCommand.isNotEmpty()) {
                tvLastCommand.text = "«$lastCommand»"
            }

            if (isListening) {
                ivMic.setColorFilter(getColor(R.color.spotify_green))
                if (ivMic.animation == null) {
                    ivMic.startAnimation(AnimationUtils.loadAnimation(this@MainActivity, R.anim.pulse))
                }
            } else {
                ivMic.clearAnimation()
                ivMic.setColorFilter(getColor(R.color.text_secondary))
            }
        }
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        ivMic = findViewById(R.id.ivMic)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        tvLastCommand = findViewById(R.id.tvLastCommand)
        fabToggle = findViewById(R.id.fabToggle)

        fabToggle.setOnClickListener {
            if (VoiceCommandService.isRunning) stopVoiceService() else checkPermissionsAndStart()
        }

        findViewById<ImageButton>(R.id.btnHelp).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(getString(R.string.help_message))
                .setPositiveButton(R.string.help_close, null)
                .show()
        }

        updateUi()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(VoiceCommandService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        updateUi()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun checkPermissionsAndStart() {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.RECORD_AUDIO)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            startVoiceService()
        } else {
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val micGranted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                startVoiceService()
            } else {
                Toast.makeText(
                    this,
                    "Для работы приложения необходим доступ к микрофону",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startVoiceService() {
        val intent = Intent(this, VoiceCommandService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        // Сервис стартует асинхронно — обновляем UI через 300мс когда он точно запустился
        Handler(Looper.getMainLooper()).postDelayed({ updateUi() }, 300)
    }

    private fun stopVoiceService() {
        stopService(Intent(this, VoiceCommandService::class.java))
        // Обновляем UI напрямую, не ждём isRunning (он меняется асинхронно)
        fabToggle.setImageResource(R.drawable.ic_mic)
        fabToggle.backgroundTintList = getColorStateList(R.color.spotify_green)
        tvHint.text = getString(R.string.hint_stopped)
        tvStatus.text = getString(R.string.status_stopped)
        tvLastCommand.text = ""
        ivMic.clearAnimation()
        ivMic.setColorFilter(getColor(R.color.text_secondary))
    }

    private fun updateUi() {
        if (VoiceCommandService.isRunning) {
            fabToggle.setImageResource(R.drawable.ic_stop)
            fabToggle.backgroundTintList = getColorStateList(R.color.error_red)
            tvHint.text = getString(R.string.hint_running)
            if (tvStatus.text == getString(R.string.status_stopped)) {
                tvStatus.text = getString(R.string.status_starting)
            }
        } else {
            fabToggle.setImageResource(R.drawable.ic_mic)
            fabToggle.backgroundTintList = getColorStateList(R.color.spotify_green)
            tvHint.text = getString(R.string.hint_stopped)
            tvStatus.text = getString(R.string.status_stopped)
            ivMic.setColorFilter(getColor(R.color.text_secondary))
            ivMic.clearAnimation()
        }
    }
}
