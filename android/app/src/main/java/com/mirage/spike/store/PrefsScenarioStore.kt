package com.mirage.spike.store

import android.content.Context

/** Saved plans persisted on the device in SharedPreferences (as one JSON array). */
class PrefsScenarioStore(context: Context) : ScenarioStore {
    private val prefs = context.applicationContext.getSharedPreferences("mirage_scenarios", Context.MODE_PRIVATE)
    override fun load(): List<SavedScenario> = SavedScenario.listFromJson(prefs.getString(KEY, null))
    override fun save(list: List<SavedScenario>) {
        val old=prefs.getString(KEY,null)
        val changed=SavedScenario.listFromJson(old).map{it.copy(lastUsedAt=0)} != list.map{it.copy(lastUsedAt=0)}
        val edit=prefs.edit().putString(KEY,SavedScenario.listToJson(list))
        if(changed) edit.putString("previous",old)
        check(edit.commit()) {"Storage write failed"}
    }
    override fun previous(): List<SavedScenario>? = prefs.getString("previous", null)?.let { SavedScenario.listFromJson(it) }
    private companion object { const val KEY = "scenarios_v1" }
}
