package com.mirage.spike

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
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
    private var dictationId: Long? = null
    private var fieldId: Long? = null
    private val dictationWords = DictationTranscript()
    private var audioFocus: AudioFocusRequest? = null
    private var loading = false
    private var destroyed = false
    private var timeout: Job? = null
    private var preparationTimeout: Job? = null
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
        scope.launch { PlannerSpeech.state.collect { request ->
            if (request.requestId == dictationId && request.active && request.finishRequested) completeDictation()
        } }
        scope.launch { FieldSpeech.state.collect { request ->
            if (request.requestId == fieldId && request.active && request.finishRequested) completeDictation()
        } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == OFF) { completeDictation(interrupted = true); stopSelf(); return START_NOT_STICKY }
        if (!LocalLanguageModel.interpretationEnabled && intent.action != DICTATE && intent.action != FIELD_DICTATE) {
            completeDictation(interrupted = true)
            handsFree = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (isDictating()) completeDictation(interrupted = true, stop = false)
        dictationId = if (intent.action == DICTATE) intent.getLongExtra(EXTRA_PLANNER_REQUEST_ID, -1L) else null
        fieldId = if (intent.action == FIELD_DICTATE) intent.getLongExtra(EXTRA_FIELD_REQUEST_ID, -1L) else null
        dictationWords.clear()
        fieldId?.let { id -> FieldSpeech.state.value.takeIf { it.requestId == id }?.prefix?.let(dictationWords::segment) }
        if (dictationId?.let { !PlannerSpeech.isActive(it) } == true || fieldId?.let { !FieldSpeech.isActive(it) } == true) {
            stopSelf(); return START_NOT_STICKY
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail("Microphone permission is required"); return START_NOT_STICKY
        }
        handsFree = intent.action == ENABLE || (handsFree && intent.action == LISTEN)
        if (isDictating() && ((dictationId != null && PlannerSpeech.state.value.finishRequested) ||
                (fieldId != null && FieldSpeech.state.value.finishRequested))) {
            completeDictation(); return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("mirage_voice", "Mirage hands-free", NotificationManager.IMPORTANCE_LOW))
        val off = PendingIntent.getService(this, 60, Intent(this, VoiceService::class.java).setAction(OFF), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 61, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(this, "mirage_voice").setSmallIcon(R.drawable.ic_stat_mirage)
            .setContentTitle("Mirage microphone is on").setContentText(if (handsFree) "Say Hello Mirage, then wait for the chime" else if (isDictating()) "Speak, then choose Done speaking" else "Listening for one instruction")
            .setContentIntent(open).setOngoing(true).addAction(0, "Microphone off", off).build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(63, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            else startForeground(63, note)
        } catch (_: Exception) { fail("Could not enable the microphone. Your entry is kept."); return START_NOT_STICKY }
        val managerAudio = getSystemService(AudioManager::class.java)
        audioFocus?.let(managerAudio::abandonAudioFocusRequest)
        audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                    if (isDictating()) completeDictation(interrupted = true) else stopSelf()
                }
            }.build()
        if (managerAudio.requestAudioFocus(audioFocus!!) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            fail("Microphone interrupted by another audio session. Your entry is kept."); return START_NOT_STICKY
        }
        VoiceState.mutable.value = VoiceView(enabled = true, status = "Preparing offline voice…")
        if (model == null) {
            preparationTimeout?.cancel()
            preparationTimeout = scope.launch {
                delay(60_000)
                if (!destroyed && model == null) fail("Voice preparation took too long. Your entry is kept; try again.")
            }
            if (!loading) {
                loading = true
                try {
                    StorageService.unpack(this, "model-en-us", "mirage-voice-model", { m ->
                        loading = false
                        preparationTimeout?.cancel()
                        if (destroyed) m.close() else { model = m; startListening(command = !handsFree) }
                    }, { _ ->
                        loading = false
                        preparationTimeout?.cancel()
                        if (!destroyed) fail("Offline voice model unavailable. Your entry is kept; try again.")
                    })
                } catch (_: Exception) {
                    loading = false
                    fail("Offline voice model unavailable. Your entry is kept; try again.")
                }
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
        if (!canListen()) { stopSelf(); return }
        timeout?.cancel(); transition?.cancel(); speechFallback?.cancel(); tts?.stop(); releaseRecorder()
        val m = model ?: return
        try {
            val rec = if (command) Recognizer(m, 16000f) else Recognizer(m, 16000f, "[\"hello mirage\", \"[unk]\"]")
            recognizer = rec
            val service = SpeechService(rec, 16000f); recorder = service
            val gen = generation
            val listener = object : RecognitionListener {
                private fun valid() = !destroyed && gen == generation &&
                    (dictationId?.let { PlannerSpeech.isActive(it) } != false) && (fieldId?.let { FieldSpeech.isActive(it) } != false)
                override fun onPartialResult(h: String) {
                    if (!valid()) return
                    val text = JSONObject(h).optString("partial")
                    if (command) {
                        if (isDictating()) publishDictation(dictationWords.partial(text))
                        else VoiceState.mutable.value = VoiceState.mutable.value.copy(transcript = text)
                    }
                    else if (text.trim() == "hello mirage") startListening(true)
                }
                override fun onResult(h: String) {
                    if (!valid()) return
                    val text = JSONObject(h).optString("text").trim()
                    if (command && text.isNotBlank()) {
                        if (isDictating()) {
                            publishDictation(dictationWords.segment(text))
                            return
                        }
                        // Old service intents and delayed callbacks must not execute trip commands
                        // in the dictation-only build, even if its language model is present.
                        if (!LocalLanguageModel.interpretationEnabled) { stopSelf(); return }
                        timeout?.cancel(); releaseRecorder()
                        VoiceState.mutable.value = VoiceView(true, false, "Working…", text)
                        Conversation.submit(text, spoken = true)
                        // A network lookup can take longer; re-arm only after the spoken result.
                        speechFallback?.cancel()
                        speechFallback = scope.launch { delay(45000); finishSpeech() }
                    } else if (!command && text == "hello mirage") startListening(true)
                }
                override fun onFinalResult(h: String) { if (valid() && isDictating()) publishDictation(dictationWords.segment(JSONObject(h).optString("text"))) }
                override fun onError(e: Exception) { if (valid()) fail("Microphone unavailable. Your captured words are kept; try again.") }
                override fun onTimeout() { if (valid()) endCommandWindow() }
            }
            if (command) {
                VoiceState.mutable.value = VoiceView(true, false, "Ready for your instruction…")
                transition = scope.launch {
                    try {
                        tone?.startTone(ToneGenerator.TONE_DTMF_1, 100); delay(130)
                        tone?.startTone(ToneGenerator.TONE_DTMF_3, 120); delay(150)
                        if (gen != generation || destroyed) return@launch
                        if (!canListen()) { stopSelf(); return@launch }
                        check(service.startListening(listener))
                        VoiceState.mutable.value = VoiceView(true, true, "Listening…")
                        timeout = scope.launch { delay(if (isDictating()) 90000 else 15000); endCommandWindow() }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { fail("Microphone unavailable. Your captured words are kept; try again.") }
                }
            } else {
                VoiceState.mutable.value = VoiceView(true, false, "Say Hello Mirage")
                check(service.startListening(listener))
            }
        } catch (_: Exception) { fail("Could not start listening. Your entry is kept; try again.") }
    }
    private fun isDictating() = dictationId != null || fieldId != null
    private fun canListen(): Boolean = if (isDictating()) {
        dictationId?.let(PlannerSpeech::isActive) ?: fieldId?.let(FieldSpeech::isActive) ?: false
    } else LocalLanguageModel.interpretationEnabled
    private fun publishDictation(text: String) {
        dictationId?.let { PlannerSpeech.partial(it, text) }
        fieldId?.let { FieldSpeech.partial(it, text) }
        val hidden = fieldId != null && FieldSpeech.state.value.sensitive
        VoiceState.mutable.value = VoiceState.mutable.value.copy(transcript = if (hidden) "" else text)
    }
    private fun completeDictation(interrupted: Boolean = false, stop: Boolean = true) {
        if (!isDictating()) return
        val text = dictationWords.text().ifBlank {
            fieldId?.let { FieldSpeech.state.value.takeIf { view -> view.requestId == it }?.transcript }
                ?: dictationId?.let { PlannerSpeech.state.value.takeIf { view -> view.requestId == it }?.transcript }.orEmpty()
        }
        dictationId?.let { if (interrupted) PlannerSpeech.interrupt(it, text) else PlannerSpeech.finish(it, text) }
        fieldId?.let { if (interrupted) FieldSpeech.interrupt(it, text) else FieldSpeech.finish(it, text) }
        preparationTimeout?.cancel(); timeout?.cancel(); transition?.cancel(); releaseRecorder()
        if (stop) stopSelf()
    }
    private fun speak(text: String) {
        if (destroyed || isDictating() || !LocalLanguageModel.interpretationEnabled) return
        timeout?.cancel(); speechFallback?.cancel(); releaseRecorder()
        VoiceState.mutable.value = VoiceState.mutable.value.copy(listening = false, status = text)
        if (ttsReady) {
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mirage-${System.nanoTime()}")
            if (result == TextToSpeech.ERROR) finishSpeech()
            else speechFallback = scope.launch { delay(30000); finishSpeech() }
        } else finishSpeech()
    }
    private fun finishSpeech() {
        if (destroyed || isDictating()) return
        if (!LocalLanguageModel.interpretationEnabled) { stopSelf(); return }
        timeout?.cancel(); speechFallback?.cancel()
        if (handsFree) startListening(Conversation.needsReply()) else stopSelf()
    }
    private fun endCommandWindow() {
        if (destroyed) return
        if (isDictating()) { completeDictation(); return }
        if (!LocalLanguageModel.interpretationEnabled) { stopSelf(); return }
        if (handsFree) startListening(false) else stopSelf()
    }
    private fun fail(message: String) {
        preparationTimeout?.cancel()
        if (isDictating()) completeDictation(interrupted = true) else Conversation.notice(message)
        VoiceState.mutable.value = VoiceView(status = message)
        stopSelf()
    }
    override fun onDestroy() {
        completeDictation(interrupted = true, stop = false)
        destroyed = true; releaseRecorder(); scope.cancel(); tts?.stop(); tts?.shutdown(); model?.close(); tone?.release()
        audioFocus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }
        VoiceState.mutable.value = VoiceView()
        super.onDestroy()
    }
    companion object {
        const val ENABLE = "com.mirage.voice.ENABLE"
        const val LISTEN = "com.mirage.voice.LISTEN"
        const val DICTATE = "com.mirage.voice.DICTATE"
        const val EXTRA_PLANNER_REQUEST_ID = "planner_request_id"
        const val FIELD_DICTATE = "com.mirage.voice.FIELD_DICTATE"
        const val EXTRA_FIELD_REQUEST_ID = "field_request_id"
        const val OFF = "com.mirage.voice.OFF"
    }
}
