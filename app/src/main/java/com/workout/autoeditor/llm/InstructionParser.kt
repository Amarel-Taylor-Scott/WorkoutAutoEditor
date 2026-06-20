package com.workout.autoeditor.llm

import com.workout.autoeditor.data.EditPolicy
import com.workout.autoeditor.data.ExerciseClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Turns a free-text editing instruction into a structured EditPolicy.
 *
 * Strategy:
 *   1. Send a strict-format prompt to Gemma.
 *   2. Extract the first balanced JSON object from the response.
 *   3. Parse fields permissively (any missing field falls back to default).
 *   4. On total parse failure, retry once with a stricter prompt.
 *   5. If still failing, return EditPolicy.DEFAULT_TIGHT.
 */
class InstructionParser(private val gemma: GemmaService) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun parse(userInstruction: String): EditPolicy {
        val raw = gemma.generate(buildPrompt(userInstruction))
        val parsed = tryExtract(raw)
        if (parsed != null) return parsed

        val raw2 = gemma.generate(buildStrictPrompt(userInstruction))
        return tryExtract(raw2) ?: EditPolicy.DEFAULT_TIGHT
    }

    private fun buildPrompt(instruction: String): String = """
You are an editor for fitness videos. Convert the user's instruction into JSON.

Schema (every field optional, omit unknowns):
{
  "keepClasses": [SQUAT|PUSHUP|BICEP_CURL|OVERHEAD_PRESS|REST|WARMUP|IDLE],
  "dropWarmups": true|false,
  "dropRest": true|false,
  "dropIdle": true|false,
  "minRepsPerSegment": int,
  "perExerciseCapMs": long,
  "targetTotalMs": long
}

User instruction: "$instruction"

Reply with ONLY the JSON object, no commentary.
""".trimIndent()

    private fun buildStrictPrompt(instruction: String): String =
        "Output JSON only. Instruction: $instruction\nJSON:"

    private fun tryExtract(raw: String): EditPolicy? {
        val jsonStr = extractFirstJson(raw) ?: return null
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            buildPolicy(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractFirstJson(raw: String): String? {
        var depth = 0
        var start = -1
        for (i in raw.indices) {
            val c = raw[i]
            if (c == '{') {
                if (depth == 0) start = i
                depth += 1
            } else if (c == '}') {
                depth -= 1
                if (depth == 0 && start >= 0) return raw.substring(start, i + 1)
            }
        }
        return null
    }

    private fun buildPolicy(obj: JsonObject): EditPolicy {
        val def = EditPolicy.DEFAULT_TIGHT
        val keep = (obj["keepClasses"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { runCatching { ExerciseClass.valueOf(it.jsonPrimitive.content.uppercase()) }.getOrNull() }
            ?.toSet() ?: def.keepClasses

        return EditPolicy(
            keepClasses = keep,
            dropWarmups = obj["dropWarmups"]?.jsonPrimitive?.booleanOrNull ?: def.dropWarmups,
            dropRest = obj["dropRest"]?.jsonPrimitive?.booleanOrNull ?: def.dropRest,
            dropIdle = obj["dropIdle"]?.jsonPrimitive?.booleanOrNull ?: def.dropIdle,
            minRepsPerSegment = obj["minRepsPerSegment"]?.jsonPrimitive?.longOrNull?.toInt() ?: def.minRepsPerSegment,
            minSegmentMs = obj["minSegmentMs"]?.jsonPrimitive?.longOrNull ?: def.minSegmentMs,
            perExerciseCapMs = obj["perExerciseCapMs"]?.jsonPrimitive?.longOrNull ?: def.perExerciseCapMs,
            targetTotalMs = obj["targetTotalMs"]?.jsonPrimitive?.longOrNull ?: def.targetTotalMs,
            mergeGapMs = obj["mergeGapMs"]?.jsonPrimitive?.longOrNull ?: def.mergeGapMs,
            padHeadMs = obj["padHeadMs"]?.jsonPrimitive?.longOrNull ?: def.padHeadMs,
            padTailMs = obj["padTailMs"]?.jsonPrimitive?.longOrNull ?: def.padTailMs,
        )
    }
}
