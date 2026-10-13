package com.mirage.spike

/**
 * Bounds generated JSON before it reaches the production validators.
 * NaturalLanguageInput and VoiceIntent remain authoritative: schema-constrained
 * output must still pass operation-specific, clarification and semantic checks.
 */
internal object LanguageModelSchema {
    fun forSchema(schema: String): String = when (schema) {
        "field" -> FIELD
        "plan" -> PLAN
        "legacy" -> LEGACY
        else -> throw IllegalArgumentException("Unknown interpretation schema.")
    }

    // These plain JSON constants are also read by the local model-evaluation harness.
    private const val FIELD = """{
      "type":"object",
      "properties":{
        "value":{"type":"string","maxLength":512},
        "question":{"type":"string","maxLength":400}
      },
      "required":["value","question"],
      "additionalProperties":false
    }"""

    private const val PLAN = """{
      "type":"object",
      "properties":{
        "edits":{
          "type":"array","minItems":0,"maxItems":12,
          "items":{
            "type":"object",
            "properties":{
              "op":{"type":"string","enum":["set_start","set_destination","add_stop","replace_stop","remove_stop","move_stop","set_stay","set_mode","set_departure","clear_departure"]},
              "target":{"type":"string","maxLength":32},
              "place":{"type":"string","maxLength":240},
              "minutes":{"type":"integer","minimum":-1,"maximum":1440},
              "value":{"type":"string","maxLength":160}
            },
            "required":["op","target","place","minutes","value"],
            "additionalProperties":false
          }
        },
        "question":{"type":"string","maxLength":400}
      },
      "required":["edits","question"],
      "additionalProperties":false
    }"""

    private const val LEGACY = """{
      "type":"object",
      "properties":{
        "action":{"type":"string","enum":["add_saved","add_place","save_new","save_changes","move","remove","stay","extend","status","pause","resume","clarify"]},
        "target":{"type":"string","maxLength":160},
        "placement":{"type":"string","enum":["NEXT","END","NOW"]},
        "minutes":{"type":"integer","minimum":0,"maximum":1440},
        "position":{"type":"integer","minimum":0,"maximum":100}
      },
      "required":["action","target","placement","minutes","position"],
      "additionalProperties":false
    }"""
}
