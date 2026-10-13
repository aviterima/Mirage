package com.mirage.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Recording state contains no operation that can save a record or execute a command. */
data class FieldSpeechView(
    val requestId: Long = 0,
    val key: String? = null,
    val transcript: String = "",
    val active: Boolean = false,
    val complete: Boolean = false,
    val interrupted: Boolean = false,
    val finishRequested: Boolean = false,
    val sensitive: Boolean = false,
    val prefix: String = "",
)

object FieldSpeech {
    private val mutable = MutableStateFlow(FieldSpeechView())
    val state = mutable.asStateFlow()
    private val completed = MutableStateFlow<Map<String, FieldSpeechView>>(emptyMap())
    val results = completed.asStateFlow()

    @Synchronized fun begin(key: String, sensitive: Boolean = false, prefix: String = ""): Long {
        require(key.isNotBlank())
        if (mutable.value.active) interrupt(mutable.value.requestId)
        val id = mutable.value.requestId + 1
        mutable.value = FieldSpeechView(requestId = id, key = key, transcript = prefix,
            active = true, sensitive = sensitive, prefix = prefix)
        return id
    }
    @Synchronized fun partial(id: Long, text: String) {
        val current = mutable.value
        if (current.requestId == id && current.active) mutable.value = current.copy(transcript = text)
    }
    @Synchronized fun requestFinish(id: Long) {
        val current = mutable.value
        if (current.requestId == id && current.active) mutable.value = current.copy(finishRequested = true)
    }
    @Synchronized fun finish(id: Long, text: String = mutable.value.transcript, interrupted: Boolean = false) {
        val current = mutable.value
        if (current.requestId == id && current.active) {
            val result = current.copy(transcript = text, active = false, complete = true,
                interrupted = interrupted, finishRequested = false)
            mutable.value = result
            current.key?.let { completed.value = (completed.value + (it to result)).entries.toList().takeLast(16).associate { entry -> entry.toPair() } }
        }
    }
    fun interrupt(id: Long, text: String = mutable.value.transcript) = finish(id, text, interrupted = true)
    @Synchronized fun consume(id: Long) {
        completed.value = completed.value.filterValues { it.requestId != id }
        if (mutable.value.requestId == id) mutable.value = FieldSpeechView(requestId = id)
    }
    @Synchronized fun cancel(key: String? = null) {
        completed.value = if (key == null) emptyMap() else completed.value - key
        if (key == null || mutable.value.key == key) mutable.value = FieldSpeechView(requestId = mutable.value.requestId + 1)
    }
    fun isActive(id: Long) = mutable.value.let { it.requestId == id && it.active && it.key != null }
}

/** Vosk emits multiple completed phrases plus a replaceable current partial. */
internal class DictationTranscript {
    private val segments = mutableListOf<String>()
    private var unfinished = ""
    fun partial(text: String): String { unfinished = text.trim(); return text() }
    fun segment(text: String): String {
        text.trim().takeIf { it.isNotEmpty() }?.let(segments::add)
        unfinished = ""
        return text()
    }
    fun text(): String = (segments + unfinished).filter(String::isNotBlank).joinToString(" ")
    fun clear() { segments.clear(); unfinished = "" }
}
