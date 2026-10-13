package com.mirage.spike

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.mirage.spike.engine.LiveSession
import com.mirage.spike.engine.PlaybackSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.util.concurrent.atomic.AtomicReference

/** Native bundled-model initialization only; no microphone recording or acoustic-quality claim. */
class OfflineSpeechNativeAcceptanceTest {
    @Test fun bundledVoskInitializesAndSilentPcmProducesNoWordsOrActions() = runBlocking {
        assertFalse(LocalLanguageModel.interpretationEnabled)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.stopService(Intent(context, VoiceService::class.java))
        val ready = CompletableDeferred<Unit>()
        val loaded = AtomicReference<Model?>(null)
        val beforePlayback = PlaybackSource.current
        val beforePlan = LiveSession.plan
        val beforeChat = Conversation.state.value
        try {
            // Same bundled asset and destination as VoiceService; this reads no remote model.
            StorageService.unpack(context, "model-en-us", "mirage-voice-model", { model ->
                loaded.set(model)
                if (!ready.complete(Unit)) loaded.getAndSet(null)?.close()
            }, { error -> ready.completeExceptionally(error) })
            withTimeout(60_000) { ready.await() }
            val recognizer = Recognizer(requireNotNull(loaded.get()), 16000f)
            try {
                val silence = ShortArray(16000)
                recognizer.acceptWaveForm(silence, silence.size)
                val result = JSONObject(recognizer.finalResult)
                assertTrue("Silence must not invent a command", result.getString("text").isBlank())
            } finally { recognizer.close() }
            assertSame(beforePlayback, PlaybackSource.current)
            assertSame(beforePlan, LiveSession.plan)
            assertEquals(beforeChat, Conversation.state.value)
            assertFalse(LocalLanguageModel.ready)
        } finally {
            ready.cancel()
            loaded.getAndSet(null)?.close()
        }
    }
}
