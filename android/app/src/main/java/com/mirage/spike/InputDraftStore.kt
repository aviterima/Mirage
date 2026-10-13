package com.mirage.spike

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

internal interface PlannerDraftStorage {
    fun all(): Map<String, *>
    fun commit(values: Map<String, Any>, removals: Set<String>): Boolean
}

private class PreferencesDraftStorage(private val preferences: SharedPreferences) : PlannerDraftStorage {
    override fun all(): Map<String, *> = preferences.all
    override fun commit(values: Map<String, Any>, removals: Set<String>): Boolean {
        val previous = preferences.all
        val editor = preferences.edit()
        removals.forEach(editor::remove)
        values.forEach { (key, value) -> when (value) {
            is Long -> editor.putLong(key, value)
            is String -> editor.putString(key, value)
            else -> error("Unsupported draft value")
        } }
        if (editor.commit()) return true
        // SharedPreferences updates its memory before disk commit. Restore touched values on
        // failure so a failed reset does not appear to have cleared the current input.
        val rollback = preferences.edit()
        (values.keys + removals).forEach { key -> when (val value = previous[key]) {
            is String -> rollback.putString(key, value)
            is Long -> rollback.putLong(key, value)
            else -> rollback.remove(key)
        } }
        rollback.commit()
        return false
    }
}

/** Local interrupted input only. Never export this store through diagnostics. */
object InputDraftStore {
    private const val PREFIX = "input."
    private const val INPUT_GENERATION = "inputGeneration"
    private const val DRAFT_GENERATION = "draftGeneration"
    private const val MAX_VALUE_BYTES = 256 * 1024
    private const val MAX_TOTAL_BYTES = 2 * 1024 * 1024
    private var storage: PlannerDraftStorage? = null
    private var secrets: () -> List<String> = { emptyList() }
    var generation by mutableLongStateOf(0L)
        private set
    internal var draftGeneration by mutableLongStateOf(0L)
        private set

    @Synchronized fun configure(context: Context) {
        if (storage != null) return
        val app = context.applicationContext
        attach(PreferencesDraftStorage(app.getSharedPreferences("mirage_recovery", Context.MODE_PRIVATE)))
        secrets = {
            val keys = app.getSharedPreferences("mirage_keys", Context.MODE_PRIVATE)
            listOfNotNull(keys.getString("user_key", null), keys.getString("install_id", null),
                keys.getString("install_id", null)?.take(8), BuildConfig.MAPS_API_KEY)
        }
    }

    private fun attach(value: PlannerDraftStorage?) {
        storage = value
        val current = value?.all().orEmpty()
        generation = current[INPUT_GENERATION] as? Long ?: 0L
        draftGeneration = current[DRAFT_GENERATION] as? Long ?: 0L
    }
    internal fun useStorageForTest(value: PlannerDraftStorage?, knownSecrets: List<String> = emptyList()) {
        synchronized(this) { attach(value); secrets = { knownSecrets } }
    }
    private fun validKey(key: String) = key.isNotBlank() && key.length <= 180 && key.none { it.isISOControl() }
    private fun safe(text: String): Boolean {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_VALUE_BYTES) return false
        if (secrets().any { it.length >= 8 && text.contains(it) }) return false
        if (Regex("(?i)(?:\\bBearer\\s+[A-Za-z0-9._~+/-]{8,}|\\bsk-[A-Za-z0-9_-]{12,}|-----BEGIN [A-Z ]*PRIVATE KEY-----)").containsMatchIn(text)) return false
        return !Regex("(?i)(?:AIza[0-9A-Za-z_-]{20,}|(?:api[_ -]?key|password|authorization|bearer|access[_ -]?token|secret)\\s*[\\\"']?\\s*[:=]\\s*[\\\"']?[^\\s\\\"',}]{8,})").containsMatchIn(text)
    }
    @Synchronized fun read(key: String): String? = if (validKey(key)) storage?.all()?.get(PREFIX + key) as? String else null
    @Synchronized fun write(key: String, text: String): Boolean = saveIfCurrent(key, text, generation)
    @Synchronized fun saveIfCurrent(key: String, text: String, generation: Long): Boolean {
        if (generation != this.generation || !validKey(key) || !safe(text)) return false
        return commit(mapOf(PREFIX + key to text), emptySet())
    }
    @Synchronized fun remove(key: String): Boolean {
        if (!validKey(key)) return false
        val next = generation + 1
        if (!commit(mapOf(INPUT_GENERATION to next), setOf(PREFIX + key))) return false
        generation = next
        return true
    }
    @Synchronized fun clearPlanner(): Boolean {
        val next = generation + 1
        val remove = storage?.all()?.keys.orEmpty().filter { it.startsWith(PREFIX + "planner.") }.toSet()
        if (!commit(mapOf(INPUT_GENERATION to next), remove, allowUnconfigured = true)) return false
        generation = next
        return true
    }
    /** One durable transaction clears planner input, full/legacy drafts and both generations. */
    @Synchronized internal fun resetPlannerRecovery(): Boolean {
        val nextInput = generation + 1
        val nextDraft = draftGeneration + 1
        val remove = storage?.all()?.keys.orEmpty().filter { it.startsWith(PREFIX + "planner.") }.toSet() +
            setOf("draft", "plannerSnapshot")
        if (!commit(mapOf(INPUT_GENERATION to nextInput, DRAFT_GENERATION to nextDraft), remove, allowUnconfigured = true)) return false
        generation = nextInput; draftGeneration = nextDraft
        return true
    }
    @Synchronized internal fun readRecovery(key: String): String? = storage?.all()?.get(key) as? String
    @Synchronized internal fun saveRecovery(values: Map<String, String>, generation: Long): Boolean {
        if (generation != draftGeneration || values.values.any { !safe(it) }) return false
        return commit(values, emptySet())
    }
    private fun commit(values: Map<String, Any>, removals: Set<String>, allowUnconfigured: Boolean = false): Boolean {
        val target = storage ?: return allowUnconfigured
        val next = target.all().toMutableMap().apply { removals.forEach(::remove); putAll(values) }
        if (next.filterKeys { it.startsWith(PREFIX) || it in setOf("draft", "plannerSnapshot") }
                .values.sumOf { (it as? String)?.toByteArray(Charsets.UTF_8)?.size ?: 0 } > MAX_TOTAL_BYTES) return false
        return runCatching { target.commit(values, removals) }.getOrDefault(false)
    }
}
