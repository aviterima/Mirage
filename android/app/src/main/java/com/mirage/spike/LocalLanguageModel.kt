package com.mirage.spike

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A separately downloaded, checksum-pinned model. No user speech is sent to a model service. */
object LocalLanguageModel {
    const val FILE = "Qwen3-0.6B-Q8_0.gguf"
    const val HASH = "9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031"
    const val BYTES = 639446688L
    const val URL = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/23749fefcc72300e3a2ad315e1317431b06b590a/$FILE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val mutable = MutableStateFlow("Advanced voice not downloaded · 610 MB")
    val state = mutable.asStateFlow()
    private var file: File? = null
    private var downloading: Job? = null
    @Volatile private var downloadCall: okhttp3.Call? = null
    private var unload: Job? = null
    @Volatile private var loaded = false
    @Volatile var ready = false
        private set
    private external fun newNativeRequest(): Long
    private external fun generate(path: String, prompt: String, request: Long): String
    private external fun generateStructured(path: String, prompt: String, schema: String, request: Long): String
    private external fun cancelNative()
    private external fun releaseNative()
    fun configure(context: Context) {
        NaturalLanguageInput.configure(context)
        if (file != null) return
        file = File(context.filesDir, FILE)
        // Only a fully verified download is renamed to the final filename.
        ready = file!!.isFile && file!!.length() == BYTES
        if (ready) mutable.value = "Advanced voice ready · on this phone"
    }
    fun download() {
        if (downloading?.isCompleted == false || ready) return
        val dest = file ?: return
        downloading = scope.launch {
            val part = File(dest.path + ".part")
            try {
                check(dest.parentFile!!.usableSpace > BYTES + 128L * 1024 * 1024) { "Free at least 750 MB to download advanced voice" }
                mutable.value = "Downloading advanced voice…"
                val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
                val call = client.newCall(Request.Builder().url(URL).build()); downloadCall = call
                call.execute().use { response ->
                    check(response.isSuccessful) { "Download failed (${response.code})" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    response.body!!.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(128 * 1024); var total = 0L; var lastPercent = -1
                        while (true) {
                            ensureActive(); val n = input.read(buffer); if (n < 0) break
                            total += n; check(total <= BYTES) { "Unexpected model size" }
                            output.write(buffer,0,n); digest.update(buffer,0,n)
                            val percent = (total * 100 / BYTES).toInt()
                            if (percent != lastPercent) { mutable.value = "Downloading advanced voice · $percent%"; lastPercent = percent }
                        }
                        check(total == BYTES && digest.digest().joinToString("") { "%02x".format(it) } == HASH) { "Model verification failed; try downloading again" }
                    } }
                }
                check(part.renameTo(dest)) { "Could not finish saving model" }
                ready = true; mutable.value = "Advanced voice ready · on this phone"
            } catch (e: Exception) { part.delete(); mutable.value = "${e.message ?: "Download cancelled"}. Tap Download to retry." }
            finally { downloadCall = null }
        }
    }
    fun cancelDownload() { downloading?.cancel(); downloadCall?.cancel() }
    suspend fun interpret(prompt: String): String = infer(prompt, null)
    internal suspend fun interpretStructured(prompt: String, schema: String): String {
        require(schema == "field" || schema == "plan")
        return infer(prompt, schema)
    }
    private suspend fun infer(prompt: String, schema: String?): String = gate.withLock {
        check(ready) { "Download advanced voice in Talk to Mirage first" }
        unload?.cancel()
        try {
            val request = withContext(Dispatchers.IO) {
                if (!loaded) { System.loadLibrary("mirage_voice"); loaded = true }
                newNativeRequest()
            }
            // A cancelled queued caller never enters this block or cancels another field's work.
            // Keep the mutex until the native owner finishes, even when its UI caller disappears.
            val worker = scope.async {
                ensureActive()
                if (schema == null) generate(file!!.absolutePath, prompt, request)
                else generateStructured(file!!.absolutePath, prompt, schema, request)
            }
            try { worker.await() }
            catch (cancelled: CancellationException) {
                worker.cancel()
                cancelNative()
                withContext(NonCancellable) { worker.join() }
                throw cancelled
            }
        } finally {
            unload = scope.launch { delay(60_000); gate.withLock { if (loaded) releaseNative() } }
        }
    }
}
