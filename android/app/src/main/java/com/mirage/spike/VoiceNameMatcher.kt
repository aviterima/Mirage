package com.mirage.spike

import java.util.Locale

/** Parentheses are display formatting, while every word (including the city) must still match. */
internal object VoiceNameMatcher {
    private val whitespace = Regex("\\s+")

    private fun comparison(value: String): String = value.replace('(', ' ').replace(')', ' ')
        .trim().replace(whitespace, " ").lowercase(Locale.ROOT)

    fun matches(name: String, spokenName: String): Boolean {
        val expected = comparison(name)
        return expected.isNotEmpty() && expected == comparison(spokenName)
    }

    fun mentioned(name: String, instruction: String): Boolean {
        val expected = comparison(name)
        if (expected.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(expected) + "(?![\\p{L}\\p{N}])")
            .containsMatchIn(comparison(instruction))
    }

    /** Bind only a unique full-name match; downstream confirmation continues to use stored names. */
    fun bind(intent: VoiceIntent, savedNames: List<String>, upcomingNames: List<String>): VoiceIntent {
        if (intent.target.isBlank()) return intent
        val saved = intent.action in setOf("add_place", "add_saved")
        val candidates = when {
            saved -> savedNames
            intent.action in setOf("move", "remove", "stay") -> upcomingNames
            else -> return intent // In particular, never rewrite a new custom save name.
        }
        val matchingNames = candidates.filter { matches(it, intent.target) }
        check(matchingNames.size <= 1) { "Name one unique saved item or upcoming stop, including its city." }
        val canonical = matchingNames.singleOrNull() ?: return intent
        return intent.copy(action = if (saved) "add_saved" else intent.action, target = canonical)
    }
}
