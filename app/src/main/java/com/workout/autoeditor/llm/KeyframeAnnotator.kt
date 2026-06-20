package com.workout.autoeditor.llm

import com.workout.autoeditor.data.Segment

/**
 * Asks Gemma to label a small number of ambiguous segments.
 *
 * v1 is text-only: we serialize each segment as features (class, reps,
 * confidence, audio activity) and ask Gemma to classify warmup vs working_set.
 * Vision modality requires MediaPipe tasks-genai 0.10.16+; we pin to 0.10.14
 * for max compatibility, so we trade vision for stability.
 *
 * Picks the K segments with lowest classifier confidence (capped at MAX_CALLS).
 */
class KeyframeAnnotator(private val gemma: GemmaService) {

    companion object {
        private const val MAX_CALLS = 15
        private const val LOW_CONF_THRESHOLD = 0.6f
    }

    suspend fun annotate(segments: List<Segment>): List<Segment> {
        val candidates = segments
            .withIndex()
            .filter { (_, s) -> s.avgConfidence < LOW_CONF_THRESHOLD || s.repCount in 1..2 }
            .sortedBy { it.value.avgConfidence }
            .take(MAX_CALLS)

        if (candidates.isEmpty()) return segments

        val mutable = segments.toMutableList()
        for ((idx, seg) in candidates) {
            val prompt = buildPrompt(seg)
            val resp = gemma.generate(prompt).lowercase()
            val label = when {
                "warmup" in resp -> "warmup"
                "working" in resp -> "working_set"
                "rest" in resp -> "rest"
                else -> "uncertain"
            }
            mutable[idx] = seg.copy(annotations = seg.annotations + ("intent" to label))
        }
        return mutable
    }

    private fun buildPrompt(seg: Segment): String = """
A segment from a workout video.
Class: ${seg.exercise}
Reps: ${seg.repCount}
Duration ms: ${seg.durationMs}
Avg classifier confidence: ${"%.2f".format(seg.avgConfidence)}
Avg audio RMS: ${"%.3f".format(seg.avgAudioRms)}
Person fully visible: ${seg.personVisible}

Choose ONE word: warmup, working_set, rest.
""".trimIndent()
}
