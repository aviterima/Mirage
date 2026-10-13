package com.mirage.spike

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The shipped offline flow: recognition edits words; only existing concrete controls apply them. */
class OfflineDictationUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("test.offline.name", "test.offline.stay", "test.offline.departure")

    @Before fun prepare() {
        assertFalse("This suite covers the normal offline build", LocalLanguageModel.interpretationEnabled)
        context.stopService(Intent(context, VoiceService::class.java))
        FieldSpeech.cancel()
        InputDraftStore.configure(context)
        keys.forEach { InputDraftStore.remove(it) }
    }

    @After fun cleanup() {
        compose.runOnIdle { FieldSpeech.cancel(); keys.forEach { InputDraftStore.remove(it) } }
        context.stopService(Intent(context, VoiceService::class.java))
    }

    @Test fun bundledMicrophoneWorksWithoutGemmaAndSetupHasNoDownload() {
        LocalLanguageModel.configure(context)
        assertFalse(LocalLanguageModel.ready)
        var listened = 0
        var finished = 0
        var listening by mutableStateOf(false)
        var setup by mutableStateOf(false)
        compose.setContent {
            MirageTheme {
                Column {
                    PlannerMicrophone(onListen = { listened++; listening = true }, listening = listening,
                        onCancel = { finished++; listening = false }, modifier = Modifier.testTag("offlineMic"))
                    if (setup) VoiceSetupSheet(onDismiss = { setup = false }, onUseMicrophone = { listened++; setup = false })
                }
            }
        }
        compose.onNodeWithTag("offlineMic").performClick()
        compose.runOnIdle { assertEquals(1, listened); assertTrue(listening); assertFalse(LocalLanguageModel.ready) }
        compose.onNodeWithTag("voiceSetup").assertDoesNotExist()
        compose.onNodeWithTag("voiceDownload").assertDoesNotExist()
        compose.onNodeWithTag("offlineMic").performClick()
        compose.runOnIdle { assertEquals(1, finished); assertFalse(listening); setup = true }
        compose.onNodeWithTag("voiceSetupStatus").assertTextContains("Offline dictation is included")
        compose.onNodeWithTag("voiceDownload").assertDoesNotExist()
        compose.onNodeWithTag("voiceSetupUseMicrophone").performClick()
        compose.runOnIdle { assertEquals(2, listened); assertFalse(LocalLanguageModel.ready) }
    }

    @Test fun dictatedNameIsLiteralEditableAndNeverInvokesInterpreterOrSave() {
        val key = keys[0]
        var modelCalls = 0
        val saved = mutableListOf<String>()
        compose.setContent {
            MirageTheme {
                var text by remember { mutableStateOf("") }
                Column {
                    VoiceTextField(text, { text = it }, key, "Name", kind = "name",
                        modifier = Modifier.testTag("offlineName"), interpreter = { _, _, _, _, _ ->
                            modelCalls++; error("Offline field must not reach interpretation")
                        })
                    Button(onClick = { saved += text }, modifier = Modifier.testTag("offlineSave")) { Text("Save") }
                }
            }
        }
        compose.runOnIdle {
            val request = FieldSpeech.begin(key)
            FieldSpeech.finish(request, "Call it Friday, actually Saturday")
        }
        compose.onNodeWithTag("offlineName").assertTextContains("Call it Friday, actually Saturday")
        compose.onNodeWithTag("voiceReview-$key").assertDoesNotExist()
        compose.onNodeWithTag("voiceUndo-$key").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, modelCalls); assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("offlineName").performTextReplacement("Saturday")
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        compose.onNodeWithTag("offlineSave").performClick()
        compose.runOnIdle { assertEquals(listOf("Saturday"), saved); assertEquals(0, modelCalls) }
    }

    @Test fun stayRejectsNonConcreteOrOutOfRangeWordsUntilExplicitSet() {
        val applied = mutableListOf<Int>()
        val key = keys[1]
        compose.setContent { MirageTheme { DwellDialog("Fixture", 30, { applied += it }, {}, inputKey = key) } }
        val input = compose.onNode(hasSetTextAction())
        listOf("an hour and a half", "-1", "1441", "1.5", "").forEach { invalid ->
            input.performTextReplacement(invalid)
            compose.onNodeWithText("Set").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(applied.isEmpty()) }
        }
        input.performTextReplacement("90")
        compose.onNodeWithTag("voiceReview-$key").assertDoesNotExist()
        compose.onNodeWithText("Set").assertIsEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithText("Set").performClick()
        compose.runOnIdle { assertEquals(listOf(90), applied); assertNull(InputDraftStore.read(key)) }
    }

    @Test fun departureRejectsAmbiguousAndImpossibleTextUntilExplicitSet() {
        val applied = mutableListOf<Long>()
        val key = keys[2]
        compose.setContent { MirageTheme { VoiceTimeDialog("Departure", key, null, { applied += it }, {}) } }
        val input = compose.onNodeWithTag("voiceTimeInput")
        listOf("tomorrow at nine", "2026-02-30T09:00", "2026-10-20T25:00").forEach { invalid ->
            input.performTextReplacement(invalid)
            compose.onNodeWithTag("voiceTimeSet").assertIsNotEnabled()
            compose.runOnIdle { assertTrue(applied.isEmpty()) }
        }
        val concrete = "2026-10-20T09:30"
        input.performTextReplacement(concrete)
        assertEquals(concrete, input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("voiceReview-$key").assertDoesNotExist()
        compose.onNodeWithTag("voiceTimeSet").assertIsEnabled()
        compose.runOnIdle { assertTrue(applied.isEmpty()) }
        compose.onNodeWithTag("voiceTimeSet").performClick()
        compose.runOnIdle { assertEquals(listOf(parsePlannerTime(concrete)), applied); assertNull(InputDraftStore.read(key)) }
    }

    @Test fun disabledInterpretationRejectsAllEntryPointsBeforeNativeWork() = runBlocking {
        LocalLanguageModel.configure(context)
        LocalLanguageModel.download()
        assertFalse(LocalLanguageModel.ready)
        val requests: List<suspend () -> String> = listOf(
            { LocalLanguageModel.interpret("Synthetic system", "Synthetic request") },
            { LocalLanguageModel.interpretStructured("Synthetic system", "Synthetic request", "field") },
            { LocalLanguageModel.interpretStructured("Synthetic system", "Synthetic request", "plan") },
        )
        requests.forEach { request ->
            val result = runCatching { request() }
            assertTrue("Disabled interpretation must reject before model readiness or JNI", result.exceptionOrNull() is IllegalStateException)
            assertEquals("Use the editable fields and itinerary controls in this version.", result.exceptionOrNull()?.message)
        }
        assertFalse(LocalLanguageModel.ready)
    }
}
