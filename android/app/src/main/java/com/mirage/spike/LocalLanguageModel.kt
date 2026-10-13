package com.mirage.spike

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** A separately downloaded, checksum-pinned model. No instruction is sent to a model service. */
object LocalLanguageModel {
    const val FILE = "gemma-4-E4B-it.litertlm"
    const val HASH = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
    const val BYTES = 3659530240L
    const val DOWNLOAD_SIZE = "3.7 GB"
    const val FREE_SPACE = "4 GB"
    const val URL = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/$FILE"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow("Advanced voice update needed · $DOWNLOAD_SIZE")
    val state = mutable.asStateFlow()
    private var file: File? = null
    private var cache: File? = null
    private var downloading: Job? = null
    @Volatile private var downloadCall: okhttp3.Call? = null
    @Volatile var ready = false
        private set

    fun configure(context: Context) {
        NaturalLanguageInput.configure(context)
        if (file != null) return
        file = File(context.noBackupFilesDir, FILE)
        cache = File(context.cacheDir, "language-model").apply { mkdirs() }
        // This private filename is installed only after size and SHA-256 verification.
        // An older Qwen download remains untouched and cannot satisfy the new model gate.
        ready = file!!.isFile && file!!.length() == BYTES
        if (ready) mutable.value = "Advanced voice ready · on this phone"
    }

    fun download() {
        if (downloading?.isCompleted == false || ready) return
        val dest = file ?: return
        downloading = scope.launch {
            try {
                mutable.value = "Downloading advanced voice…"
                val client = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).build()
                var lastPercent = -1
                VerifiedModelDownload(client, URL, BYTES, HASH).download(dest,
                    checkActive = { ensureActive() },
                    onCall = { downloadCall = it },
                    onProgress = { total ->
                        val percent = (total * 100 / BYTES).toInt()
                        if (percent != lastPercent) {
                            mutable.value = if (percent == 100) "Downloading advanced voice · verifying files…"
                                else "Downloading advanced voice · $percent%"
                            lastPercent = percent
                        }
                    })
                ready = true
                mutable.value = "Advanced voice ready · on this phone"
            } catch (cancelled: CancellationException) {
                mutable.value = "Download paused. Tap Resume download to continue."
                throw cancelled
            } catch (e: Exception) {
                mutable.value = "${e.message ?: "Download interrupted"}. Tap Resume download to retry."
            } finally { downloadCall = null }
        }
    }

    fun cancelDownload() { downloading?.cancel(); downloadCall?.cancel() }

    suspend fun interpret(systemInstruction: String, userData: String): String = infer(systemInstruction, userData, "legacy")

    internal suspend fun interpretStructured(systemInstruction: String, userData: String, schema: String): String {
        require(schema == "field" || schema == "plan")
        return infer(systemInstruction, userData, schema)
    }

    private suspend fun infer(systemInstruction: String, userData: String, schema: String): String {
        check(ready) { "Download the voice update in Voice setup first" }
        // Legacy commands do not need a reasoning phase. LiteRT-LM 0.18.0 also has a
        // known constrained-output error when an enabled reasoning phase is skipped.
        return LiteRtLanguageEngine.infer(file!!, cache!!, systemInstruction, userData,
            LanguageModelSchema.forSchema(schema), if (schema == "field") 500 else if (schema == "legacy") 220 else 1200,
            thinkingTokenBudget = if (schema == "legacy") 0 else 256)
    }
}
