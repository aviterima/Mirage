package com.mirage.spike

import org.json.JSONObject

/** Restore text only against the same selected place; a newly loaded plan wins. */
internal fun readPlannerEntry(key: String, baseline: String): String = runCatching {
    val json = JSONObject(InputDraftStore.read("planner.entry.$key") ?: return baseline)
    if (json.getString("baseline") == baseline) json.getString("text") else baseline
}.getOrDefault(baseline)

internal fun savePlannerEntry(key: String, baseline: String, text: String): Boolean =
    InputDraftStore.write("planner.entry.$key", JSONObject().put("baseline", baseline).put("text", text).toString())
