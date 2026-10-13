package com.mirage.spike

import com.mirage.spike.engine.LatLng
import com.mirage.spike.engine.PlaceHit
import com.mirage.spike.store.SavedScenario
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZoneId
import java.time.ZonedDateTime

data class PlannerReviewView(
    val busy: Boolean = false,
    val input: String = "",
    val question: String = "",
    val choices: List<PlaceHit> = emptyList(),
    val candidate: PlanCandidate? = null,
    val canConfirm: Boolean = false,
    val status: String = "",
)

/** Whole-plan interpretation and place resolution never mutate the confirmed planner. */
class PlannerReviewController(
    private val vm: MirageViewModel,
    private val scope: CoroutineScope,
    private val interpret: suspend (String, String) -> PlanInterpretation,
) {
    private val mutableState = MutableStateFlow(PlannerReviewView())
    val state: StateFlow<PlannerReviewView> = mutableState.asStateFlow()
    private var base: PlannerSnapshot? = null
    private var slot: String? = null
    private var field = Field.END
    private var history = emptyList<String>()
    private var serial = 0L
    private var job: Job? = null
    private var inputGeneration = InputDraftStore.generation

    fun propose(text: String, field: Field) {
        if (MockState.status.value.let { it.running || it.starting }) {
            mutableState.value = mutableState.value.copy(status = "Stop the simulation before changing this draft.", canConfirm = false)
            return
        }
        if (text.isBlank() || text.length > NaturalLanguageInput.MAX_TEXT_CHARS) {
            mutableState.value = mutableState.value.copy(status = "Enter an instruction of at most ${NaturalLanguageInput.MAX_TEXT_CHARS} characters.", canConfirm = false)
            return
        }
        val oldBase = base
        if (oldBase != null && !vm.matchesPlannerSnapshot(oldBase)) {
            invalidate("The plan changed. Start a fresh review from the current plan.")
            return
        }
        job?.cancel(); val request = ++serial
        inputGeneration = InputDraftStore.generation
        this.field = field
        val before = oldBase ?: vm.capturePlanner().also { base = it }
        val pending = mutableState.value.candidate ?: PlanCandidate.fromSnapshot(before)
        val previousQuestion = mutableState.value.question
        val priorHistory = history
        history = (history + text).takeLast(8)
        slot = null
        mutableState.value = PlannerReviewView(busy = true, input = text, candidate = pending, status = "Reviewing the whole plan…")
        persist()
        job = scope.launch {
            try {
                val context = context(before, pending, priorHistory, previousQuestion)
                NaturalLanguageInput.checkInput(text, context)
                val result = interpret(text, context)
                if (!current(request, before)) return@launch
                if (result.question.isNotBlank()) {
                    require(result.edits.isEmpty()) { "Clarification cannot also change the plan." }
                    mutableState.value = mutableState.value.copy(busy = false, question = result.question,
                        status = "Clarify this instruction; your pending plan is retained.", canConfirm = false)
                    persist()
                    return@launch
                }
                // Construct every logical edit before any lookup. Later queries survive ambiguity.
                val next = pending.applyEdits(result.edits)
                mutableState.value = mutableState.value.copy(candidate = next, question = "", choices = emptyList())
                persist()
                resolve(request, before)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (current(request, before)) {
                    mutableState.value = mutableState.value.copy(busy = false, canConfirm = false,
                        status = if (failure is IllegalArgumentException) failure.message ?: "Clarify the proposed changes."
                        else "The instruction could not be reviewed. Your pending plan and input are retained.")
                    persist()
                }
            }
        }
    }

    fun choose(hit: PlaceHit) {
        val before = base ?: return
        val target = slot ?: return
        val view = mutableState.value
        if (view.busy || hit !in view.choices || !current(serial, before)) return
        val saved = if (hit.placeId.startsWith(SAVED_PREFIX)) vm.savedScenarios.firstOrNull {
            it.id == hit.placeId.removePrefix(SAVED_PREFIX) && validSaved(it)
        } else null
        if (hit.placeId.startsWith(SAVED_PREFIX) && (saved == null || saved.dest != hit.latLng)) {
            mutableState.value = view.copy(canConfirm = false, status = "That saved place changed. Review the instruction again.")
            return
        }
        val selected = saved?.let(::savedHit) ?: hit
        val candidate = view.candidate ?: return
        mutableState.value = view.copy(candidate = candidate.resolve(target, selected, saved).copy(lastTarget = target), busy = true,
            choices = emptyList(), question = "", canConfirm = false, status = "Resolving the remaining places…")
        slot = null
        persist()
        val request = ++serial
        job = scope.launch { resolve(request, before) }
    }

    fun confirm(): Boolean {
        val before = base ?: return false
        val view = mutableState.value
        val candidate = view.candidate ?: return false
        if (!view.canConfirm || view.busy || !candidate.resolved || !current(serial, before)) return false
        val selectedSaved = (listOfNotNull(candidate.origin) + candidate.preview.map { it.place }).mapNotNull { it.saved }
        if (selectedSaved.any { selected -> vm.savedScenarios.none { sameSaved(it, selected) } }) {
            mutableState.value = view.copy(canConfirm = false, status = "A saved place changed. Review the instruction again.")
            persist()
            return false
        }
        val after = runCatching { candidate.toSnapshot(before) }.getOrElse {
            mutableState.value = view.copy(canConfirm = false, status = "The proposed plan is incomplete. Review it again.")
            return false
        }
        if (!vm.applyPlannerProposal(before, after)) {
            mutableState.value = view.copy(canConfirm = false, status = vm.error ?: "The plan changed. Review it again.")
            return false
        }
        selectedSaved.map { it.id }.distinct().forEach(vm::recordSavedUse)
        job?.cancel(); serial++; base = null; slot = null; history = emptyList()
        val removed = InputDraftStore.read(KEY) == null || InputDraftStore.remove(KEY)
        mutableState.value = PlannerReviewView(status = if (removed) "Plan updated. Review it before tapping Start."
            else "Plan updated. The old recovery review could not be removed; it will not apply automatically.")
        return true
    }

    /** Cancels only this pending review. A new planner session clears its durable data first. */
    fun reset() {
        if (InputDraftStore.read(KEY) != null && !InputDraftStore.remove(KEY)) {
            job?.cancel(); serial++
            mutableState.value = mutableState.value.copy(busy = false, canConfirm = false,
                status = "Could not clear the saved review. Your input is still retained.")
            return
        }
        job?.cancel(); serial++; base = null; slot = null; history = emptyList()
        inputGeneration = InputDraftStore.generation
        mutableState.value = PlannerReviewView()
    }

    /** Restores a review without interpreting, searching, applying, saving a scenario or starting. */
    fun restorePending() {
        if (base != null || mutableState.value.busy) return
        val encoded = InputDraftStore.read(KEY) ?: return
        try {
            val data = JSONObject(encoded)
            require(data.getInt("version") == 1)
            val restoredBase = PlannerSnapshot.fromJson(data.getJSONObject("base"))
            if (!vm.matchesPlannerSnapshot(restoredBase)) {
                mutableState.value = PlannerReviewView(status = "An earlier review belongs to a different draft. Review your instruction again.")
                return
            }
            val candidate = PlanCandidate.fromJson(data.getJSONObject("candidate"))
            val choices = data.optJSONArray("choices") ?: JSONArray()
            require(choices.length() <= 100)
            val question = data.optString("question")
            val prior = data.optJSONArray("history") ?: JSONArray()
            require(prior.length() <= 8)
            val input = data.getString("input")
            require(input.length <= NaturalLanguageInput.MAX_TEXT_CHARS)
            job?.cancel(); serial++; base = restoredBase
            slot = data.optString("slot").takeIf { it.isNotBlank() }
            field = Field.valueOf(data.getString("field"))
            history = (0 until prior.length()).map { prior.getString(it) }
            inputGeneration = InputDraftStore.generation
            val wasReady = data.optBoolean("ready")
            mutableState.value = PlannerReviewView(input = input, question = question,
                choices = (0 until choices.length()).map { hitFromJson(choices.getJSONObject(it)) }, candidate = candidate,
                canConfirm = wasReady && candidate.resolved && question.isBlank(),
                status = if (wasReady) "Restored your pending review. Check it before applying."
                    else "Restored your pending plan and input. Continue the review when ready.")
        } catch (_: Exception) {
            mutableState.value = PlannerReviewView(status = "The saved review could not be restored. The confirmed plan is unchanged.")
        }
    }

    private suspend fun resolve(request: Long, before: PlannerSnapshot) {
        while (current(request, before)) {
            val candidate = mutableState.value.candidate ?: return
            val unresolved = candidate.unresolved.firstOrNull()
            if (unresolved == null) {
                slot = null
                mutableState.value = mutableState.value.copy(busy = false, question = "", choices = emptyList(),
                    canConfirm = true, status = "Review all changes, then apply the plan.")
                persist(); return
            }
            val (target, place) = unresolved
            val saved = vm.savedScenarios.filter { validSaved(it) &&
                (if (place.query.startsWith(SAVED_PREFIX)) it.id == place.query.removePrefix(SAVED_PREFIX)
                else it.name.equals(place.query,true) || it.aliases.any { alias -> alias.equals(place.query,true) }) }
            var chosenSaved: SavedScenario? = null
            val hits: List<PlaceHit>
            try {
                hits = when {
                    saved.size == 1 -> { chosenSaved = saved.single(); listOf(savedHit(saved.single())) }
                    saved.isNotEmpty() -> saved.map(::savedHit)
                    place.query.startsWith(SAVED_PREFIX) -> emptyList()
                    !vm.hasKey -> {
                        if (!current(request, before)) return
                        slot = target
                        mutableState.value = mutableState.value.copy(busy = false, canConfirm = false,
                            question = "Choose a saved place or add a Maps key to find ${place.label}.", choices = emptyList(),
                            status = "Your complete pending plan is retained.")
                        persist(); return
                    }
                    else -> vm.placeSearch(vm.api, place.query, resolutionBias(candidate, target))
                }.filter { hit -> hit.latLng.lat.isFinite() && hit.latLng.lng.isFinite() &&
                    hit.latLng.lat in -90.0..90.0 && hit.latLng.lng in -180.0..180.0 && hit.name.isNotBlank() }.distinct().take(100)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (current(request, before)) {
                    slot = target
                    mutableState.value = mutableState.value.copy(busy = false, canConfirm = false,
                        question = "Try again or clarify the location for ${place.label}.", choices = emptyList(),
                        status = "Place search failed. Your complete pending plan is retained.")
                    persist()
                }
                return
            }
            if (!current(request, before)) return
            if (hits.size != 1) {
                slot = target
                mutableState.value = mutableState.value.copy(busy = false, canConfirm = false, choices = hits,
                    question = if (hits.isEmpty()) "No place matched ${place.label}. Specify the city or choose another place."
                    else "Which ${place.label} did you mean?", status = "Your other proposed stops and settings are retained.")
                persist(); return
            }
            mutableState.value = mutableState.value.copy(candidate = candidate.resolve(target, hits.single(), chosenSaved))
            persist()
        }
    }

    private fun current(request: Long, before: PlannerSnapshot): Boolean {
        if (request != serial || before.draftGeneration != vm.draftGeneration) return false
        if (!vm.matchesPlannerSnapshot(before) || MockState.status.value.let { it.running || it.starting }) {
            invalidate("The plan changed. Start a fresh review from the current plan.")
            return false
        }
        return true
    }

    private fun invalidate(message: String) {
        job?.cancel(); serial++; base = null; slot = null; history = emptyList()
        // Keep the visible input for correction; a stale candidate is never confirmable.
        mutableState.value = PlannerReviewView(input = mutableState.value.input, status = message)
    }

    private fun persist() {
        val before = base ?: return
        val view = mutableState.value
        val candidate = view.candidate ?: return
        if (before.draftGeneration != vm.draftGeneration) return
        val json = JSONObject().put("version",1).put("base",before.toJson()).put("candidate",candidate.toJson())
            .put("input",view.input).put("field",field.name).put("question",view.question).put("slot",slot.orEmpty())
            .put("history",JSONArray(history)).put("ready",view.canConfirm)
            .put("choices",JSONArray().apply { view.choices.forEach { put(hitJson(it)) } })
        if (!InputDraftStore.saveIfCurrent(KEY,json.toString(),inputGeneration)) mutableState.value = view.copy(
            status = "Your review is retained on this screen, but its recovery copy could not be saved.")
    }

    private fun context(before: PlannerSnapshot, pending: PlanCandidate, prior: List<String>, question: String): String {
        val lastIndex = pending.preview.indexOfFirst { it.id == pending.lastTarget }
        val lastReference = when {
            pending.lastTarget == "origin" -> "start"
            lastIndex >= 0 && pending.planMode == PlanMode.ITINERARY -> "stop:${lastIndex + 1}"
            lastIndex >= 0 -> "destination"
            else -> ""
        }
        fun plan(candidate: PlanCandidate) = JSONObject().put("mode",candidate.planMode.name)
            .put("origin",candidate.origin?.let { place -> JSONObject().put("label",place.label).put("query",place.query)
                .put("saved_id",place.saved?.id.orEmpty()).put("resolved",place.resolved) })
            .put("departure_millis",candidate.departureMillis)
            .put("stops",JSONArray().apply { candidate.preview.forEachIndexed { i, stop -> put(JSONObject()
                .put("target",if(candidate.planMode == PlanMode.ITINERARY) "stop:${i+1}" else "destination")
                .put("label",stop.label).put("query",stop.place.query).put("saved_id",stop.place.saved?.id.orEmpty())
                .put("resolved",stop.place.resolved).put("stay_minutes",stop.stayMinutes).put("mode",stop.mode.name)) } })
        return JSONObject().put("local_now",ZonedDateTime.now().toString()).put("timezone",ZoneId.systemDefault().id)
            .put("active_field",field.name).put("current_plan",plan(PlanCandidate.fromSnapshot(before)))
            .put("last_mentioned_target",lastReference)
            .put("pending_plan",plan(pending)).put("earlier_instructions",JSONArray(prior)).put("previous_question",question)
            .put("saved_places",JSONArray().apply { vm.savedScenarios.filter(::validSaved).forEach { saved ->
                put(JSONObject().put("id",saved.id).put("name",saved.name).put("aliases",JSONArray(saved.aliases)).put("address",saved.destAddress))
            } }).toString()
    }

    private fun resolutionBias(candidate: PlanCandidate, target: String): LatLng? {
        val index = candidate.preview.indexOfFirst { it.id == target }
        return candidate.preview.take(index.coerceAtLeast(0)).lastOrNull { it.place.resolved }?.place?.hit?.latLng
            ?: candidate.origin?.hit?.latLng ?: vm.tripStart()
    }
    private fun savedHit(sc: SavedScenario) = PlaceHit(requireNotNull(sc.dest),sc.name,sc.destAddress,SAVED_PREFIX + sc.id)
    private fun validSaved(sc: SavedScenario): Boolean = sc.kind == PlanMode.SNAP.name && sc.dest?.let {
        it.lat.isFinite() && it.lng.isFinite() && it.lat in -90.0..90.0 && it.lng in -180.0..180.0
    } == true
    private fun sameSaved(now: SavedScenario, selected: SavedScenario) = validSaved(now) &&
        now.copy(lastUsedAt = selected.lastUsedAt, favorite = selected.favorite) == selected

    companion object {
        private const val KEY = "planner.review"
        private const val SAVED_PREFIX = "saved:"
    }
}
