package com.mirage.spike.store

import android.content.Context

/** Saved plans persisted on the device in SharedPreferences (as one JSON array). */
class PrefsScenarioStore(context: Context) : ScenarioStore {
    private val prefs = context.applicationContext.getSharedPreferences("mirage_scenarios", Context.MODE_PRIVATE)
    override fun load(): List<SavedScenario> = SavedScenario.listFromJson(prefs.getString(KEY, null))
    override fun save(list: List<SavedScenario>) { check(prefs.edit().putString(KEY, SavedScenario.listToJson(list)).commit()) { "Storage write failed" } }
    private companion object { const val KEY = "scenarios_v1" }
}
