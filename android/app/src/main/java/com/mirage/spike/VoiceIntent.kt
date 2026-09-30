package com.mirage.spike

import org.json.JSONObject

data class VoiceIntent(val action: String, val target: String = "", val placement: Placement = Placement.NEXT,
    val minutes: Int = 0, val position: Int = 0, val travelMode: com.mirage.spike.engine.TravelMode? = null) {
    fun groundedIn(text: String): Boolean {
        if (action !in setOf("add_saved","add_place","save_new","move","remove") && !(action=="stay" && target.isNotBlank())) return true
        val words = Regex("[\\p{L}\\p{N}]+").findAll(text.lowercase()).map { it.value }.toSet()
        val targetWords = Regex("[\\p{L}\\p{N}]+").findAll(target.lowercase()).map { it.value }.toList()
        return targetWords.isNotEmpty() && targetWords.all { it in words }
    }
    companion object {
        private val actions = setOf("add_saved", "add_place", "save_new", "save_changes", "move", "remove", "stay", "extend", "status", "pause", "resume", "clarify")
        fun parse(raw: String): VoiceIntent {
            val text = raw.trim()
            require(text.startsWith("{") && text.endsWith("}")) { "Please try a shorter instruction." }
            val obj = JSONObject(text)
            require(obj.keys().asSequence().toSet() == setOf("action","target","placement","minutes","position")) { "Unrecognized command format." }
            val action = obj.getString("action"); require(action in actions) { "Unsupported action." }
            require(obj.get("target") is String && obj.get("minutes") is Number && obj.get("position") is Number)
            val target = obj.getString("target").trim()
            val minutes = obj.getInt("minutes"); val position = obj.getInt("position")
            require(target.length <= 160 && minutes in 0..1440 && position in 0..100) { "Command value is out of range." }
            require(obj.getDouble("minutes") == minutes.toDouble() && obj.getDouble("position") == position.toDouble())
            if (action in setOf("add_saved","add_place","save_new","move","remove")) require(target.isNotBlank()) { "Name the destination or itinerary." }
            if (action in setOf("stay","extend")) require(minutes > 0) { "Say how many minutes." }
            if (action == "move") require(position > 0) { "Say the new stop position." }
            return VoiceIntent(action,if (action in setOf("add_saved","add_place","save_new","move","remove","stay")) target else "",Placement.valueOf(obj.getString("placement")),minutes,position)
        }
    }
}
