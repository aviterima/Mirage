package com.mirage.spike

import android.content.Context
import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario
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
    private val followUp = VoiceFollowUp()
    private var planningRequest=false
    private var planningSnapshot: SavedScenario?=null
    private var planningSaved: SavedScenario?=null
    private var planningHit: PlaceHit?=null
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
        pending=null; pendingDraft=null; hits=emptyList();planningRequest=false;planningSnapshot=null;planningSaved=null;planningHit=null
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
        val parsed=CommandParser.parse(text)
        val currentView=LiveSession.state.value
        val contextIntent=followUp.resolve(text,currentView.stops,currentView.index,LiveSession.epoch)
        val savedMatches=model.savedScenarios.filter { item ->
            (listOf(item.name)+item.aliases).any { alias -> alias.isNotBlank() && Regex("(?i)(?<![\\p{L}\\p{N}])"+Regex.escape(alias)+"(?![\\p{L}\\p{N}])").containsMatchIn(text) }
        }
        val adding=Regex("^(?:please )?(?:add|take me to|go to|after this|next|drive to|walk to)",RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
        val savedIntent=if(adding && savedMatches.size==1 && !Regex("(?i)\\b(and|then|also)\\b").containsMatchIn(text)) VoiceIntent("add_saved",savedMatches.single().name,
            if(Regex("(?i)\\b(now|immediately)\\b").containsMatchIn(text)) Placement.NOW else if(text.contains("end",true)) Placement.END else Placement.NEXT,minutes=(parsed as? SpokenCommand.Journey)?.legs?.singleOrNull()?.minutes ?: 0,travelMode=(parsed as? SpokenCommand.Journey)?.legs?.singleOrNull()?.mode) else null
        val save = Regex("^(?:save (?:this |the )?(?:whole )?(?:trip|itinerary) as) (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        val direct = contextIntent ?: savedIntent ?: if (save != null) VoiceIntent("save_new",save.groupValues[1].trim())
            else if (normalized in setOf("save changes","save my itinerary","save this itinerary")) VoiceIntent("save_changes")
            else when(parsed) {
                is SpokenCommand.Journey -> if(parsed.legs.size==1 && !parsed.snap) VoiceIntent("add_place",parsed.legs.single().query,if(normalized.contains("now"))Placement.NOW else Placement.NEXT,minutes=parsed.legs.single().minutes,travelMode=parsed.legs.single().mode) else null
                is SpokenCommand.Stay -> VoiceIntent("stay",minutes=parsed.minutes)
                is SpokenCommand.Extend -> VoiceIntent("extend",minutes=parsed.minutes)
                else -> null
            }
        if (!LocalLanguageModel.ready && direct == null) return false
        Conversation.cancelPending()
        cancel(); spoken=isSpoken
        val serial = generation
        epoch = LiveSession.epoch; stopSnapshot = LiveSession.state.value.stops
        job = scope.launch {
            Conversation.smartBusy(true)
            try {
                val current = LiveSession.state.value
                val catalog = JSONArray(model.savedScenarios.sortedByDescending { item -> text.lowercase().split(" ").count { word -> word.length>2 && item.name.contains(word,true) } }.take(40).map { it.name })
                val upcoming = JSONArray(current.stops.drop(current.index+1).map { it.stop.name })
                val user = "Saved catalog: $catalog\nUpcoming stops: $upcoming\nInstruction: " + JSONObject.quote(text.take(600).replace("<|","< |"))
                val prompt = "<|im_start|>system\n$instructions<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
                val intent = direct ?: VoiceIntent.parse(LocalLanguageModel.interpret(prompt))
                ensureActive(); if (serial != generation) return@launch
                check(epoch == LiveSession.epoch) { "The simulation changed. Please repeat your instruction." }
                val normalizedIntent = if (intent.action == "add_place" && model.savedScenarios.count { it.name.equals(intent.target,true) } == 1)
                    intent.copy(action="add_saved") else intent
                check(direct != null || normalizedIntent.groundedIn(text)) { "Please say the actual saved name or full destination; I won't guess what 'there' means." }
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
        if(!MockState.status.value.running) {preparePlanning(intent);return}
        check(LiveSession.plan != null) { "Open your itinerary before editing it." }
        pending=intent
        epoch=LiveSession.epoch; stopSnapshot=LiveSession.state.value.stops
        pendingIndex=LiveSession.state.value.index
        when(intent.action) {
            "add_saved" -> {
                val matches=model.savedScenarios.filter { it.name.equals(intent.target,true) }
                check(matches.size==1) { "I couldn't identify one saved item called ${intent.target}. Choose it from Add destination." }
                model.continuation.begin(intent.placement)
                intent.travelMode?.let{model.continuation.mode(it)}
                model.continuation.saved(matches.single())
                if(intent.minutes>0) {
                    check(model.continuation.state?.stops?.size==1) { "Add the itinerary first, then name the stop whose stay should change." }
                    model.continuation.stay(intent.minutes)
                }
                finishPreview()
            }
            "add_place" -> {
                model.continuation.begin(intent.placement)
                intent.travelMode?.let{model.continuation.mode(it)}
                val origin=model.continuation.origin()
                check(model.hasKey) { "Add your Maps key in Setup to search." }
                hits=model.placeSearch(model.api,intent.target,origin).take(5)
                check(hits.isNotEmpty()) { "No match. Try the full place name and city." }
                if (hits.size==1) { model.continuation.pickResolved(hits.single()); if(intent.minutes>0)model.continuation.stay(intent.minutes); hits=emptyList(); finishPreview() }
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
            "stay","extend" -> {
                if(intent.target.isNotBlank()) check(LiveSession.state.value.let { it.stops.drop(it.index+1) }.count { it.stop.name.equals(intent.target,true) } == 1) { "That upcoming stop changed. Name it again." }
                say(if(intent.action=="stay") "Set ${intent.target.ifBlank { "the current stop" }}'s stay to ${intent.minutes} minutes? Say yes or cancel." else "Add ${intent.minutes} minutes to the current stay? Say yes or cancel.")
            }
        }
    }
    private suspend fun preparePlanning(intent: VoiceIntent) {
        val model=vm ?: return
        planningRequest=true;planningSnapshot=model.draftSnapshot();pending=intent;epoch=LiveSession.epoch
        when(intent.action) {
            "add_saved" -> {
                planningSaved=model.savedScenarios.singleOrNull{it.name.equals(intent.target,true)} ?: error("Name one saved item")
                say("Add ${intent.target} to the planned itinerary, including a connecting leg if needed? Simulation stays off. Say yes or cancel.")
            }
            "add_place" -> {
                check(model.hasKey){"Add a Maps key to search"}
                hits=model.placeSearch(model.api,intent.target,model.tripStart()).take(5)
                check(hits.isNotEmpty()){"No match; name the place and city"}
                if(hits.size==1){planningHit=hits.single();hits=emptyList();say("Add ${planningHit!!.name}, ${planningHit!!.address}, to the planned itinerary? Simulation stays off. Say yes or cancel.")}
                else {Conversation.smartChoices(hits);say("Choose the correct place by number, then review its address.")}
            }
            "save_new" -> say("Save the planned itinerary as ${intent.target}? Say yes or cancel.")
            "save_changes" -> {check(model.draftSavedName!=null){"Name this itinerary first: save this trip as followed by a name"};say("Update ${model.draftSavedName} with the current plan? Say yes or cancel.")}
            else -> {cancel();say("This command needs a running trip. You can add a saved place or save the plan before starting.")}
        }
    }
    private fun confirmPlanning(action: VoiceIntent) {
        val model=vm ?: return
        check(!MockState.status.value.running && epoch==LiveSession.epoch && planningSnapshot==model.draftSnapshot()) {"The plan changed. Repeat the instruction before confirming."}
        when(action.action) {
            "add_saved" -> {
                val item=planningSaved ?: error("Choose an item")
                check(model.savedScenarios.any{it==item}){"The saved item changed; review it again"}
                when(item.kind) {
                    "ROUTE" -> check(model.appendSavedRoute(item,true)){model.error ?: "Could not add route"}
                    "ITINERARY" -> model.appendSavedItinerary(item)
                    else -> {action.travelMode?.let{model.chooseMode(it)};model.appendResolvedStop(PlaceHit(item.dest ?: error("Missing destination"),item.name,item.destAddress,item.destPlaceId),action.minutes)}
                }
            }
            "add_place" -> {action.travelMode?.let{model.chooseMode(it)};model.appendResolvedStop(planningHit ?: error("Choose a place"),action.minutes)}
            "save_new" -> check(model.saveScenario(action.target)){model.error ?: "Could not save"}
            "save_changes" -> check(model.saveScenario(model.draftSavedName ?: error("Name the itinerary"),true)){model.error ?: "Could not save"}
        }
        cancel();say("Done. The plan is updated; simulation is still off.")
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
        if(planningRequest) {
            planningHit=hit
            say("Add ${hit.name}, ${hit.address}, to the planned itinerary? Simulation stays off. Say yes or cancel.")
            return
        }
        val serial=generation
        job=scope.launch {
            Conversation.smartBusy(true)
            try { check(epoch==LiveSession.epoch); model.continuation.pickResolved(hit);pending?.minutes?.takeIf{it>0}?.let{model.continuation.stay(it)}; finishPreview() }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(serial==generation) say(e.message ?: "Please choose the location again.") }
            finally {if(serial==generation)Conversation.smartBusy(false)}
        }
    }
    private fun confirm() {
        val action=pending ?: run { say("Choose a location first."); return }
        if(hits.isNotEmpty()) { say("Choose a location by number first."); return }
        val model=vm ?: return
        try {
            if(planningRequest) {confirmPlanning(action);return}
            check(MockState.status.value.running && epoch==LiveSession.epoch && stopSnapshot==LiveSession.state.value.stops) { "The itinerary changed. Please repeat your instruction before confirming." }
            val plan=LiveSession.plan ?: error("No live itinerary")
            if (action.action in setOf("stay","extend") && action.target.isBlank()) check(plan.view().index == pendingIndex) { "The current stop changed. Please repeat your instruction." }
            when(action.action) {
                "add_saved","add_place" -> {
                    check(pendingDraft != null && model.continuation.state === pendingDraft) { "The addition changed. Please review it again." }
                    check(model.continuation.commit(start ?: error("Open Mirage first"))) { model.continuation.state?.error ?: "Could not add destination" }
                }
                "save_new" -> check(model.saveActiveScenario(action.target)) { model.error ?: "Could not save" }
                "save_changes" -> check(model.saveActiveScenario(plan.view().savedName,true)) { model.error ?: "Could not save changes" }
                "stay" -> {
                    val target=plan.view().let { it.stops.drop(it.index+1) }.singleOrNull { it.stop.name.equals(action.target,true) }
                    check(if(action.target.isBlank()) plan.setCurrentStay(action.minutes) else target!=null && plan.setStay(target.id,action.minutes)) { "The stop changed" }
                }
                "extend" -> check(plan.extendStay(action.minutes)) { "Current stop changed" }
                "remove","move" -> {
                    val view=plan.view(); val entries=view.stops.drop(view.index+1)
                    val entry=entries.single { it.stop.name.equals(action.target,true) }
                    check(if(action.action=="remove") plan.remove(entry.id) else plan.move(entry.id, action.position-1-entries.indexOf(entry))) { "The trip advanced. Try again." }
                }
            }
            if(action.action in setOf("add_saved","add_place","move","stay")) {
                val view=LiveSession.state.value
                val target=if(action.action.startsWith("add")) pendingDraft?.stops?.lastOrNull()?.name else action.target
                view.stops.drop(view.index+1).singleOrNull { it.stop.name.equals(target,true) }?.let { followUp.remember(it.id,LiveSession.epoch) }
            }
            pending=null; pendingDraft=null
            say(if(action.action.startsWith("save")) "Saved. Your simulation continues." else "Done. The itinerary is updated. Say save changes to keep it.")
        } catch(e: Exception) { cancel(); say(e.message ?: "Could not apply the change") }
    }
}
