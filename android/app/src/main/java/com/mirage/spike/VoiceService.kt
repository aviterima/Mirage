package com.mirage.spike

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class VoiceView(val enabled: Boolean = false, val listening: Boolean = false, val status: String = "Microphone off", val transcript: String = "")
object VoiceState {
    internal val mutable = MutableStateFlow(VoiceView())
    val state = mutable.asStateFlow()
}

/** Offline wake grammar; full local transcription runs only during a command window. */
class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var recorder: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var generation = 0
    private var handsFree = false
    private var loading = false
    private var destroyed = false
    private var timeout: Job? = null
    private var transition: Job? = null
    private var speechFallback: Job? = null
    private var tone: ToneGenerator? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 75) }.getOrNull()
        tts = TextToSpeech(this) { result -> ttsReady = result == TextToSpeech.SUCCESS; if (ttsReady) tts?.language = Locale.US }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { scope.launch { finishSpeech() } }
            @Deprecated("Android callback") override fun onError(id: String?) { scope.launch { finishSpeech() } }
        })
        scope.launch { Conversation.speech.collect { speak(it) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == OFF) { stopSelf(); return START_NOT_STICKY }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required"); return START_NOT_STICKY
        }
        handsFree = intent.action == ENABLE || (handsFree && intent.action == LISTEN)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mirage_voice", "Mirage hands-free", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(this, 60, Intent(this, VoiceService::class.java).setAction(OFF), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 61, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, "mirage_voice").setSmallIcon(R.drawable.ic_stat_mirage)
            .setContentTitle("Mirage microphone is on").setContentText(if (handsFree) "Say Hello Mirage, then wait for the chime" else "Listening for one instruction")
            .setContentIntent(open).setOngoing(true).addAction(0, "Microphone off", off).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(63, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(63, note)
        } catch (e: Exception) { fail("Could not enable voice: ${e.message}"); return START_NOT_STICKY }
        VoiceState.mutable.value = VoiceView(enabled = true, status = "Preparing offline voice…")
        if (model == null) {
            if (!loading) {
                loading = true
                StorageService.unpack(this, "model-en-us", "mirage-voice-model", { m ->
                    loading = false
                    if (destroyed) m.close() else { model = m; startListening(command = !handsFree) }
                }, { e -> if (!destroyed) fail("Offline voice model unavailable: ${e.message}") })
            }
        } else startListening(command = intent.action == LISTEN || !handsFree)
        return START_NOT_STICKY
    }
    private fun releaseRecorder() {
        generation++
        recorder?.cancel(); recorder?.shutdown(); recorder = null
        recognizer?.close(); recognizer = null
    }
    private fun startListening(command: Boolean) {
        if (destroyed) return
        timeout?.cancel(); transition?.cancel(); releaseRecorder()
        val m = model ?: return
        try {
            val rec = if (command) Recognizer(m, 16000f) else Recognizer(m, 16000f, "[\"hello mirage\", \"[unk]\"]")
            recognizer = rec
            val service = SpeechService(rec, 16000f); recorder = service
            val gen = generation
            val listener = object : RecognitionListener {
                private fun valid() = !destroyed && gen == generation
                override fun onPartialResult(h: String) {
                    if (!valid()) return
                    val text = JSONObject(h).optString("partial")
                    if (command) VoiceState.mutable.value = VoiceState.mutable.value.copy(transcript = text)
                    else if (text.trim() == "hello mirage") startListening(true)
                }
                override fun onResult(h: String) {
                    if (!valid()) return
                    val text = JSONObject(h).optString("text").trim()
                    if (command && text.isNotBlank()) {
                        timeout?.cancel(); releaseRecorder()
                        VoiceState.mutable.value = VoiceView(true, false, "Working…", text)
                        Conversation.submit(text, spoken = true)
                        // A network lookup can take longer; re-arm only after the spoken result.
                        speechFallback?.cancel()
                        speechFallback = scope.launch { delay(45000); finishSpeech() }
                    } else if (!command && text == "hello mirage") startListening(true)
                }
                override fun onFinalResult(h: String) {}
                override fun onError(e: Exception) { if (valid()) fail("Microphone unavailable: ${e.message}") }
                override fun onTimeout() { if (valid()) endCommandWindow() }
            }
            if (command) {
                VoiceState.mutable.value = VoiceView(true, false, "Ready for your instruction…")
                transition = scope.launch {
                    tone?.startTone(ToneGenerator.TONE_DTMF_1, 100); delay(130)
                    tone?.startTone(ToneGenerator.TONE_DTMF_3, 120); delay(150)
                    if (gen != generation || destroyed) return@launch
                    VoiceState.mutable.value = VoiceView(true, true, "Listening…")
                    service.startListening(listener)
                    timeout = scope.launch { delay(15000); endCommandWindow() }
                }
            } else {
                VoiceState.mutable.value = VoiceView(true, false, "Say Hello Mirage")
                service.startListening(listener)
            }
        } catch (e: Exception) { fail("Could not start listening: ${e.message}") }
    }
    private fun speak(text: String) {
        if (destroyed) return
        timeout?.cancel(); speechFallback?.cancel(); releaseRecorder()
        VoiceState.mutable.value = VoiceState.mutable.value.copy(listening = false, status = text)
        if (ttsReady) {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mirage-${System.nanoTime()}")
            if (result == TextToSpeech.ERROR) finishSpeech()
            else speechFallback = scope.launch { delay(30000); finishSpeech() }
        } else finishSpeech()
    }
    private fun finishSpeech() {
        if (destroyed) return
        timeout?.cancel(); speechFallback?.cancel()
        if (handsFree) startListening(Conversation.needsReply()) else stopSelf()
    }
    private fun endCommandWindow() {
        if (destroyed) return
        if (handsFree) startListening(false) else stopSelf()
    }
    private fun fail(message: String) {
        Conversation.notice(message)
        VoiceState.mutable.value = VoiceView(status = message)
        stopSelf()
    }
    override fun onDestroy() {
        destroyed = true; releaseRecorder(); scope.cancel(); tts?.stop(); tts?.shutdown(); model?.close(); tone?.release()
        VoiceState.mutable.value = VoiceView()
        super.onDestroy()
    }
    companion object {
        const val ENABLE = "com.mirage.voice.ENABLE"
        const val LISTEN = "com.mirage.voice.LISTEN"
        const val OFF = "com.mirage.voice.OFF"
    }
}
