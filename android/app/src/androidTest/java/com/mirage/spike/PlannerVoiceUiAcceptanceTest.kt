package com.mirage.spike

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mirage.spike.engine.*
import com.mirage.spike.store.PrefsScenarioStore
import com.mirage.spike.store.SavedScenario
import org.junit.*
import org.junit.Assert.*

/** Exercises the production planner and microphone control with deterministic recognition
 * results. Acoustic recognition and model download remain separate device checks.
 */
class PlannerVoiceUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val vmStore = ViewModelStore()
    private val vmOwner = object : ViewModelStoreOwner { override val viewModelStore = vmStore }
    private lateinit var vm: MirageViewModel
    private val remote = PlaceHit(LatLng(33.65, -117.73), "Western Digital (Irvine)",
        "Irvine, CA, USA (fixture)", "fixture-inline-western-digital")
    private val queries = mutableListOf<String>()
    private var starts = 0
    private fun place(id: String, name: String, point: LatLng) = SavedScenario(
        id, name, "SNAP", 1L, false, null, "", point, name,
        TravelMode.DRIVE, emptyMap(), Realism.REALISTIC, null, emptyList())
    private val home = place("inline-home", "Home", LatLng(33.5, -112.0))
    private val office = place("inline-office", "Office", LatLng(33.6, -112.1))

    @Before fun setup() {
        context.stopService(Intent(context, MockLocationService::class.java))
        context.stopService(Intent(context, VoiceService::class.java))
        context.getSharedPreferences("mirage_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        PrefsScenarioStore(context).save(listOf(home, office))
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
        PlannerSpeech.cancel(); Conversation.resetPlanningSession()
        queries.clear(); starts = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MirageViewModel(planInterpret = { text, _ ->
                    when {
                        text == "Plan the afternoon" -> PlanInterpretation(listOf(
                            PlanEdit("set_start", place = "saved:inline-home"),
                            PlanEdit("add_stop", place = "saved:inline-office", minutes = 30),
                            PlanEdit("add_stop", place = "Western Digital", minutes = 60),
                            PlanEdit("set_departure", value = "2026-10-20T13:00")))
                        text == "Make the first stay forty five minutes instead" -> PlanInterpretation(listOf(PlanEdit("set_stay", "stop:1", minutes = 45)))
                        text.startsWith("add ") -> PlanInterpretation(listOf(PlanEdit("add_stop", place = "saved:inline-${text.removePrefix("add ").lowercase()}")))
                        else -> PlanInterpretation(emptyList(), "Which part should change?")
                    }
                }) { _, query, _ ->
                    queries += query
                    if (query.contains("Western", true)) listOf(remote) else emptyList()
                } as T
            }
            vm = ViewModelProvider(vmOwner, factory)[MirageViewModel::class.java]
        }
    }

    @After fun cleanup() {
        compose.runOnIdle { PlannerSpeech.cancel(); Conversation.resetPlanningSession(); vmStore.clear() }
        context.stopService(Intent(context, VoiceService::class.java))
        context.stopService(Intent(context, MockLocationService::class.java))
        context.getSharedPreferences("mirage_recovery", Context.MODE_PRIVATE).edit().clear().commit()
        MockState.reset(); LiveSession.clear(); PlaybackSource.clearQueue()
        PlaybackSource.current = null; PlaybackSource.paused = false
    }

    private fun launchPlanner(largeText: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalViewModelStoreOwner provides vmOwner,
                LocalDensity provides Density(density.density, if (largeText) 1.3f else density.fontScale)) {
                MirageTheme {
                    Surface(Modifier.fillMaxSize()) {
                        MapScreen(onStartService = { starts++ }, onStopService = {},
                            onOpenDevSettings = {}, onRequestBattery = {}, onRequestPermissions = {},
                            setupChecks = { SetupChecks(false, false, true) }, hasLocPerm = false)
                    }
                }
            }
        }
        compose.onNodeWithTag("plannerDestinationInput").assertExists()
        compose.runOnIdle { vm.configureApi(ApiConfig(null, "fixture", "inline-voice-test")) }
    }

    private fun dictate(field: Field, text: String) {
        compose.runOnIdle {
            val request = PlannerSpeech.begin(field)
            PlannerSpeech.finish(request, text)
        }
        compose.waitUntil(5_000) { PlannerSpeech.state.value.target == null }
        compose.waitForIdle()
    }

    private fun assertBeside(fieldTag: String, microphoneTag: String) {
        compose.onNodeWithTag(fieldTag).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(microphoneTag).assertIsDisplayed().assertHasClickAction()
        val field = compose.onNodeWithTag(fieldTag).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithTag(microphoneTag).fetchSemanticsNode().boundsInRoot
        assertTrue("Microphone stays vertically beside its editable field", mic.center.y in field.top..field.bottom)
        assertTrue("Microphone stays at the field's right edge", mic.center.x > field.center.x && mic.right <= field.right + 1f)
        assertTrue("Editable text has space beside the microphone", field.width > mic.width + 16f)
    }

    private fun assertIdle() {
        assertEquals(0, starts)
        assertFalse(MockState.status.value.running)
        assertNull(LiveSession.plan)
        assertNull(PlaybackSource.current)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle(); device.waitForIdle()
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }

    @Test fun microphoneShowsMissingModelSetupAndReadyModelListensWithoutDownloading() {
        var ready by mutableStateOf(false)
        var listened = 0
        var downloaded = 0
        compose.setContent {
            MirageTheme {
                Column(Modifier.padding(24.dp)) {
                    PlannerMicrophone(onListen = { listened++ }, modifier = Modifier.testTag("fixtureMicrophone"),
                        contentDescription = "Dictate destination", voiceReady = ready,
                        modelStatus = if (ready) "Advanced voice ready" else "Advanced voice not downloaded",
                        onDownload = { downloaded++ }, onCancelDownload = {})
                }
            }
        }
        compose.onNodeWithTag("fixtureMicrophone").performClick()
        compose.onNodeWithTag("voiceSetup").assertIsDisplayed()
        compose.onNodeWithTag("voiceSetupStatus").assertTextContains("not downloaded", substring = true)
        compose.onNodeWithTag("voiceDownload").assertIsDisplayed()
        assertEquals(0, listened); assertEquals(0, downloaded)
        screenshot("planner-voice-model-setup")
        compose.onNodeWithTag("voiceSetupClose").performClick()
        compose.runOnIdle { ready = true }
        compose.onNodeWithTag("fixtureMicrophone").performClick()
        assertEquals(1, listened); assertEquals(0, downloaded)
        compose.onNodeWithTag("voiceSetup").assertDoesNotExist()
    }

    @Test fun microphonesStayWithFieldsAndDictationUsesTheSameLocationResults() {
        launchPlanner()
        assertBeside("plannerStartInput", "plannerMicStart")
        assertBeside("plannerDestinationInput", "plannerMicEnd")
        dictate(Field.START, "Home")
        compose.onNodeWithTag("plannerSavedSuggestion-inline-home").performScrollTo().performClick()
        compose.onNodeWithTag("plannerStartInput").assertTextContains("Home")
        dictate(Field.END, "Western Digital")
        compose.waitUntil(5_000) { vm.suggestions.singleOrNull() == remote }
        compose.onNodeWithTag("plannerDestinationInput").assertTextContains("Western Digital")
        compose.onNodeWithText(remote.name).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plannerClearEnd").performScrollTo().performClick()
        compose.onNodeWithTag("plannerDestinationInput").performClick().performTextInput("Western Digital")
        compose.waitUntil(5_000) { vm.suggestions.singleOrNull() == remote }
        compose.runOnIdle {
            assertTrue(queries.count { it == "Western Digital" } >= 2)
            assertEquals(listOf(remote), vm.suggestions.toList())
            assertEquals(home.dest, vm.start)
            assertIdle()
        }
        device.pressBack()
        compose.onNodeWithText("Itinerary").performScrollTo().performClick()
        assertBeside("plannerDestinationInput", "plannerMicEnd")
        screenshot("planner-inline-microphones")
    }

    private fun previewAddition(name: String) {
        dictate(Field.END, "add $name")
        compose.onNodeWithTag("plannerInputSubmit").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.planning.state.value.canConfirm }
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().assertIsDisplayed()
    }

    @Test fun sequentialVoiceInstructionsAppendStopsAndShowTheLatestAddition() {
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        previewAddition("Home")
        compose.runOnIdle { assertTrue(vm.stops.isEmpty()); assertIdle() }
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.stops.size == 1 }
        previewAddition("Office")
        compose.runOnIdle { assertEquals(listOf("Home"), vm.stops.map { it.name }); assertIdle() }
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.stops.size == 2 }
        compose.onNodeWithTag("plannerPreview").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plannerNewestStop-1").performScrollTo().assertIsDisplayed()
        screenshot("planner-voice-two-stops")
        compose.runOnIdle { assertEquals(listOf("Home", "Office"), vm.stops.map { it.name }); assertIdle() }
    }

    @Test fun clearingInputKeepsStopsAndNewItineraryClearsPendingVoiceAndRecovery() {
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        compose.runOnIdle {
            assertTrue(vm.pickSavedPlace(home, Field.END))
            assertTrue(vm.pickSavedPlace(office, Field.END))
        }
        dictate(Field.END, "unresolved place")
        compose.onNodeWithTag("plannerClearEnd").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Home", "Office"), vm.stops.map { it.name })
            assertTrue(vm.suggestions.isEmpty())
            assertFalse(PlannerSpeech.state.value.active)
            assertIdle()
        }
        previewAddition("Home")
        lateinit var previousDraft: SavedScenario
        var previousGeneration = -1L
        compose.runOnIdle {
            previousDraft = vm.draftSnapshot()!!
            previousGeneration = vm.draftGeneration
            TripRecovery.saveDraft(previousDraft)
            assertNotNull(TripRecovery.draft())
            val request = PlannerSpeech.begin(Field.END)
            PlannerSpeech.partial(request, "unfinished instruction")
        }
        compose.onNodeWithTag("plannerNewItinerary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plannerNewCancel").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Home", "Office"), vm.stops.map { it.name })
            assertNotNull(TripRecovery.draft())
        }
        compose.onNodeWithTag("plannerNewItinerary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plannerNewConfirm").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(PlanMode.ITINERARY, vm.planMode)
            assertTrue(vm.stops.isEmpty()); assertNull(vm.dest); assertFalse(vm.canSaveScenario)
            assertNull(vm.draftSavedName); assertFalse(vm.draftUndoAvailable)
            assertFalse(SmartVoice.needsReply()); assertTrue(Conversation.state.value.lines.isEmpty())
            assertFalse(PlannerSpeech.state.value.active); assertNull(PlannerSpeech.state.value.target)
            assertTrue(TripRecovery.draft()?.stops.orEmpty().isEmpty())
            assertFalse(TripRecovery.saveDraftIfCurrent(previousDraft, previousGeneration))
            assertTrue(TripRecovery.draft()?.stops.orEmpty().isEmpty())
            assertEquals(setOf("Home", "Office"), PrefsScenarioStore(context).load().map { it.name }.toSet())
            assertIdle()
        }
        screenshot("planner-new-itinerary")
    }

    @Test fun keyboardAndLargerTextKeepNextStopMicrophoneAccessible() {
        launchPlanner(largeText = true)
        compose.onNodeWithText("Itinerary").performClick()
        compose.runOnIdle { vm.pickSavedPlace(home, Field.END); vm.pickSavedPlace(office, Field.END) }
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick().performTextInput("Western")
        assertBeside("plannerDestinationInput", "plannerMicEnd")
        compose.onNodeWithTag("plannerClearEnd").assertIsDisplayed()
        screenshot("planner-voice-large-text-keyboard")
        compose.runOnIdle { assertEquals(listOf("Home", "Office"), vm.stops.map { it.name }); assertIdle() }
        device.pressBack()
    }

    @Test fun wholePlanReviewAcceptsCorrectionAndUndoRestoresEveryDetail() {
        launchPlanner()
        val before = vm.capturePlanner()
        dictate(Field.END, "Plan the afternoon")
        compose.onNodeWithTag("plannerInputSubmit").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.planning.state.value.canConfirm }
        compose.onNodeWithText("2. Western Digital (Irvine)").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(vm.stops.isEmpty()); assertNull(vm.departureMillis); assertIdle()
        }
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick()
            .performTextReplacement("Make the first stay forty five minutes instead")
        device.pressBack()
        compose.onNodeWithTag("plannerInputSubmit").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.planning.state.value.canConfirm && vm.planning.state.value.candidate?.preview?.firstOrNull()?.stayMinutes == 45 }
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(home.dest, vm.start)
            assertEquals(listOf("Office", remote.name), vm.stops.map { it.name })
            assertEquals(listOf(45, 60), vm.stops.map { it.dwellMinutes })
            assertNotNull(vm.departureMillis); assertIdle()
        }
        screenshot("planner-contextual-whole-plan")
        compose.onNodeWithText("Undo", useUnmergedTree = true).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(before.scenario, vm.capturePlanner().scenario)
            assertFalse(vm.draftUndoAvailable); assertIdle()
        }
    }

    @Test fun interruptedSpeechKeepsEditableWordsAndDoesNotChangePlan() {
        launchPlanner()
        compose.runOnIdle {
            val request = PlannerSpeech.begin(Field.END)
            PlannerSpeech.partial(request, "Plan an afternoon starting at Home")
        }
        compose.onNodeWithTag("plannerDestinationInput").assertTextContains("Plan an afternoon starting at Home")
        compose.runOnIdle { PlannerSpeech.interrupt(PlannerSpeech.state.value.requestId) }
        compose.waitUntil(5_000) { PlannerSpeech.state.value.target == null }
        compose.onNodeWithTag("plannerDestinationInput").assertTextContains("Plan an afternoon starting at Home")
        compose.runOnIdle {
            assertTrue(vm.stops.isEmpty()); assertNull(vm.dest); assertIdle()
            assertEquals("Plan an afternoon starting at Home", readPlannerEntry("end", ""))
        }
    }

    @Test fun typingSaveInstructionCharacterByCharacterPreservesBothRouteEndpoints() {
        launchPlanner()
        compose.runOnIdle {
            assertEquals(PlanMode.ROUTE, vm.planMode)
            assertTrue(vm.pickSavedPlace(home, Field.START))
            assertTrue(vm.pickSavedPlace(office, Field.END))
        }
        fun assertEndpoints() {
            assertEquals(home.dest, vm.start); assertEquals("Home", vm.startName)
            assertEquals(office.dest, vm.dest); assertEquals("Office", vm.destName)
            assertIdle()
        }
        fun typeAndSave(fieldTag: String, name: String) {
            val input = compose.onNodeWithTag(fieldTag)
            // Replace the displayed place by typing into the field, without pressing Clear.
            input.performScrollTo().performClick().performTextReplacement("")
            compose.runOnIdle { assertEndpoints() }
            val command = "save this trip as $name"
            command.forEach { character ->
                input.performTextInput(character.toString())
                // In particular, s / sa / sav must not discard a resolved endpoint.
                compose.runOnIdle { assertEndpoints() }
            }
            input.assertTextContains(command)
            device.pressBack()
            compose.onNodeWithTag("plannerInputSubmit").performScrollTo().performClick()
            compose.waitUntil(5_000) { SmartVoice.needsReply() }
            compose.onNodeWithTag("plannerVoiceConfirm").performScrollTo().performClick()
            compose.waitUntil(5_000) { PrefsScenarioStore(context).load().any { it.name == name } }
            val saved = PrefsScenarioStore(context).load().single { it.name == name }
            assertEquals("ROUTE", saved.kind)
            assertEquals(home.dest, saved.start); assertEquals("Home", saved.startName)
            assertEquals(office.dest, saved.dest); assertEquals("Office", saved.destName)
            compose.runOnIdle { assertEndpoints() }
        }
        typeAndSave("plannerDestinationInput", "Friday")
        compose.runOnIdle { Conversation.clearPlannerFeedback() }
        typeAndSave("plannerStartInput", "Saturday")
        assertEquals(setOf("Friday", "Saturday"), PrefsScenarioStore(context).load()
            .filter { it.kind == "ROUTE" }.map { it.name }.toSet())
    }
}
