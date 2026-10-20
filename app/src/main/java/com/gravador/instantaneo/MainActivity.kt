package com.gravador.instantaneo

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.*
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// --- SERVIÇO DE GRAVAÇÃO ---
class AudioRecordService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var timerJob: Job? = null

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_TOGGLE = "ACTION_TOGGLE"
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "record_channel"
        private val _isRecording = MutableStateFlow(false)
        val isRecording = _isRecording.asStateFlow()
        private val _duration = MutableStateFlow(0L)
        val duration = _duration.asStateFlow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Gravação Ativa", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecord()
            ACTION_STOP -> stopRecord()
            ACTION_TOGGLE -> if (_isRecording.value) stopRecord() else startRecord()
        }
        return START_NOT_STICKY
    }

    private fun startRecord() {
        if (_isRecording.value) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Gravador:Lock").apply { acquire(3 * 60 * 60 * 1000L) }

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val dir = getExternalFilesDir(null) ?: filesDir
        currentFile = File(dir, "REC_$timeStamp.m4a")

        try {
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(currentFile!!.absolutePath)
                prepare()
                start()
            }
            _isRecording.value = true
            vibrate(150)
            startForeground(NOTIFICATION_ID, makeNotification(0L))
            timerJob = scope.launch {
                var sec = 0L
                while (_isRecording.value) {
                    delay(1000)
                    sec++
                    _duration.value = sec
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, makeNotification(sec))
                }
            }
        } catch (e: Exception) {
            stopRecord()
        }
    }

    private fun stopRecord() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {
            currentFile?.delete()
        } finally {
            mediaRecorder = null
            _isRecording.value = false
            timerJob?.cancel()
            _duration.value = 0L
            if (wakeLock?.isHeld == true) wakeLock?.release()
            vibrate(100)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun makeNotification(sec: Long): Notification {
        val stopIntent = Intent(this, AudioRecordService::class.java).apply { action = ACTION_STOP }
        val pi = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val time = String.format("%02d:%02d", sec / 60, sec % 60)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🔴 Gravando...")
            .setContentText("Duração: $time | Toque para Parar")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "PARAR", pi)
            .build()
    }

    private fun vibrate(ms: Long) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}

// --- SERVIÇO DE CAPTURA DO BOTÃO DE VOLUME ---
class VolumeAccessibilityService : AccessibilityService() {
    private var lastClick = 0L
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val now = System.currentTimeMillis()
            if (now - lastClick <= 600) {
                lastClick = 0L
                val intent = Intent(this, AudioRecordService::class.java).apply { action = AudioRecordService.ACTION_TOGGLE }
                startForegroundService(intent)
                return true
            }
            lastClick = now
        }
        return super.onKeyEvent(event)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
}

// --- TELA DO APLICATIVO ---
class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ctx = LocalContext.current
                val isRec by AudioRecordService.isRecording.collectAsState()
                val sec by AudioRecordService.duration.collectAsState()

                val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
                LaunchedEffect(Unit) {
                    val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
                    permLauncher.launch(p.toTypedArray())
                }

                fun listFiles(): List<File> {
                    val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                    return dir.listFiles { f -> f.extension == "m4a" }?.sortedByDescending { it.lastModified() } ?: emptyList()
                }

                var files by remember { mutableStateOf(listFiles()) }

                Scaffold(
                    topBar = { TopAppBar(title = { Text("Gravador Instantâneo") }) }
                ) { pad ->
                    Column(
                        modifier = Modifier.fillMaxSize().padding(pad).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("Atalho: 2 cliques no Volume Menos (-)", fontSize = 14.sp)
                                Spacer(modifier = Modifier.height(4.dp))
                                Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                    Text("Ativar Acessibilidade")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text(String.format("%02d:%02d", sec / 60, sec % 60), fontSize = 48.sp, color = if (isRec) Color.Red else Color.Gray)
                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                val intent = Intent(ctx, AudioRecordService::class.java).apply {
                                    action = if (isRec) AudioRecordService.ACTION_STOP else AudioRecordService.ACTION_START
                                }
                                ctx.startForegroundService(intent)
                                files = listFiles()
                            },
                            modifier = Modifier.fillMaxWidth().height(60.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isRec) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(if (isRec) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isRec) "PARAR GRAVAÇÃO" else "INICIAR GRAVAÇÃO")
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Text("Gravações salvas:", modifier = Modifier.align(Alignment.Start))

                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(files) { f ->
                                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(f.name, modifier = Modifier.weight(1f), fontSize = 13.sp)
                                        IconButton(onClick = { f.delete(); files = listFiles() }) {
                                            Icon(Icons.Default.Delete, "Excluir")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}