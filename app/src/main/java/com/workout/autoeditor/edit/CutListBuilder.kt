package com.workout.autoeditor.edit

import com.workout.autoeditor.data.CutList
import com.workout.autoeditor.data.CutListItem
import com.workout.autoeditor.data.EditPolicy
import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.data.Segment

/**
 * Six-stage pipeline turning Segments + EditPolicy into a CutList.
 *
 *   1. Filter by class (per policy.keepClasses + drop flags).
 *   2. Drop segments below quality bars (low reps, low confidence,
 *      person not visible, marked warmup/rest by Gemma annotations).
 *   3. Group adjacent same-class segments (shouldn't happen after
 *      TimelineBuilder but safety-net).
 *   4. Cap per-exercise total duration (rank by confidence and reps).
 *   5. Merge segments that are close in time.
 *   6. Trim total length to policy.targetTotalMs (drop weakest segments).
 */
class CutListBuilder(private val policy: EditPolicy) {

    fun build(sourceUri: String, segments: List<Segment>): CutList {
        val s1 = filterByClass(segments)
        val s2 = filterByQuality(s1)
        val s3 = capPerExercise(s2)
        val s4 = mergeNearby(s3)
        val s5 = trimToTarget(s4)

        val items = s5.map { seg ->
            CutListItem(
                sourceUri = sourceUri,
                startMs = (seg.startMs - policy.padHeadMs).coerceAtLeast(0L),
                endMs = seg.endMs + policy.padTailMs,
                exercise = seg.exercise,
                rationale = "class=${seg.exercise} reps=${seg.repCount} conf=${"%.2f".format(seg.avgConfidence)}",
            )
        }
        val total = items.sumOf { it.endMs - it.startMs }
        return CutList(sourceUri = sourceUri, items = items, totalMs = total)
    }

    private fun filterByClass(segments: List<Segment>): List<Segment> = segments.filter { s ->
        when (s.exercise) {
            ExerciseClass.WARMUP -> !policy.dropWarmups
            ExerciseClass.REST -> !policy.dropRest
            ExerciseClass.IDLE -> !policy.dropIdle
            ExerciseClass.UNKNOWN -> false
            else -> s.exercise in policy.keepClasses
        }
    }

    private fun filterByQuality(segments: List<Segment>): List<Segment> = segments.filter { s ->
        if (!s.personVisible) return@filter false
        if (s.durationMs < policy.minSegmentMs) return@filter false
        val needsReps = s.exercise in setOf(
            ExerciseClass.SQUAT, ExerciseClass.PUSHUP,
            ExerciseClass.BICEP_CURL, ExerciseClass.OVERHEAD_PRESS,
        )
        if (needsReps && s.repCount < policy.minRepsPerSegment) return@filter false
        val intent = s.annotations["intent"]
        if (policy.dropWarmups && intent == "warmup") return@filter false
        if (policy.dropRest && intent == "rest") return@filter false
        true
    }

    private fun capPerExercise(segments: List<Segment>): List<Segment> {
        val byClass = segments.groupBy { it.exercise }
        val out = ArrayList<Segment>()
        for ((_, group) in byClass) {
            var remaining = policy.perExerciseCapMs
            val ranked = group.sortedWith(
                compareByDescending<Segment> { it.repCount }
                    .thenByDescending { it.avgConfidence }
                    .thenBy { it.startMs },
            )
            for (s in ranked) {
                if (remaining <= 0L) break
                if (s.durationMs <= remaining) {
                    out += s
                    remaining -= s.durationMs
                } else {
                    out += s.copy(endMs = s.startMs + remaining)
                    remaining = 0L
                }
            }
        }
        return out.sortedBy { it.startMs }
    }

    private fun mergeNearby(segments: List<Segment>): List<Segment> {
        if (segments.size < 2) return segments
        val sorted = segments.sortedBy { it.startMs }
        val out = ArrayList<Segment>()
        var current = sorted[0]
        for (k in 1 until sorted.size) {
            val next = sorted[k]
            val gap = next.startMs - current.endMs
            if (next.exercise == current.exercise && gap in 0..policy.mergeGapMs) {
                current = current.copy(
                    endMs = next.endMs,
                    repCount = current.repCount + next.repCount,
                    avgConfidence = (current.avgConfidence + next.avgConfidence) / 2f,
                    avgAudioRms = (current.avgAudioRms + next.avgAudioRms) / 2f,
                )
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun trimToTarget(segments: List<Segment>): List<Segment> {
        val target = policy.targetTotalMs ?: return segments
        var total = segments.sumOf { it.durationMs }
        if (total <= target) return segments
        val ranked = segments.sortedWith(
            compareByDescending<Segment> { it.avgConfidence }
                .thenByDescending { it.repCount },
        )
        val keep = LinkedHashSet<Segment>()
        var acc = 0L
        for (s in ranked) {
            if (acc + s.durationMs <= target) {
                keep += s
                acc += s.durationMs
            }
        }
        return keep.sortedBy { it.startMs }
    }
}
