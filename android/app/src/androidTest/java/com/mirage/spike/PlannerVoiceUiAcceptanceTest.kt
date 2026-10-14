package com.mirage.spike

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
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
    private var interpreterCalls = 0
    private lateinit var hidePlannerKeyboard: () -> Unit
    private lateinit var plannerHostView: android.view.View
    private var plannerVisible by mutableStateOf(true)
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
        queries.clear(); starts = 0; interpreterCalls = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = MirageViewModel(planInterpret = { text, _ ->
                    interpreterCalls++
                    check(LocalLanguageModel.interpretationEnabled) { "Offline planning must never invoke interpretation" }
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
        if (!LocalLanguageModel.interpretationEnabled) assertEquals("Offline UI reached interpretation", 0, interpreterCalls)
    }

    private fun launchPlanner(largeText: Boolean = false, compactViewport: Boolean = false) {
        compose.setContent {
            val density = LocalDensity.current
            val focus = LocalFocusManager.current
            val keyboard = LocalSoftwareKeyboardController.current
            val hostView = LocalView.current
            SideEffect {
                hidePlannerKeyboard = { focus.clearFocus(); keyboard?.hide() }
                plannerHostView = hostView
            }
            CompositionLocalProvider(LocalViewModelStoreOwner provides vmOwner,
                LocalDensity provides Density(density.density, if (largeText) 1.3f else density.fontScale)) {
                MirageTheme {
                    // Constrain the app viewport, while its own scaffold still consumes
                    // the emulator's real IME insets. This is not a physical-device test.
                    Surface((if (compactViewport) Modifier.width(360.dp).height(640.dp) else Modifier.fillMaxSize())
                        .testTag("plannerTestViewport")) {
                        if (plannerVisible) MapScreen(onStartService = { starts++ }, onStopService = {},
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

    private fun dismissPlannerKeyboard() {
        // Back can finish this test Activity when a preceding action already hid the IME.
        compose.runOnIdle { hidePlannerKeyboard() }
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

    @Test fun offlineHomeSearchEnterSaveAndStartNewStayExplicit() {
        assertFalse(LocalLanguageModel.interpretationEnabled)
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        dictate(Field.START, "Office")
        compose.onNodeWithTag("plannerSavedSuggestion-inline-office").performScrollTo().performClick()
        dictate(Field.END, "Home")
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performImeAction()
        compose.onNodeWithTag("plannerSavedSuggestion-inline-home").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plannerReviewApply").assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(vm.stops.isEmpty()); assertEquals(office.dest, vm.start)
            assertEquals(0, interpreterCalls); assertIdle()
        }
        compose.onNodeWithTag("plannerSavedSuggestion-inline-home").performClick()
        compose.runOnIdle { assertEquals(listOf("Home"), vm.stops.map { it.name }); assertIdle() }
        dictate(Field.END, "unfinished search")
        compose.onNodeWithTag("plannerClearEnd").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Home"), vm.stops.map { it.name })
            assertTrue(vm.suggestions.isEmpty()); assertFalse(PlannerSpeech.state.value.active); assertIdle()
        }
        dismissPlannerKeyboard()
        compose.onNode(hasText("Save") and hasAnyAncestor(hasTestTag("plannerFooter")))
            .assertIsDisplayed().performClick()
        compose.onNodeWithTag("savedPlanName").performTextReplacement("Offline Saturday")
        compose.onNode(hasText("Save") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.waitUntil(5_000) { PrefsScenarioStore(context).load().any { it.name == "Offline Saturday" } }
        val saved = PrefsScenarioStore(context).load().single { it.name == "Offline Saturday" }
        assertEquals("ITINERARY", saved.kind)
        assertEquals(office.dest, saved.start); assertEquals(listOf("Home"), saved.stops.map { it.name })
        // Dismiss the library, which remains open after saving, using its normal close action.
        compose.onNode(hasText("Done") and hasAnyAncestor(isDialog())).performScrollTo().performClick()
        compose.onNodeWithTag("plannerDestinationInput").assertExists()
        var lateRequest = 0L
        compose.runOnIdle {
            lateRequest = PlannerSpeech.begin(Field.END)
            PlannerSpeech.partial(lateRequest, "words from the old itinerary")
            assertIdle()
        }
        compose.onNodeWithTag("plannerNewItinerary").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plannerNewCancel").performClick()
        compose.runOnIdle { assertEquals(listOf("Home"), vm.stops.map { it.name }); assertIdle() }
        compose.onNodeWithTag("plannerNewItinerary").performClick()
        compose.onNodeWithTag("plannerNewConfirm").performClick()
        compose.runOnIdle {
            PlannerSpeech.finish(lateRequest, "late words from the old itinerary")
            assertTrue(vm.stops.isEmpty()); assertNull(vm.dest); assertFalse(vm.canSaveScenario)
            assertNull(PlannerSpeech.state.value.target); assertFalse(PlannerSpeech.state.value.active)
            assertTrue(TripRecovery.draft()?.stops.orEmpty().isEmpty())
            assertEquals(setOf("Home", "Office", "Offline Saturday"), PrefsScenarioStore(context).load().map { it.name }.toSet())
            assertEquals(0, interpreterCalls); assertIdle()
        }
        assertEquals("", compose.onNodeWithTag("plannerDestinationInput").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        screenshot("offline-home-save-start-new")
    }

    @Test fun settingsCannotStartWithUnresolvedTextOrAnActiveRecording() {
        assertFalse(LocalLanguageModel.interpretationEnabled)
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        dictate(Field.END, "Home")
        compose.onNodeWithTag("plannerSavedSuggestion-inline-home").performScrollTo().performClick()
        compose.onNodeWithTag("plannerPrimary").assertIsEnabled()

        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performTextInput("unfinished location")
        dismissPlannerKeyboard()
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.onNodeWithTag("plannerSettingsOpen").performClick()
        compose.onNodeWithTag("plannerSettingsPrimary").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("Home"), vm.stops.map { it.name }); assertIdle() }
        compose.onNodeWithText("Back to map").performScrollTo().performClick()
        compose.onNodeWithTag("plannerClearEnd").performScrollTo().performClick()
        compose.onNodeWithTag("plannerPrimary").assertIsEnabled()
        compose.onNodeWithTag("plannerSettingsOpen").performClick()
        compose.onNodeWithTag("plannerSettingsPrimary").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Back to map").performScrollTo().performClick()

        var recording = 0L
        compose.runOnIdle {
            recording = PlannerSpeech.begin(Field.END)
            PlannerSpeech.partial(recording, "unfinished recording")
        }
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.onNodeWithTag("plannerSettingsOpen").performClick()
        compose.onNodeWithTag("plannerSettingsPrimary").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertIdle() }
        compose.onNodeWithText("Back to map").performScrollTo().performClick()
        compose.onNodeWithTag("plannerClearEnd").performScrollTo().performClick()
        compose.runOnIdle {
            PlannerSpeech.finish(recording, "late words must not re-open the entry")
            assertFalse(PlannerSpeech.state.value.active)
            assertEquals(listOf("Home"), vm.stops.map { it.name })
            assertEquals(0, interpreterCalls)
            assertIdle()
        }
        compose.onNodeWithTag("plannerPrimary").assertIsEnabled()
        compose.onNodeWithTag("plannerSettingsOpen").performClick()
        compose.onNodeWithTag("plannerSettingsPrimary").performScrollTo().assertIsEnabled()
        screenshot("offline-settings-start-guards")
    }

    @ExperimentalLanguageModel
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
        dismissPlannerKeyboard()
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

    @ExperimentalLanguageModel
    @Test fun sequentialVoiceInstructionsAppendStopsAndShowTheLatestAddition() {
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        previewAddition("Home")
        compose.runOnIdle { assertTrue(vm.stops.isEmpty()); assertIdle() }
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().performClick()
        assertEquals("", compose.onNodeWithTag("plannerDestinationInput").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("Start itinerary").assertIsEnabled()
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

    @ExperimentalLanguageModel
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
        dismissPlannerKeyboard()
    }

    @Test fun mapPreviewPreservesSelectedPlanAndUnfinishedEntryWithoutStarting() {
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        compose.runOnIdle {
            assertTrue(vm.pickSavedPlace(home, Field.START))
            assertTrue(vm.pickSavedPlace(office, Field.END))
            assertTrue(vm.pickSavedPlace(home, Field.END))
        }
        compose.onNodeWithTag("plannerPrimary").assertIsEnabled()
        val unfinished = "unfinished next destination"
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick()
            .performTextInput(unfinished)
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.onNodeWithTag("plannerMapToggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plannerPanel").assertDoesNotExist()
        compose.onNodeWithTag("plannerDestinationInput").assertDoesNotExist()
        compose.onNodeWithTag("plannerCollapsedSummary").assertIsDisplayed()
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(home.dest, vm.start)
            assertEquals(listOf("Office", "Home"), vm.stops.map { it.name })
            assertEquals(unfinished, readPlannerEntry("end", ""))
            assertIdle()
        }
        screenshot("planner-map-preview-pending-entry")
        compose.onNodeWithTag("plannerMapToggle").assertIsDisplayed().performClick()
        compose.onNodeWithTag("plannerPanel").assertIsDisplayed()
        compose.onNodeWithTag("plannerStartInput").performScrollTo().assertTextContains("Home")
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().assertTextContains(unfinished)
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(home.dest, vm.start)
            assertEquals(listOf("Office", "Home"), vm.stops.map { it.name })
            assertEquals(unfinished, readPlannerEntry("end", ""))
            assertIdle()
        }
        screenshot("planner-edit-restores-pending-entry")
    }

    @Test fun plannerSettingsOpensCarHelpAndReturnsToTheUnchangedDraft() {
        launchPlanner()
        compose.onNodeWithText("Itinerary").performClick()
        val before = compose.runOnIdle {
            assertTrue(vm.pickSavedPlace(home, Field.START))
            assertTrue(vm.pickSavedPlace(office, Field.END))
            vm.capturePlanner()
        }
        val unfinished = "Another place to find"
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick()
            .performTextInput(unfinished)
        dismissPlannerKeyboard()
        compose.onNodeWithTag("plannerSettingsOpen").performClick()
        compose.onNodeWithTag("plannerCarHelp").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Android Auto & Wi-Fi").assertIsDisplayed()
        screenshot("planner-car-help-open")
        compose.onNodeWithText("Wireless Android Auto uses Wi-Fi.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A car can use a separate location source.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("carWifiSettings").performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.runOnIdle { assertIdle() }
        screenshot("planner-car-help-wifi-action")
        // The injected-card test verifies the Settings callback. This journey keeps
        // actual system Wi-Fi settings closed and verifies production navigation.
        compose.onNodeWithText("Done").performScrollTo().performClick()
        compose.onNodeWithTag("carWifiSettings").assertDoesNotExist()
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().assertTextContains(unfinished)
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(before, vm.capturePlanner().copy(activeField = before.activeField))
            assertEquals(unfinished, readPlannerEntry("end", ""))
            assertIdle()
        }
    }

    @Test fun compactPlannerKeepsNextStopAndSearchResultAboveFooterWithKeyboard() {
        launchPlanner(largeText = true, compactViewport = true)
        compose.onNodeWithTag("plannerTestViewport").assertWidthIsEqualTo(360.dp).assertHeightIsEqualTo(640.dp)
        compose.onNodeWithText("Itinerary").performClick()
        compose.runOnIdle {
            assertTrue(vm.pickSavedPlace(home, Field.START))
            repeat(6) { index -> assertTrue(vm.pickSavedPlace(if (index % 2 == 0) office else home, Field.END)) }
        }
        val input = compose.onNodeWithTag("plannerDestinationInput")
        input.performScrollTo().performClick().performTextInput("Western")
        // Text injection alone does not establish a displayed system keyboard.
        // Tap the actual editor again, as in the full-Activity large-text journey.
        input.performClick()
        screenshot("planner-compact-search-before-ime-check")
        compose.waitUntil(15_000) {
            compose.runOnUiThread {
                plannerHostView.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            }
        }
        compose.waitUntil(5_000) { vm.suggestions.singleOrNull() == remote }
        val result = compose.onNodeWithText(remote.name)
        result.performScrollTo().assertIsDisplayed()
        input.assertIsDisplayed().assertTextContains("Western")
        val microphone = compose.onNodeWithTag("plannerMicEnd").assertIsDisplayed().assertHasClickAction()
        val footer = compose.onNodeWithTag("plannerFooter").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val fieldBounds = input.fetchSemanticsNode().boundsInRoot
        val micBounds = microphone.fetchSemanticsNode().boundsInRoot
        val resultBounds = result.fetchSemanticsNode().boundsInRoot
        listOf("Next stop" to fieldBounds, "Microphone" to micBounds, "Search result" to resultBounds).forEach { (name, bounds) ->
            assertTrue("$name must remain above the footer on a compact phone with keyboard", bounds.height > 0 && bounds.top >= 0 && bounds.bottom <= footer.top)
        }
        assertTrue("Microphone remains beside the compact input", micBounds.center.y in fieldBounds.top..fieldBounds.bottom)
        assertTrue("Microphone remains within the compact input", micBounds.right <= fieldBounds.right + 1f)
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        screenshot("planner-compact-keyboard-search-result")
        compose.onNodeWithTag("plannerInputSubmit").performScrollTo().assertIsDisplayed().assertIsEnabled().assertHasClickAction()
        compose.onNodeWithContentDescription("Reorder stop 1: Office").assertDoesNotExist()
        // The search temporarily folds the itinerary. Its visible Expand action
        // must reveal the existing stops with one tap while retaining the entry.
        compose.onNodeWithTag("plannerPreviewToggle").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Reorder stop 1: Office").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().assertTextContains("Western")
        compose.onNodeWithTag("plannerPrimary").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(home.dest, vm.start)
            assertEquals(listOf("Office", "Home", "Office", "Home", "Office", "Home"), vm.stops.map { it.name })
            assertIdle()
        }
        dismissPlannerKeyboard()
    }

    @ExperimentalLanguageModel
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
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick().performTextReplacement("")
        dismissPlannerKeyboard()
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick()
            .performTextReplacement("Make the first stay forty five minutes instead")
        dismissPlannerKeyboard()
        compose.onNodeWithTag("plannerReviewApply").performScrollTo().assertIsNotEnabled()
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
            assertEquals(before, vm.capturePlanner().copy(activeField = before.activeField))
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

    @Test fun loadingSameEndpointsDropsOldWordsAndLateSpeech() {
        launchPlanner()
        compose.runOnIdle { vm.pickSavedPlace(home, Field.START); vm.pickSavedPlace(office, Field.END) }
        compose.onNodeWithTag("plannerDestinationInput").performScrollTo().performClick()
            .performTextReplacement("Stay at Office for twenty minutes")
        dismissPlannerKeyboard()
        compose.runOnIdle {
            val request = PlannerSpeech.begin(Field.END)
            PlannerSpeech.partial(request, "old unfinished instruction")
            val saved = home.copy(id = "inline-saved-route", name = "Morning route", kind = "ROUTE",
                start = home.dest, startName = home.name, dest = office.dest, destName = office.name,
                travelMode = TravelMode.FLY)
            assertTrue(vm.loadScenario(saved))
            PlannerSpeech.finish(request, "late old speech")
        }
        compose.waitForIdle()
        assertEquals("Office", compose.onNodeWithTag("plannerDestinationInput").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle {
            assertFalse(PlannerSpeech.state.value.active); assertNull(PlannerSpeech.state.value.target)
            assertEquals("Office", readPlannerEntry("end", "Office")); assertIdle()
        }
    }

    @Test fun completedSpeechSurvivesClosingPlannerBeforeConsumption() {
        launchPlanner()
        compose.runOnIdle {
            val request = PlannerSpeech.begin(Field.END)
            PlannerSpeech.finish(request, "Home then the museum tomorrow")
            plannerVisible = false
        }
        compose.onNodeWithTag("plannerDestinationInput").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals("Home then the museum tomorrow", readPlannerEntry("end", ""))
            PlannerSpeech.cancel() // Recognition memory need not survive recreation.
            plannerVisible = true
        }
        compose.onNodeWithTag("plannerDestinationInput").assertTextContains("Home then the museum tomorrow")
        compose.runOnIdle { assertNull(vm.dest); assertTrue(vm.stops.isEmpty()); assertIdle() }
    }

    @ExperimentalLanguageModel
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
            dismissPlannerKeyboard()
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
