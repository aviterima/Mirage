package com.mirage.spike

import com.mirage.spike.engine.TravelMode

sealed interface SpokenCommand {
    data object Pause : SpokenCommand
    data object Resume : SpokenCommand
    data object Stop : SpokenCommand
    data object ClarifyStop : SpokenCommand
    data object Skip : SpokenCommand
    data object Status : SpokenCommand
    data object Cancel : SpokenCommand
    data object Help : SpokenCommand
    data class Stay(val minutes: Int) : SpokenCommand
    data class Extend(val minutes: Int) : SpokenCommand
    data class Scale(val factor: Double) : SpokenCommand
    data class Remove(val ordinal: Int) : SpokenCommand
    data class Choice(val ordinal: Int) : SpokenCommand
    data class Journey(val legs: List<RequestedStop>, val next: Boolean = false, val snap: Boolean = false) : SpokenCommand
    data class Unknown(val text: String) : SpokenCommand
}
data class RequestedStop(val query: String, val mode: TravelMode = TravelMode.DRIVE, val minutes: Int = 0)

/** Deliberately bounded, offline language layer. Unrecognized words never become actions. */
object CommandParser {
    fun normalize(text: String) = text.lowercase().trim().replace(Regex("[.,!?]+$"), "").replace(Regex("\\s+"), " ")
    fun minutes(text: String): Int? {
        val t = normalize(text).replace("half an hour", "30 minutes").replace("half hour", "30 minutes").replace("an hour", "1 hour").replace("a minute", "1 minute")
        if (Regex("[-+−]\\d|\\d*\\.\\d+|minus |negative |hundred|thousand").containsMatchIn(t)) return null
        val words = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
            "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14,
            "fifteen" to 15, "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
            "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40,
            "fifty" to 50, "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90)
        val number = words.keys.joinToString("|")
        val match = Regex("\\b(\\d+|(?:$number)(?:[ -](?:$number))*) (minutes?|hours?)\\b").find(t) ?: return null
        val token = match.groupValues[1]
        val parts = token.split(' ', '-')
        if (parts.size > 2) return null
        val n = token.toLongOrNull() ?: if (parts.size == 1) words[token]?.toLong() else {
            val tens = words[parts[0]] ?: return null
            if (tens < 20 || tens % 10 != 0 || (words[parts[1]] ?: 0) !in 1..9) return null
            (tens + (words[parts[1]] ?: return null)).toLong()
        } ?: return null
        if (n !in 1..1440) return null
        return (n * if (match.groupValues[2].startsWith("hour")) 60 else 1).takeIf { it in 1..1440 }?.toInt()
    }
    fun parse(input: String): SpokenCommand {
        val t = normalize(input).removePrefix("hello mirage ").removePrefix("please ")
        return when (t) {
            "pause", "pause here", "hold here" -> SpokenCommand.Pause
            "resume", "continue", "continue driving" -> SpokenCommand.Resume
            "stop" -> SpokenCommand.ClarifyStop
            "stop simulation", "end simulation", "return to real location", "return to my real location", "stop simulation and return to my real location" -> SpokenCommand.Stop
            "leave now", "skip ahead", "jump to arrival", "end this stay" -> SpokenCommand.Skip
            "status", "what happens next", "what is next", "where am i", "what are you doing" -> SpokenCommand.Status
            "cancel", "never mind", "nevermind" -> SpokenCommand.Cancel
            "help", "what can i say" -> SpokenCommand.Help
            else -> parseDetails(t)
        }
    }
    private fun parseDetails(input: String): SpokenCommand {
        val arrival = input.startsWith("when i arrive")
        val t = input.replace(Regex("^when i arrive[, ]+"), "")
        if (Regex("^(extend|stay|add another)").containsMatchIn(t)) {
            minutes(t)?.let { return if (t.startsWith("extend") || t.contains("another") || t.contains("longer")) SpokenCommand.Extend(it) else SpokenCommand.Stay(it) }
        }
        Regex("^(?:fast forward|fast-forward|speed up)(?: to)? (\\d+|one|two|five|ten)(?: times|x)?$").matchEntire(t)?.let {
            val n = it.groupValues[1].toDoubleOrNull() ?: mapOf("one" to 1.0, "two" to 2.0, "five" to 5.0, "ten" to 10.0)[it.groupValues[1]]!!
            if (n in 1.0..100.0) return SpokenCommand.Scale(n)
        }
        Regex("^remove (?:the )?(\\d+|first|second|third|fourth|fifth)(?: upcoming)? stop$").matchEntire(t)?.let {
            val n = ordinal(it.groupValues[1]); if (n != null) return SpokenCommand.Remove(n)
        }
        Regex("^(?:number |option |the )?(\\d+|one|two|three|first|second|third)(?: one)?$").matchEntire(t)?.let {
            ordinal(it.groupValues[1])?.let { n -> return SpokenCommand.Choice(n) }
        }
        val next = arrival || t.startsWith("after this") || t.startsWith("after arrival") || t.startsWith("next ") || t.startsWith("when i arrive, ")
        var text = t.replace(Regex("^(?:after this|after arrival)[, ]+"), "").removePrefix("next ")
        val snap = text.startsWith("snap to ")
        val legs = mutableListOf<RequestedStop>()
        for (piece in text.split(Regex("\\s+(?:and )?then\\s+|;\\s*"))) {
            if (piece.startsWith("stay ")) {
                val time = minutes(piece) ?: return SpokenCommand.Unknown(t)
                if (legs.isEmpty()) return SpokenCommand.Unknown(t)
                legs[legs.lastIndex] = legs.last().copy(minutes = time)
                continue
            }
            val m = Regex("^(drive to|walk to|bike to|cycle to|fly to|take transit to|go to|take me to|snap to) (.+)$").matchEntire(piece) ?: return SpokenCommand.Unknown(t)
            val mode = when (m.groupValues[1]) { "walk to" -> TravelMode.WALK; "bike to", "cycle to" -> TravelMode.BIKE; "fly to" -> TravelMode.FLY; "take transit to" -> TravelMode.TRANSIT; else -> TravelMode.DRIVE }
            legs += RequestedStop(m.groupValues[2], mode)
        }
        return if (legs.isNotEmpty() && (!snap || (legs.size == 1 && !next))) SpokenCommand.Journey(legs, next, snap) else SpokenCommand.Unknown(t)
    }
    private fun ordinal(t: String) = t.toIntOrNull()?.takeIf { it > 0 } ?: mapOf("first" to 1, "one" to 1, "second" to 2, "two" to 2, "third" to 3, "three" to 3, "fourth" to 4, "fifth" to 5)[t]
}

