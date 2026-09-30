package com.mirage.spike

import android.content.Context
import com.mirage.spike.engine.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Voice and touch share the continuation planner, live plan editing, and save implementation. */
object SmartVoice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var vm: MirageViewModel? = null
    private var start: (() -> Unit)? = null
    private var instructions = ""
    private var job: Job? = null
    private var generation = 0L
    private var pending: VoiceIntent? = null
    private var epoch = -1L
    private var pendingIndex = -1
    private var stopSnapshot: List<LiveStop> = emptyList()
    private var pendingDraft: ContinuationDraft? = null
    private var hits = emptyList<PlaceHit>()
    private var spoken = false
    fun needsReply() = pending != null || hits.isNotEmpty()
    fun hasChoices() = hits.isNotEmpty()
    fun bind(context: Context, model: MirageViewModel, onStart: () -> Unit) {
        if (vm !== model) cancel()
        vm = model; start = onStart
        LocalLanguageModel.configure(context)
        if (instructions.isBlank()) instructions = context.assets.open("voice-intent-prompt.txt").bufferedReader().use { it.readText() }
    }
    fun cancel() {
        generation++; job?.cancel(); job=null; LocalLanguageModel.cancel()
        if (pendingDraft != null && vm?.continuation?.state === pendingDraft) vm?.continuation?.cancel()
        pending=null; pendingDraft=null; hits=emptyList()
        Conversation.smartChoices(emptyList()); Conversation.smartBusy(false)
    }
    private fun say(text: String) = Conversation.smartReply(text,spoken)
    fun handle(text: String, isSpoken: Boolean): Boolean {
        val model = vm ?: return false
        val normalized = CommandParser.normalize(text)
        if (normalized in setOf("yes","confirm","do it","save it","yes please")) {
            if (!needsReply()) return false
            spoken=isSpoken; confirm(); return true
        }
        if (normalized in setOf("no","no thanks")) {
            if (!needsReply()) return false
            cancel(); spoken=isSpoken; say("Cancelled. Your trip is unchanged."); return true
        }
        if (hasChoices()) {
            val choice = CommandParser.parse(text) as? SpokenCommand.Choice
            if (choice != null) { choose(choice.ordinal-1); return true }
        }
        // Saving remains available without downloading a language model.
        val save = Regex("^(?:save (?:this |the )?(?:whole )?(?:trip|itinerary) as) (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        val direct = if (save != null) VoiceIntent("save_new",save.groupValues[1].trim())
            else if (normalized in setOf("save changes","save my itinerary","save this itinerary")) VoiceIntent("save_changes") else null
        if (!LocalLanguageModel.ready && direct == null) return false
        Conversation.cancelPending()
        cancel(); spoken=isSpoken
        val serial = generation
        epoch = LiveSession.epoch; stopSnapshot = LiveSession.state.value.stops
        job = scope.launch {
            Conversation.smartBusy(true)
            try {
                val current = LiveSession.state.value
                val catalog = JSONArray(model.savedScenarios.take(40).map { it.name })
                val upcoming = JSONArray(current.stops.drop(current.index+1).map { it.stop.name })
                val user = "Saved catalog: $catalog\nUpcoming stops: $upcoming\nInstruction: " + JSONObject.quote(text.take(600).replace("<|","< |"))
                val prompt = "<|im_start|>system\n$instructions<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                val intent = direct ?: VoiceIntent.parse(LocalLanguageModel.interpret(prompt))
                ensureActive(); if (serial != generation) return@launch
                check(epoch == LiveSession.epoch) { "The simulation changed. Please repeat your instruction." }
                val normalizedIntent = if (intent.action == "add_place" && model.savedScenarios.count { it.name.equals(intent.target,true) } == 1)
                    intent.copy(action="add_saved") else intent
                check(normalizedIntent.groundedIn(text)) { "Please say the actual saved name or full destination; I won't guess what 'there' means." }
                prepare(normalizedIntent)
            } catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (serial == generation) {
                if (pending != null) model.continuation.cancel()
                cancel(); say(e.message ?: "Could not understand that instruction. Try one change at a time.")
            } }
            finally { if (serial == generation) Conversation.smartBusy(false) }
        }
        return true
    }
    private suspend fun prepare(intent: VoiceIntent) {
        val model=vm ?: return
        if (intent.action == "clarify") { say("Please name the saved item or destination and make one change at a time."); return }
        if (intent.action in setOf("status","pause","resume")) {
            Conversation.executeBasic(intent.action,spoken); return
        }
        check(MockState.status.value.running && LiveSession.plan != null) { "Start a simulation before editing or saving its itinerary." }
        pending=intent
        epoch=LiveSession.epoch; stopSnapshot=LiveSession.state.value.stops
        pendingIndex=LiveSession.state.value.index
        when(intent.action) {
            "add_saved" -> {
                val matches=model.savedScenarios.filter { it.name.equals(intent.target,true) }
                check(matches.size==1) { "I couldn't identify one saved item called ${intent.target}. Choose it from Add destination." }
                model.continuation.begin(intent.placement)
                model.continuation.saved(matches.single())
                finishPreview()
            }
            "add_place" -> {
                model.continuation.begin(intent.placement)
                val origin=model.continuation.origin()
                check(model.hasKey) { "Add your Maps key in Setup to search." }
                hits=model.placeSearch(model.api,intent.target,origin).take(5)
                check(hits.isNotEmpty()) { "No match. Try the full place name and city." }
                if (hits.size==1) { model.continuation.pick(hits.single()); hits=emptyList(); finishPreview() }
                else {
                    model.continuation.edit(false)
                    pendingDraft=model.continuation.state
                    Conversation.smartChoices(hits)
                    say("Which location? " + hits.mapIndexed { i,h -> "${i+1}: ${h.name}, ${h.address}" }.joinToString(". "))
                }
            }
            "save_new" -> say("Save the full live itinerary as ${intent.target}? Say yes or cancel.")
            "save_changes" -> {
                check(LiveSession.state.value.savedId != null) { "Give this trip a name: say save this trip as, followed by its name." }
                say("Update ${LiveSession.state.value.savedName} with this full live itinerary? Say yes or cancel.")
            }
            "remove","move" -> {
                val upcoming=LiveSession.state.value.let { it.stops.drop(it.index+1) }
                check(upcoming.count { it.stop.name.equals(intent.target,true) } == 1) { "Name one unique upcoming stop, or use My itinerary." }
                if(intent.action=="move") check(intent.position in 1..upcoming.size) { "That stop position does not exist." }
                say(if(intent.action=="remove") "Remove ${intent.target} from this trip? Say yes or cancel." else "Move ${intent.target} to upcoming position ${intent.position}? Say yes or cancel.")
            }
            "stay","extend" -> say(if(intent.action=="stay") "Set the current stop's stay to ${intent.minutes} minutes? Say yes or cancel." else "Add ${intent.minutes} minutes to the current stay? Say yes or cancel.")
        }
    }
    private suspend fun finishPreview() {
        val model=vm ?: return
        model.continuation.edit(false)
        withTimeout(35_000) { while(model.continuation.state?.previewBusy == true) delay(100) }
        val d=model.continuation.state ?: error("The addition was cancelled.")
        check(d.ready) { d.error ?: "Could not prepare the route." }
        pendingDraft=d
        val names=d.stops.joinToString { it.name + if(it.address.isBlank()) "" else ", ${it.address}" }
        say("${d.placement.label}: $names. Existing stops stay in your trip. Say yes or cancel.")
    }
    fun choose(index: Int) {
        val hit=hits.getOrNull(index) ?: return
        val model=vm ?: return
        hits=emptyList(); Conversation.smartChoices(hits)
        val serial=generation
        job=scope.launch {
            try { check(epoch==LiveSession.epoch); model.continuation.pick(hit); finishPreview() }
            catch(e: Exception) { if(serial==generation) say(e.message ?: "Please choose the location again.") }
        }
    }
    private fun confirm() {
        val action=pending ?: run { say("Choose a location first."); return }
        if(hits.isNotEmpty()) { say("Choose a location by number first."); return }
        val model=vm ?: return
        try {
            check(MockState.status.value.running && epoch==LiveSession.epoch && stopSnapshot==LiveSession.state.value.stops) { "The itinerary changed. Please repeat your instruction before confirming." }
            val plan=LiveSession.plan ?: error("No live itinerary")
            if (action.action in setOf("stay","extend")) check(plan.view().index == pendingIndex) { "The current stop changed. Please repeat your instruction." }
            when(action.action) {
                "add_saved","add_place" -> {
                    check(pendingDraft != null && model.continuation.state === pendingDraft) { "The addition changed. Please review it again." }
                    check(model.continuation.commit(start ?: error("Open Mirage first"))) { model.continuation.state?.error ?: "Could not add destination" }
                }
                "save_new" -> check(model.saveActiveScenario(action.target)) { model.error ?: "Could not save" }
                "save_changes" -> check(model.saveActiveScenario(plan.view().savedName,true)) { model.error ?: "Could not save changes" }
                "stay" -> check(plan.setCurrentStay(action.minutes)) { "Current stop changed" }
                "extend" -> check(plan.extendStay(action.minutes)) { "Current stop changed" }
                "remove","move" -> {
                    val view=plan.view(); val entries=view.stops.drop(view.index+1)
                    val entry=entries.single { it.stop.name.equals(action.target,true) }
                    check(if(action.action=="remove") plan.remove(entry.id) else plan.move(entry.id, action.position-1-entries.indexOf(entry))) { "The trip advanced. Try again." }
                }
            }
            pending=null; pendingDraft=null
            say(if(action.action.startsWith("save")) "Saved. Your simulation continues." else "Done. The itinerary is updated. Say save changes to keep it.")
        } catch(e: Exception) { cancel(); say(e.message ?: "Could not apply the change") }
    }
}
