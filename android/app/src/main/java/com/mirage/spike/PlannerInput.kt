package com.mirage.spike

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A field-scoped dictation result. Recording cannot execute a trip command. */
data class PlannerSpeechView(
    val requestId: Long = 0,
    val target: Field? = null,
    val transcript: String = "",
    val complete: Boolean = false,
    val active: Boolean = false,
)

object PlannerSpeech {
    private val mutable = MutableStateFlow(PlannerSpeechView())
    val state = mutable.asStateFlow()

    @Synchronized fun begin(field: Field): Long {
        val id = mutable.value.requestId + 1
        mutable.value = PlannerSpeechView(requestId = id, target = field, active = true)
        return id
    }
    @Synchronized fun partial(id: Long, text: String) {
        val current = mutable.value
        if (current.requestId == id && current.active) mutable.value = current.copy(transcript = text)
    }
    @Synchronized fun finish(id: Long, text: String) {
        val current = mutable.value
        if (current.requestId == id && current.active) mutable.value = current.copy(transcript = text, complete = true, active = false)
    }
    @Synchronized fun consume(id: Long) {
        if (mutable.value.requestId == id) mutable.value = PlannerSpeechView(requestId = id)
    }
    @Synchronized fun cancel() {
        mutable.value = PlannerSpeechView(requestId = mutable.value.requestId + 1)
    }
    fun isActive(id: Long) = mutable.value.let { it.requestId == id && it.active && it.target != null }
}

/** Explicit instruction prefixes only; ordinary business/place names stay in search. */
internal fun isPlannerInstruction(text: String): Boolean {
    val value = CommandParser.normalize(text).removePrefix("please ")
    if (value in setOf("yes", "confirm", "cancel", "no", "save changes", "help", "what can i say")) return true
    return Regex("^(?:add |go to |drive to |walk to |bike to |cycle to |fly to |take transit to |take me to |snap to |after |next |save |remove |move |stay |extend |set |change |make |put |what happens next)").containsMatchIn(value)
}

private val plannerReplies = setOf("yes", "confirm", "do it", "yes please", "no", "no thanks", "cancel",
    "one", "two", "three", "first", "second", "third", "1", "2", "3", "4", "5")
internal fun isPlannerReply(text: String) = CommandParser.normalize(text) in plannerReplies
internal fun isPlannerReplyPrefix(text: String) = plannerReplies.any { it.startsWith(CommandParser.normalize(text)) }
