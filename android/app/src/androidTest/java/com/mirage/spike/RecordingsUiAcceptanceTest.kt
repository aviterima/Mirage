package com.mirage.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.mirage.spike.recording.TrackSource
import com.mirage.spike.recording.TrackState
import com.mirage.spike.recording.TrackSummary
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production library with disposable state and callback spies, no GPS collection. */
class RecordingsUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private var state by mutableStateOf(RecordingUiState())
    private var simulated by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private val recordRequests = mutableListOf<TrackSource>()
    private val replayRequests = mutableListOf<TrackSummary>()
    private val renameRequests = mutableListOf<TrackSummary>()
    private val deleteRequests = mutableListOf<TrackSummary>()
    private var finishes = 0
    private var dismisses = 0
    private val saved = TrackSummary("saved-fixture", "Saturday errands", TrackSource.REAL,
        1_000L, 3_661_000L, 3_660_000L, 42, TrackState.INTERRUPTED, 2)

    private fun show(tracks: List<TrackSummary> = emptyList()) {
        compose.setContent {
            MirageTheme {
                RecordingsContent(tracks, state, simulated, busy, error,
                    onRecord = { recordRequests += it }, onFinish = { finishes++ },
                    onRename = { renameRequests += it }, onDelete = { deleteRequests += it },
                    onReplay = { replayRequests += it }, onDismiss = { dismisses++ })
            }
        }
    }

    private fun assertNoActions() {
        assertTrue(recordRequests.isEmpty()); assertTrue(replayRequests.isEmpty())
        assertTrue(renameRequests.isEmpty()); assertTrue(deleteRequests.isEmpty())
        assertEquals(0, finishes); assertEquals(0, dismisses)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.executeShellCommand("mkdir -p /sdcard/Download/mirage-acceptance")
        device.executeShellCommand("screencap -p /sdcard/Download/mirage-acceptance/$name.png")
    }

    @Test fun openingAndRecomposingDoesNotRecordAndEachSourceRequiresATap() {
        show()
        compose.onNodeWithTag("recordingsLibrary").assertIsDisplayed()
        compose.onNodeWithTag("recordRealJourney").assertIsEnabled()
        compose.onNodeWithTag("recordEmulatedDay").assertIsEnabled()
        compose.runOnIdle { assertNoActions(); state = state.copy(revision = 1) }
        compose.runOnIdle { assertNoActions() }
        compose.onNodeWithTag("recordRealJourney").performClick()
        compose.runOnIdle { assertEquals(listOf(TrackSource.REAL), recordRequests) }
        compose.onNodeWithTag("recordEmulatedDay").performClick()
        compose.runOnIdle {
            assertEquals(listOf(TrackSource.REAL, TrackSource.EMULATED), recordRequests)
            assertEquals(0, finishes); assertTrue(replayRequests.isEmpty())
        }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, finishes) }
    }

    @Test fun simulationBlocksRealRecordingButAllowsEmulatedRecording() {
        simulated = true
        show()
        compose.onNodeWithTag("recordRealJourney").assertIsNotEnabled()
        compose.onNodeWithText("Stop simulation to record real locations.").assertIsDisplayed()
        compose.onNodeWithTag("recordEmulatedDay").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf(TrackSource.EMULATED), recordRequests)
            simulated = false
        }
        compose.onNodeWithTag("recordRealJourney").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf(TrackSource.EMULATED), recordRequests) }
    }

    @Test fun activeRecordingHasExplicitStopAndCannotReplayOrStartAnotherSource() {
        val active = saved.copy(id = "active-fixture", source = TrackSource.EMULATED,
            state = TrackState.ACTIVE, endedAtMillis = null, sampleCount = 0)
        state = RecordingUiState(active = active)
        show(listOf(active, saved))
        compose.onNodeWithText("● Recording emulated day").assertIsDisplayed()
        compose.onNodeWithText("Waiting for locations…").assertIsDisplayed()
        compose.onNodeWithTag("recordRealJourney").assertDoesNotExist()
        compose.onNodeWithTag("recordEmulatedDay").assertDoesNotExist()
        compose.onNodeWithTag("recording-active-fixture").assertDoesNotExist()
        compose.onNodeWithTag("replayRecording-saved-fixture").assertIsNotEnabled()
        compose.runOnIdle { assertNoActions(); state = state.copy(active = active.copy(source = TrackSource.REAL, sampleCount = 12)) }
        compose.onNodeWithText("● Recording real journey").assertIsDisplayed()
        screenshot("recordings-active")
        compose.onNodeWithTag("stopRecording").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, finishes); assertTrue(recordRequests.isEmpty()); assertTrue(replayRequests.isEmpty()) }
        compose.onNodeWithText("Close").performClick()
        compose.runOnIdle { assertEquals(1, dismisses); assertEquals(1, finishes) }
    }

    @Test fun savedActionsSelectTheExactRecordingAndRespectBusyStartingAndEmptyStates() {
        val empty = saved.copy(id = "empty-fixture", name = "No fixes yet", sampleCount = 0, gapCount = 0, state = TrackState.SAVED)
        show(listOf(saved, empty))
        compose.onNodeWithText("Interrupted recording · captured locations recovered").assertExists()
        compose.onNodeWithText("2 gaps in recorded locations").assertExists()
        screenshot("recordings-populated")
        compose.runOnIdle { assertNoActions() }
        compose.onNodeWithTag("replayRecording-saved-fixture").performScrollTo().assertIsEnabled().performClick()
        val inSaved = hasAnyAncestor(hasTestTag("recording-saved-fixture"))
        compose.onNode(hasText("Rename") and inSaved).performScrollTo().performClick()
        compose.onNode(hasText("Delete") and inSaved).performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(saved), replayRequests)
            assertEquals(listOf(saved), renameRequests)
            assertEquals(listOf(saved), deleteRequests)
            assertTrue(recordRequests.isEmpty()); assertEquals(0, finishes)
        }
        compose.onNodeWithTag("replayRecording-empty-fixture").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { busy = true; error = "Fixture storage failure" }
        compose.onNodeWithText("Fixture storage failure").assertIsDisplayed()
        compose.onNodeWithTag("recordRealJourney").assertIsNotEnabled()
        compose.onNodeWithTag("recordEmulatedDay").assertIsNotEnabled()
        compose.onNodeWithTag("replayRecording-saved-fixture").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasText("Rename") and inSaved).assertIsNotEnabled()
        compose.onNode(hasText("Delete") and inSaved).assertIsNotEnabled()
        compose.runOnIdle { busy = false; state = state.copy(starting = true) }
        compose.onNodeWithTag("recordRealJourney").assertDoesNotExist()
        compose.onNodeWithTag("recordEmulatedDay").assertDoesNotExist()
        compose.onNodeWithTag("replayRecording-saved-fixture").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(listOf(saved), replayRequests); assertEquals(listOf(saved), renameRequests)
            assertEquals(listOf(saved), deleteRequests); assertTrue(recordRequests.isEmpty())
        }
    }
}
