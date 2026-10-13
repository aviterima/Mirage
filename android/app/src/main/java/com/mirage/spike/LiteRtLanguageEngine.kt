package com.mirage.spike

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Local inference only. A new conversation prevents an earlier field or discarded plan from
 * becoming model history. JSON is still validated by Mirage before a review can be applied.
 */
internal object LiteRtLanguageEngine {
    private const val REQUEST_TIMEOUT_MS = 90_000L
    private const val IDLE_UNLOAD_MS = 60_000L
    private const val MAX_OUTPUT_CHARS = 64 * 1024
    private const val MAX_CONTEXT_TOKENS = 8192
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private var idleUnload: Job? = null

    suspend fun infer(
        modelFile: File,
        cacheDir: File,
        systemInstruction: String,
        userData: String,
        jsonSchema: String,
        maxOutputTokens: Int = 1200,
        thinkingTokenBudget: Int = 0
    ): String = gate.withLock {
        require(systemInstruction.isNotBlank() && systemInstruction.length <= 24_000) {
            "The language instructions are too large"
        }
        require(userData.isNotBlank() && userData.length <= 32_000) {
            "This request is too large; shorten the instruction or plan"
        }
        require(jsonSchema.isNotBlank() && jsonSchema.length <= 24_000)
        require(maxOutputTokens in 1..1200)
        require(thinkingTokenBudget in 0..256 && thinkingTokenBudget < maxOutputTokens)
        check(modelFile.isFile) { "Download the on-device language model first" }
        val responseFormat = ResponseFormat.json(jsonSchema)
        idleUnload?.cancel()
        val request = OwnedRequest()
        // This worker is intentionally independent of the UI Job. A caller's cancellation must
        // first cancel native inference and then wait for its terminal callback before unlocking.
        val worker = scope.async {
            val activeEngine = loadEngine(modelFile, cacheDir)
            request.checkNotCancelled()
            activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemInstruction),
                    tools = emptyList(),
                    automaticToolCalling = false,
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    maxOutputToken = maxOutputTokens,
                    // LiteRT counts reasoning and final-answer tokens together in maxOutputToken.
                    thinkingConfig = ThinkingConfig(
                        enableThinking = thinkingTokenBudget > 0,
                        thinkingTokenBudget = thinkingTokenBudget
                    ),
                    enableResponseFormat = true,
                    enableSpeculativeDecoding = false
                )
            ).use { conversation ->
                val result = request.generate(conversation, userData, responseFormat)
                // Native prefill rejects oversized input. Native decode reports a full context
                // as completion by default, so reject that result even if its JSON looks valid.
                check(conversation.getTokenCount() < MAX_CONTEXT_TOKENS) {
                    "This request filled the model context; shorten the instruction or plan"
                }
                result
            }
        }
        try {
            withTimeout(REQUEST_TIMEOUT_MS) { worker.await() }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + Dispatchers.IO) {
                request.cancel()
                worker.join()
            }
            if (cancelled is TimeoutCancellationException) {
                throw IllegalStateException("Understanding this request took too long; shorten it and try again")
            }
            throw cancelled
        } catch (_: Exception) {
            // Native errors can contain rendered input. Do not expose or record that content.
            throw IllegalStateException("The on-device model could not understand this request. Try a shorter instruction")
        } finally {
            idleUnload = scope.launch {
                delay(IDLE_UNLOAD_MS)
                gate.withLock { releaseEngine() }
            }
        }
    }

    /** Waits for any active owner before releasing its engine. */
    suspend fun unload() = gate.withLock {
        idleUnload?.cancel()
        idleUnload = null
        withContext(Dispatchers.IO) { releaseEngine() }
    }

    private fun loadEngine(modelFile: File, cacheDir: File): Engine {
        val path = modelFile.absolutePath
        engine?.takeIf { loadedPath == path && it.isInitialized() }?.let { return it }
        releaseEngine()
        check(cacheDir.isDirectory || cacheDir.mkdirs()) { "Could not prepare the local model cache" }
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val next = Engine(
            EngineConfig(
                modelPath = path,
                backend = Backend.CPU(threadCount = 4),
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDir.absolutePath
            )
        )
        try {
            next.initialize()
        } catch (failure: Throwable) {
            if (next.isInitialized()) next.close()
            throw failure
        }
        engine = next
        loadedPath = path
        return next
    }

    private fun releaseEngine() {
        val previous = engine
        engine = null
        loadedPath = null
        if (previous?.isInitialized() == true) previous.close()
    }

    private class OwnedRequest {
        private val lifecycle = NativeRequestLifecycle<String>()
        private val output = StringBuilder()
        private var invalidOutput = false

        fun checkNotCancelled() = lifecycle.checkNotCancelled()
        fun cancel() = lifecycle.cancel()

        suspend fun generate(active: Conversation, data: String, format: ResponseFormat): String {
            val callback = object : MessageCallback {
                override fun onMessage(message: Message) {
                    synchronized(output) {
                        if (message.toolCalls.isNotEmpty()) invalidOutput = true
                        for (content in message.contents.contents) {
                            if (content !is Content.Text) {
                                invalidOutput = true
                            } else if (output.length + content.text.length > MAX_OUTPUT_CHARS) {
                                invalidOutput = true
                            } else if (!invalidOutput) {
                                output.append(content.text)
                            }
                        }
                        // Deliberately ignore reasoning channels; they are neither returned nor saved.
                    }
                }

                override fun onDone() {
                    synchronized(output) {
                        if (invalidOutput || output.isBlank()) {
                            lifecycle.fail(IllegalStateException("Invalid model response"))
                        } else {
                            lifecycle.complete(output.toString())
                        }
                    }
                }

                override fun onError(throwable: Throwable) {
                    lifecycle.fail(throwable)
                }
            }
            lifecycle.start(cancelNative = { active.cancelProcess() }) {
                active.sendMessageAsync(Message.user(data), callback, responseFormat = format)
            }
            // Do not use the SDK Flow adapter: in the pinned version its awaitClose is empty,
            // so cancelling collection would not wait for the native generation to stop.
            return lifecycle.await()
        }
    }
}

/** Owns exactly one native request; cancellation cannot escape to a newer or queued request. */
internal class NativeRequestLifecycle<T> {
    private val registration = Any()
    private val terminal = CompletableDeferred<T>()
    private var cancelled = false
    private var started = false
    private var cancelNative: (() -> Unit)? = null

    fun checkNotCancelled(): Unit = synchronized(registration) {
        if (cancelled) throw CancellationException("Language request cancelled")
    }

    fun start(cancelNative: () -> Unit, registerNative: () -> Unit): Unit = synchronized(registration) {
        checkNotCancelled()
        check(!started) { "A native request can only be started once" }
        this.cancelNative = cancelNative
        registerNative()
        started = true
    }

    fun cancel(): Unit = synchronized(registration) {
        cancelled = true
        // Keep registration and cancellation atomic. Cancelling before JNI begins prevents send;
        // cancelling during registration waits until that request exists before stopping it.
        if (started && !terminal.isCompleted) runCatching { cancelNative?.invoke() }
        // Cancelling is not terminal: the native callback must confirm generation has stopped.
    }

    fun complete(value: T) { terminal.complete(value) }
    fun fail(failure: Throwable) { terminal.completeExceptionally(failure) }
    suspend fun await(): T = terminal.await()
}
