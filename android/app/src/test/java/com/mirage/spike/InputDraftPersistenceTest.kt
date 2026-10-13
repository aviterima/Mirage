package com.mirage.spike

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

internal class MemoryPlannerDraftStorage : PlannerDraftStorage {
    private val values = mutableMapOf<String, Any>()
    var fail = false
    override fun all(): Map<String, *> = values.toMap()
    override fun commit(values: Map<String, Any>, removals: Set<String>): Boolean {
        if (fail) return false
        removals.forEach(this.values::remove); this.values.putAll(values)
        return true
    }
}

class InputDraftPersistenceTest {
    private lateinit var disk: MemoryPlannerDraftStorage
    @Before fun setup() { disk = MemoryPlannerDraftStorage(); InputDraftStore.useStorageForTest(disk) }
    @After fun cleanup() { InputDraftStore.useStorageForTest(null) }

    @Test fun typedOnlyAndReviewJsonSurviveRestartAndPlannerClearPreservesOtherOwners() {
        val generation = InputDraftStore.generation
        assertTrue(InputDraftStore.saveIfCurrent("planner.start", "Home then", generation))
        assertTrue(InputDraftStore.write("planner.review", "{\"pending\":\"Which Office?\"}"))
        assertTrue(InputDraftStore.write("saved.item-1.name", "New name"))
        InputDraftStore.useStorageForTest(disk)
        assertEquals("Home then", InputDraftStore.read("planner.start"))
        assertEquals("{\"pending\":\"Which Office?\"}", InputDraftStore.read("planner.review"))
        assertTrue(InputDraftStore.clearPlanner())
        val next = InputDraftStore.generation
        assertTrue(next > generation)
        InputDraftStore.useStorageForTest(disk)
        assertEquals(next, InputDraftStore.generation)
        assertNull(InputDraftStore.read("planner.start")); assertNull(InputDraftStore.read("planner.review"))
        assertEquals("New name", InputDraftStore.read("saved.item-1.name"))
        assertFalse(InputDraftStore.saveIfCurrent("planner.start", "late words", generation))
    }

    @Test fun resetTombstoneSurvivesRestartAndRejectsBothLateAutosaveKinds() {
        val inputGeneration = InputDraftStore.generation
        val draftGeneration = TripRecovery.draftGeneration
        assertTrue(InputDraftStore.write("planner.end", "unfinished"))
        assertTrue(InputDraftStore.saveRecovery(mapOf("draft" to "old", "plannerSnapshot" to "old-full"), draftGeneration))
        assertTrue(TripRecovery.clearDraft())
        val newDraftGeneration = TripRecovery.draftGeneration
        InputDraftStore.useStorageForTest(disk)
        assertEquals(newDraftGeneration, TripRecovery.draftGeneration)
        assertNull(InputDraftStore.readRecovery("draft")); assertNull(InputDraftStore.readRecovery("plannerSnapshot"))
        assertFalse(InputDraftStore.saveRecovery(mapOf("draft" to "late old draft"), draftGeneration))
        assertFalse(InputDraftStore.saveIfCurrent("planner.end", "late speech", inputGeneration))
    }

    @Test fun failedResetKeepsDurableInputsAndGeneration() {
        assertTrue(InputDraftStore.write("planner.end", "keep this"))
        val generation = InputDraftStore.generation
        disk.fail = true
        assertFalse(InputDraftStore.clearPlanner()); assertFalse(TripRecovery.clearDraft())
        assertEquals(generation, InputDraftStore.generation)
        InputDraftStore.useStorageForTest(disk)
        assertEquals(generation, InputDraftStore.generation)
        assertEquals("keep this", InputDraftStore.read("planner.end"))
    }

    @Test fun removingConsumedInputRejectsItsLateCallback() {
        val generation = InputDraftStore.generation
        assertTrue(InputDraftStore.write("planner.end", "confirmed"))
        assertTrue(InputDraftStore.remove("planner.end"))
        assertFalse(InputDraftStore.saveIfCurrent("planner.end", "late partial", generation))
        assertNull(InputDraftStore.read("planner.end"))
    }

    @Test fun credentialsAndOversizedInputAreNotPersisted() {
        InputDraftStore.useStorageForTest(disk, listOf("fixture-private-key-123"))
        assertFalse(InputDraftStore.write("planner.end", "use fixture-private-key-123"))
        assertFalse(InputDraftStore.write("planner.review", "{\"password\":\"secret-value-123\"}"))
        assertFalse(InputDraftStore.write("planner.end", "Bearer fixture-secret-token-123"))
        assertFalse(InputDraftStore.write("planner.end", "x".repeat(256 * 1024 + 1)))
        assertTrue(InputDraftStore.write("planner.end", "Visit Secret Garden then Home"))
        assertEquals("Visit Secret Garden then Home", InputDraftStore.read("planner.end"))
    }
}
