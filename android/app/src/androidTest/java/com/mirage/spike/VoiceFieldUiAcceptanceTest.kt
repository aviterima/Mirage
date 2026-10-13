package com.mirage.spike

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject

/** Production field/controller with deterministic interpretation; no acoustic or model-quality claim. */
class VoiceFieldUiAcceptanceTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val keys = listOf("test.voice.minutes", "test.voice.name", "test.voice.recovery", "test.voice.secret", "test.voice.datetime")

    @Before fun prepare() {
        context.stopService(Intent(context, VoiceService::class.java))
        FieldSpeech.cancel()
        InputDraftStore.configure(context)
        keys.forEach { InputDraftStore.remove(it) }
    }
    @After fun cleanUp() {
        compose.runOnIdle { FieldSpeech.cancel(); keys.forEach { InputDraftStore.remove(it) } }
        context.stopService(Intent(context, VoiceService::class.java))
    }
    private fun content(body: @Composable ColumnScope.() -> Unit) {
        compose.setContent { MirageTheme { Surface { Column(Modifier.fillMaxSize().imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp), content = body) } } }
    }

    @Test fun typedDurationRemainsEditableUntilReviewAndUndoRestoresWords() {
        val key = keys[0]
        var applied = 0
        content {
            var text by remember { mutableStateOf("") }
            VoiceTextField(text, { text = it }, key, "Minutes", kind = "minutes",
                modifier = Modifier.fillMaxWidth().testTag("durationInput"),
                interpreter = { words, _, kind, _, _ ->
                    assertEquals("an hour and a half", words); assertEquals("minutes", kind)
                    FieldInterpretation("90")
                })
            Button(onClick = { applied++ }, enabled = !voiceFieldBusy(key), modifier = Modifier.testTag("applyField")) { Text("Set") }
        }
        val input = compose.onNodeWithTag("durationInput")
        input.performClick().performTextInput("an hour and a half")
        input.assertTextContains("an hour and a half")
        compose.onNodeWithTag("voiceMic-$key").assertExists().assertHasClickAction()
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        input.assertTextContains("90")
        compose.runOnIdle { assertEquals(0, applied) }
        compose.onNodeWithTag("voiceUndo-$key").performScrollTo().performClick()
        input.assertTextContains("an hour and a half")
        compose.runOnIdle { assertEquals(0, applied) }
    }

    @Test fun correctionUsesLatestReviewedNameAndStillNeedsExplicitSave() {
        val key = keys[1]
        val currentValues = mutableListOf<String>()
        var saves = 0
        content {
            var text by remember { mutableStateOf("Original") }
            VoiceTextField(text, { text = it }, key, "Name", kind = "name", modifier = Modifier.testTag("nameInput"),
                interpreter = { words, _, _, current, _ ->
                    currentValues += current
                    FieldInterpretation(if (words.contains("Thursday")) "Thursday" else "Wednesday")
                })
            Button(onClick = { saves++ }, modifier = Modifier.testTag("saveField")) { Text("Save") }
        }
        val input = compose.onNodeWithTag("nameInput")
        input.performTextReplacement("call it Tuesday, actually Wednesday")
        input.performClick()
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        input.assertTextContains("Wednesday")
        input.performTextReplacement("change that to Thursday")
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        input.assertTextContains("Thursday")
        compose.runOnIdle { assertEquals(listOf("Original", "Wednesday"), currentValues); assertEquals(0, saves) }
        compose.onNodeWithTag("saveField").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun partialWordsSurviveEditorDisposalAndRestoreWithoutStartingRecording() {
        val key = keys[2]
        var visible by mutableStateOf(true)
        content {
            if (visible) {
                var text by remember { mutableStateOf("") }
                VoiceTextField(text, { text = it }, key, "Name", kind = "name", modifier = Modifier.testTag("recoverInput"))
            } else Text("Editor closed")
        }
        compose.onNodeWithTag("recoverInput").assertExists()
        compose.runOnIdle {
            val request = FieldSpeech.begin(key)
            FieldSpeech.partial(request, "call it a long weekend actually")
            visible = false
        }
        compose.onNodeWithText("Editor closed").assertExists()
        compose.runOnIdle {
            assertTrue(InputDraftStore.read(key).orEmpty().contains("call it a long weekend actually"))
            FieldSpeech.cancel() // Memory recognition state is unavailable after process recreation.
            visible = true
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("call it a long weekend actually", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("recoverInput").assertTextContains("call it a long weekend actually")
        compose.runOnIdle { assertFalse(FieldSpeech.state.value.active) }
    }

    @Test fun exactSecretReviewBypassesModelAndDraftStoreAndDiscardRejectsLateWords() {
        val key = keys[3]
        var modelCalls = 0
        val literal = "AIza-fake-Case_Sensitive-123456789"
        content {
            var text by remember { mutableStateOf("") }
            VoiceTextField(text, { text = it }, key, "API key", kind = "verbatim", persistDraft = false,
                modifier = Modifier.testTag("secretInput"), interpreter = { _, _, _, _, _ ->
                    modelCalls++; error("Secret must not reach interpretation")
                })
            Button(onClick = { discardVoiceField(context, key); text = "" }, modifier = Modifier.testTag("discardSecret")) { Text("Clear") }
        }
        compose.onNodeWithTag("secretInput").performClick().performTextInput(literal)
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        compose.onNodeWithTag("secretInput").assertTextContains(literal)
        var request = 0L
        compose.runOnIdle {
            assertEquals(0, modelCalls); assertNull(InputDraftStore.read(key))
            request = FieldSpeech.begin(key, sensitive = true)
            FieldSpeech.partial(request, "unfinished credential")
        }
        compose.onNodeWithTag("discardSecret").performScrollTo().performClick()
        compose.runOnIdle { FieldSpeech.finish(request, "late credential") }
        assertEquals("", compose.onNodeWithTag("secretInput").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(0, modelCalls); assertNull(InputDraftStore.read(key)); assertNull(FieldSpeech.results.value[key]) }
    }

    @Test fun clarificationSurvivesReopenAndCalendarSelectionBecomesCorrectionContext() {
        val key = keys[4]
        val initial = "2026-10-12T14:00"
        var visible by mutableStateOf(true)
        val calls = mutableListOf<Triple<String, String, String>>()
        content {
            if (visible) {
                var text by remember { mutableStateOf(initial) }
                VoiceTextField(text, { text = it }, key, "Departure", kind = "datetime",
                    modifier = Modifier.testTag("datetimeInput"), context = "Local time zone: America/Los_Angeles",
                    interpreter = { words, _, _, current, history ->
                        calls += Triple(words, current, history)
                        when (words) {
                            "tomorrow at nine" -> FieldInterpretation("", "Nine in the morning or evening?")
                            "morning" -> FieldInterpretation("2026-10-13T09:00")
                            "two hours later" -> FieldInterpretation("2026-11-01T12:00")
                            else -> error("Unexpected field input")
                        }
                    })
                Button(onClick = { text = "2026-11-01T10:00" }, modifier = Modifier.testTag("calendarChoice")) { Text("Choose calendar time") }
            } else Text("Time editor closed")
        }
        compose.onNodeWithTag("datetimeInput").performClick().performTextReplacement("tomorrow at nine")
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        compose.onNodeWithTag("voiceStatus-$key").assertTextContains("Nine in the morning or evening?")
        compose.runOnIdle { visible = false }
        compose.onNodeWithText("Time editor closed").assertExists()
        compose.runOnIdle {
            val saved = JSONObject(InputDraftStore.read(key)!!)
            assertTrue(saved.getString("clarification").contains("tomorrow at nine"))
            visible = true
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Nine in the morning or evening?", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("datetimeInput").performTextReplacement("morning")
        compose.onNodeWithTag("datetimeInput").performClick()
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        compose.onNodeWithTag("datetimeInput").assertTextContains("2026-10-13T09:00")
        compose.runOnIdle {
            assertEquals(initial, calls[1].second)
            val quoted = JSONObject(calls[1].third.substringAfter("quoted as data: "))
                .getJSONArray("clarificationHistory").getJSONObject(0)
            assertEquals("tomorrow at nine", quoted.getString("input"))
            assertEquals("Nine in the morning or evening?", quoted.getString("question"))
        }
        compose.onNodeWithTag("calendarChoice").performScrollTo().performClick()
        compose.onNodeWithTag("datetimeInput").assertTextContains("2026-11-01T10:00")
        compose.onNodeWithTag("datetimeInput").performTextReplacement("two hours later")
        compose.onNodeWithTag("datetimeInput").performClick()
        compose.onNodeWithTag("voiceReview-$key").performScrollTo().performClick()
        compose.onNodeWithTag("datetimeInput").assertTextContains("2026-11-01T12:00")
        compose.runOnIdle {
            assertEquals("2026-11-01T10:00", calls[2].second)
            assertFalse(calls[2].third.contains("clarificationHistory"))
        }
    }
}
