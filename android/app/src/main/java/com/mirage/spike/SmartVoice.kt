package com.mirage.spike

import android.content.Context
import com.mirage.spike.engine.*
import com.mirage.spike.store.SavedScenario
import com.mirage.spike.audit.*
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
    private data class PlanningTarget(val field: Field, val mode: PlanMode)
    private var planningTarget: PlanningTarget? = null
    private var auditOperation: Long? = null
    private fun audit(outcome: AuditOutcome, control: AuditControl, reason: AuditReason = AuditReason.NONE,
        operation: Long? = auditOperation) {
        AuditTrail.record(AuditAction.VOICE, outcome, vm?.auditState(),
            AuditDetails(reason = reason, source = AuditSource.VOICE, control = control), operation)
    }
    fun needsReply() = pending != null || hits.isNotEmpty()
    fun hasChoices() = hits.isNotEmpty()
    fun bindPlanner(model: MirageViewModel) {
        if (vm !== model) resetPlanningSession()
        vm = model
    }
    fun bind(context: Context, model: MirageViewModel, onStart: () -> Unit) {
        bindPlanner(model)
        start = onStart
        LocalLanguageModel.configure(context)
        if (instructions.isBlank()) instructions = context.assets.open("voice-intent-prompt.txt").bufferedReader().use { it.readText() }
    }
    fun cancel() {
        if (auditOperation != null) audit(AuditOutcome.CANCELLED, AuditControl.CLOSE)
        auditOperation = null
        generation++; job?.cancel(); job=null
        if (pendingDraft != null && vm?.continuation?.state === pendingDraft) vm?.continuation?.cancel()
        pending=null; pendingDraft=null; hits=emptyList();planningRequest=false;planningSnapshot=null;planningSaved=null;planningHit=null;planningTarget=null
        Conversation.smartChoices(emptyList()); Conversation.smartBusy(false)
    }
    fun resetPlanningSession() {
        cancel()
        followUp.clear()
        epoch = -1L; pendingIndex = -1; stopSnapshot = emptyList(); spoken = false
    }
    private fun say(text: String) = Conversation.smartReply(text,spoken)
    /** Field-scoped input never invokes the Conversation journey path that starts playback. */
    fun handlePlanning(text: String, field: Field, planMode: PlanMode, isSpoken: Boolean): Boolean {
        val model = vm ?: return false
        if (MockState.status.value.let { it.running || it.starting }) {
            cancel(); spoken = isSpoken
            say("Use the live itinerary controls while a simulation is running.")
            return true
        }
        val target = PlanningTarget(field, planMode)
        if (model.planMode != planMode || (needsReply() && (planningTarget != target || model.activeField != field))) {
            cancel(); spoken = isSpoken
            say("The planning field changed. Repeat the instruction for this field.")
            return true
        }
        if (!needsReply()) model.activeField = field
        val normalized = CommandParser.normalize(text)
        val parsed = CommandParser.parse(text)
        if (parsed == SpokenCommand.Cancel || normalized in setOf("no", "no thanks")) {
            cancel(); spoken = isSpoken; say("Cancelled. The plan is unchanged."); return true
        }
        if ((parsed is SpokenCommand.Journey && parsed.legs.size != 1) ||
            Regex("(?i)\\b(?:and|then|also)\\s+(?:drive|walk|bike|cycle|fly|go|take|snap|add|remove|save|start)\\b").containsMatchIn(text) ||
            parsed in listOf(SpokenCommand.Stop, SpokenCommand.ClarifyStop, SpokenCommand.Pause, SpokenCommand.Resume, SpokenCommand.Skip)) {
            cancel(); spoken = isSpoken
            say("Make one planning change at a time. Choose a place for this field, then use Start when ready.")
            return true
        }
        val handled = handle(text, isSpoken, target)
        if (!handled) cancel()
        return handled
    }
    fun handle(text: String, isSpoken: Boolean): Boolean = handle(text, isSpoken, null)
    private fun handle(text: String, isSpoken: Boolean, target: PlanningTarget?): Boolean {
        val model = vm ?: return false
        val normalized = CommandParser.normalize(text)
        if (normalized in setOf("yes","confirm","do it","save it","yes please")) {
            if (!needsReply()) return false
            spoken=isSpoken; confirm(); return true
        }
        if(normalized in setOf("add this stop at the end","add this stop at end","put this stop at the end") && pending?.action in setOf("add_place","add_saved") && pendingDraft!=null) {
            spoken=isSpoken
            pending=pending?.copy(placement=Placement.END,afterStopId=null)
            pendingDraft=null
            model.continuation.placement(Placement.END)
            job?.cancel()
            job=scope.launch {
                try {finishPreview()} catch(e: CancellationException) {throw e} catch(e: Exception) {cancel();say(e.message ?: "Please review the addition again.")}
            }
            return true
        }
        if (normalized in setOf("no","no thanks")) {
            if (!needsReply()) return false
            cancel(); spoken=isSpoken; say("Cancelled. Your trip is unchanged."); return true
        }
        if (hasChoices()) {
            val choice = CommandParser.parse(text) as? SpokenCommand.Choice
            if (choice != null) { choose(choice.ordinal-1); return true }
        }
        val afterMatch=Regex("^after (.+?),? ((?:go|drive|walk) to .+)$",RegexOption.IGNORE_CASE).matchEntire(text.trim())
        val commandText=afterMatch?.groupValues?.get(2) ?: text
        val another=Regex("^stay (?:another|an additional) (.+)$",RegexOption.IGNORE_CASE).matchEntire(commandText.trim())
        val parsed=CommandParser.parse(if(another!=null) "extend stay by "+another.groupValues[1] else commandText)
        val currentView=LiveSession.state.value
        val contextIntent=if (target == null) followUp.resolve(text,currentView.stops,currentView.index,LiveSession.epoch) else null
        val afterName=afterMatch?.groupValues?.get(1)?.trim()?.trimEnd(',')
        val afterId=if(afterName!=null) currentView.stops.drop(currentView.index.coerceAtLeast(0)).singleOrNull { VoiceNameMatcher.matches(it.stop.name, afterName) }?.id else null
        if(afterName!=null && afterId==null) {
            audit(AuditOutcome.BLOCKED, AuditControl.EDIT, AuditReason.MISSING_ITEM, AuditTrail.newOperation())
            spoken=isSpoken;say("Name one current or upcoming stop to add after.");return true
        }
        val savedMatches=model.savedScenarios.filter { item ->
            (listOf(item.name)+item.aliases).any { alias -> VoiceNameMatcher.mentioned(alias, commandText) }
        }
        val adding=(target != null && parsed is SpokenCommand.Journey) || Regex("^(?:please )?(?:add|take me to|go to|after |next|drive to|walk to)",RegexOption.IGNORE_CASE).containsMatchIn(text.trim())
        val singleTarget=(target != null && parsed is SpokenCommand.Journey && parsed.legs.size == 1) || !Regex("(?i)\\b(and|then|also)\\b").containsMatchIn(text)
        val savedIntent=if(adding && savedMatches.size==1 && singleTarget) VoiceIntent("add_saved",savedMatches.single().name,
            if(Regex("(?i)\\b(now|immediately)\\b").containsMatchIn(text)) Placement.NOW else if(text.contains("end",true)) Placement.END else Placement.NEXT,minutes=(parsed as? SpokenCommand.Journey)?.legs?.singleOrNull()?.minutes ?: 0,travelMode=(parsed as? SpokenCommand.Journey)?.legs?.singleOrNull()?.mode) else null
        val save = Regex("^(?:save (?:this |the )?(?:whole )?(?:trip|itinerary) as) (.+)$", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        val direct = (contextIntent ?: savedIntent ?: if (save != null) VoiceIntent("save_new",save.groupValues[1].trim())
            else if (normalized in setOf("save changes","save my itinerary","save this itinerary")) VoiceIntent("save_changes")
            else when(parsed) {
                is SpokenCommand.Journey -> if(parsed.legs.size==1 && (!parsed.snap || target != null)) VoiceIntent("add_place",parsed.legs.single().query,if(normalized.contains("now"))Placement.NOW else Placement.NEXT,minutes=parsed.legs.single().minutes,travelMode=parsed.legs.single().mode) else null
                is SpokenCommand.Stay -> VoiceIntent("stay",minutes=parsed.minutes)
                is SpokenCommand.Extend -> VoiceIntent("extend",minutes=parsed.minutes)
                else -> null
            }
        ).let { intent -> if(afterId!=null)intent?.copy(afterStopId=afterId) else intent }
        if (!LocalLanguageModel.ready && direct == null) return false
        Conversation.cancelPending()
        cancel(); spoken=isSpoken
        planningTarget = target
        val serial = generation
        val operation = AuditTrail.newOperation()
        auditOperation = operation
        audit(AuditOutcome.REQUESTED, AuditControl.EDIT, operation = operation)
        epoch = LiveSession.epoch; stopSnapshot = LiveSession.state.value.stops
        job = scope.launch {
            Conversation.smartBusy(true)
            try {
                val current = LiveSession.state.value
                val catalog = JSONArray(model.savedScenarios.sortedByDescending { item -> text.lowercase().split(" ").count { word -> word.length>2 && item.name.contains(word,true) } }.take(40).map { it.name })
                val upcoming = JSONArray(current.stops.drop(current.index+1).map { it.stop.name })
                val user = "Saved catalog: $catalog\nUpcoming stops: $upcoming\nInstruction: " + JSONObject.quote(text.take(600))
                val intent = direct ?: VoiceIntent.parse(LocalLanguageModel.interpret(instructions, user))
                ensureActive(); if (serial != generation) return@launch
                check(epoch == LiveSession.epoch) { "The simulation changed. Please repeat your instruction." }
                val view = LiveSession.state.value
                val normalizedIntent = VoiceNameMatcher.bind(intent, model.savedScenarios.map { it.name },
                    view.stops.drop(view.index + 1).map { it.stop.name })
                check(direct != null || normalizedIntent.groundedIn(text)) { "Please say the actual saved name or full destination; I won't guess what 'there' means." }
                prepare(normalizedIntent)
                ensureActive(); if (serial != generation) return@launch
                if (serial == generation && auditOperation == operation) {
                    audit(AuditOutcome.SUCCEEDED, AuditControl.EDIT, operation = operation)
                    if (!needsReply()) auditOperation = null
                }
            } catch (e: CancellationException) {
                audit(AuditOutcome.CANCELLED, AuditControl.EDIT, operation = operation)
                throw e
            }
            catch (e: Throwable) { if (serial == generation) {
                audit(AuditOutcome.FAILED, AuditControl.EDIT, auditReason(e), operation)
                auditOperation = null
                if (pending != null) model.continuation.cancel()
                cancel(); say(e.message ?: "Could not understand that instruction. Try one change at a time.")
            } }
            finally { if (serial == generation) Conversation.smartBusy(false) }
        }
        return true
    }
    private suspend fun prepare(intent: VoiceIntent) {
        val model=vm ?: return
        if (intent.action == "clarify") {
            audit(AuditOutcome.BLOCKED, AuditControl.EDIT, AuditReason.INVALID_INPUT)
            auditOperation = null
            say("Please name the saved item or destination and make one change at a time."); return
        }
        if (planningTarget != null) {
            check(MockState.status.value.let { !it.running && !it.starting }) { "The simulation changed. Return to the planner after stopping it." }
            preparePlanning(intent)
            return
        }
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
                intent.afterStopId?.let{model.continuation.afterStop(it)}
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
                intent.afterStopId?.let{model.continuation.afterStop(it)}
                intent.travelMode?.let{model.continuation.mode(it)}
                val origin=model.continuation.origin()
                check(model.hasKey) { "Add your Maps key in Setup to search." }
                hits=findPlaces(model,intent.target,origin)
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
                val target = planningTarget
                if (target != null && (target.field == Field.START || target.mode != PlanMode.ITINERARY)) {
                    check(planningSaved!!.kind == "SNAP") { "Choose one saved place for this field; routes and itineraries contain multiple places." }
                }
                say(planningPrompt(intent.target))
            }
            "add_place" -> {
                check(model.hasKey){"Add a Maps key to search"}
                hits=findPlaces(model,intent.target,model.tripStart())
                check(hits.isNotEmpty()){"No match; name the place and city"}
                if(hits.size==1){planningHit=hits.single();hits=emptyList();say(planningPrompt("${planningHit!!.name}, ${planningHit!!.address}"))}
                else {Conversation.smartChoices(hits);say("Choose the correct place by number, then review its address.")}
            }
            "save_new" -> say("Save the planned itinerary as ${intent.target}? Say yes or cancel.")
            "save_changes" -> {check(model.draftSavedName!=null){"Name this itinerary first: save this trip as followed by a name"};say("Update ${model.draftSavedName} with the current plan? Say yes or cancel.")}
            else -> {
                audit(AuditOutcome.BLOCKED, AuditControl.EDIT, AuditReason.NOT_READY)
                auditOperation = null
                cancel();say("This command needs a running trip. You can add a saved place or save the plan before starting.")
            }
        }
    }
    private fun confirmPlanning(action: VoiceIntent) {
        val model=vm ?: return
        check(MockState.status.value.let { !it.running && !it.starting } && epoch==LiveSession.epoch && planningSnapshot==model.draftSnapshot()) {"The plan changed. Repeat the instruction before confirming."}
        val target = planningTarget
        check(target == null || (model.planMode == target.mode && model.activeField == target.field)) { "The planning field changed. Repeat the instruction before confirming." }
        when(action.action) {
            "add_saved" -> {
                val item=planningSaved ?: error("Choose an item")
                check(model.savedScenarios.any{it==item}){"The saved item changed; review it again"}
                if (target != null && item.kind == "SNAP") {
                    if (target.field == Field.END) action.travelMode?.let { model.chooseMode(it) }
                    check(model.pickSavedPlace(item, target.field, AuditSource.VOICE)) { model.error ?: "Choose a saved place" }
                    applyPlanningStay(model, target, action.minutes)
                } else when(item.kind) {
                    "ROUTE" -> check(model.appendSavedRoute(item,true)){model.error ?: "Could not add route"}
                    "ITINERARY" -> model.appendSavedItinerary(item)
                    else -> {action.travelMode?.let{model.chooseMode(it)};model.appendResolvedStop(PlaceHit(item.dest ?: error("Missing destination"),item.name,item.destAddress,item.destPlaceId),action.minutes)}
                }
            }
            "add_place" -> {
                val hit = planningHit ?: error("Choose a place")
                if (target == null) { action.travelMode?.let{model.chooseMode(it)};model.appendResolvedStop(hit,action.minutes) }
                else {
                    if (target.field == Field.END) action.travelMode?.let { model.chooseMode(it) }
                    model.pickResolvedPlace(hit, target.field)
                    applyPlanningStay(model, target, action.minutes)
                }
            }
            "save_new" -> check(model.saveScenario(action.target)){model.error ?: "Could not save"}
            "save_changes" -> check(model.saveScenario(model.draftSavedName ?: error("Name the itinerary"),true)){model.error ?: "Could not save"}
        }
        auditOperation = null // Confirmation completes this operation; clearing pending state is not cancellation.
        cancel();say("Done. The plan is updated; simulation is still off.")
    }
    private fun applyPlanningStay(model: MirageViewModel, target: PlanningTarget, minutes: Int) {
        if (target.field != Field.END || minutes <= 0) return
        if (target.mode == PlanMode.ITINERARY) model.setDwell(model.stops.lastIndex, minutes)
        else model.defaultStayMinutes = minutes
    }
    private fun planningPrompt(name: String): String = when (planningTarget?.field) {
        Field.START -> "Use $name as the start? Simulation stays off. Say yes or cancel."
        Field.END -> if (planningTarget?.mode == PlanMode.ITINERARY) "Add $name as the next stop? Simulation stays off. Say yes or cancel."
            else "Use $name as the destination? Simulation stays off. Say yes or cancel."
        null -> "Add $name to the planned itinerary, including a connecting leg if needed? Simulation stays off. Say yes or cancel."
    }
    private suspend fun findPlaces(model: MirageViewModel, query: String, origin: LatLng?): List<PlaceHit> {
        val serial = generation
        val found = model.placeSearch(model.api, query, origin).take(5)
        currentCoroutineContext().ensureActive()
        if (serial != generation) throw CancellationException("Planning request was replaced")
        return found
    }
    private suspend fun finishPreview() {
        val model=vm ?: return
        model.continuation.edit(false)
        withTimeout(35_000) { while(model.continuation.state?.previewBusy == true) delay(100) }
        currentCoroutineContext().ensureActive()
        val d=model.continuation.state ?: error("The addition was cancelled.")
        check(d.ready) { d.error ?: "Could not prepare the route." }
        pendingDraft=d
        val names=d.stops.joinToString { it.name + if(it.address.isBlank()) "" else ", ${it.address}" }
        say("${model.continuation.originLabel(d)}: $names. Existing stops stay in your trip. Say yes or cancel.")
    }
    fun choose(index: Int) {
        val hit=hits.getOrNull(index) ?: return
        val model=vm ?: return
        hits=emptyList(); Conversation.smartChoices(hits)
        if(planningRequest) {
            planningHit=hit
            say(planningPrompt("${hit.name}, ${hit.address}"))
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
        val operation = auditOperation ?: AuditTrail.newOperation()
        audit(AuditOutcome.REQUESTED, AuditControl.CONFIRM, operation = operation)
        val action=pending ?: run {
            audit(AuditOutcome.BLOCKED, AuditControl.CONFIRM, AuditReason.NOT_READY, operation)
            say("Choose a location first."); return
        }
        if(hits.isNotEmpty()) {
            audit(AuditOutcome.BLOCKED, AuditControl.CONFIRM, AuditReason.NOT_READY, operation)
            say("Choose a location by number first."); return
        }
        val model=vm ?: return
        try {
            if(planningRequest) {
                confirmPlanning(action)
                audit(AuditOutcome.SUCCEEDED, AuditControl.CONFIRM, operation = operation)
                return
            }
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
            audit(AuditOutcome.SUCCEEDED, AuditControl.CONFIRM, operation = operation)
            auditOperation = null
            say(if(action.action.startsWith("save")) "Saved. Your simulation continues." else "Done. The itinerary is updated. Say save changes to keep it.")
        } catch(e: Exception) {
            audit(AuditOutcome.FAILED, AuditControl.CONFIRM, auditReason(e), operation)
            auditOperation = null
            cancel(); say(e.message ?: "Could not apply the change")
        }
    }
}
