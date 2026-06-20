package com.workout.autoeditor.pose

import com.workout.autoeditor.data.AudioEnvelope
import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.data.Segment

/**
 * Builds a contiguous timeline of Segments by:
 *   1. Per-frame classification via ExerciseClassifier.
 *   2. Sliding window majority vote to suppress flicker.
 *   3. Adjacent same-class merging.
 *   4. Per-segment rep detection via RepDetector when the class supports it.
 *
 * If frames are sampled at < 5 fps, set windowFrames lower so segment
 * boundaries don't lag.
 */
class TimelineBuilder(
    private val classifier: ExerciseClassifier = ExerciseClassifier(),
    private val windowFrames: Int = 5,
    private val minSegmentMs: Long = 1500L,
) {

    fun build(frames: List<PoseFrame>, audio: AudioEnvelope?): List<Segment> {
        if (frames.isEmpty()) return emptyList()

        val perFrame = frames.map { f ->
            val d = classifier.classify(f)
            Triple(f.timestampMs, d.exercise, d.confidence)
        }

        val smoothed = ArrayList<Triple<Long, ExerciseClass, Float>>(perFrame.size)
        for (i in perFrame.indices) {
            val from = (i - windowFrames / 2).coerceAtLeast(0)
            val to = (i + windowFrames / 2 + 1).coerceAtMost(perFrame.size)
            val window = perFrame.subList(from, to)
            val (cls, conf) = majority(window)
            smoothed += Triple(perFrame[i].first, cls, conf)
        }

        val segments = ArrayList<Segment>()
        var startIdx = 0
        var i = 1
        while (i <= smoothed.size) {
            val atEnd = i == smoothed.size
            val classChanged = !atEnd && smoothed[i].second != smoothed[startIdx].second
            if (atEnd || classChanged) {
                val first = smoothed[startIdx]
                val last = smoothed[i - 1]
                val cls = first.second
                val avgConf = smoothed.subList(startIdx, i).map { it.third }.average().toFloat()
                val sliceFrames = frames.subList(startIdx, i)
                val reps = countReps(cls, sliceFrames)
                val avgRms = avgAudio(audio, first.first, last.first)
                val visible = sliceFrames.count { it.isPersonVisible } > sliceFrames.size / 2

                val segment = Segment(
                    startMs = first.first,
                    endMs = last.first,
                    exercise = cls,
                    repCount = reps,
                    avgConfidence = avgConf,
                    avgAudioRms = avgRms,
                    personVisible = visible,
                )
                if (segment.durationMs >= minSegmentMs || cls == ExerciseClass.IDLE) {
                    segments += segment
                }
                startIdx = i
            }
            i++
        }

        return mergeAdjacentSameClass(segments)
    }

    private fun majority(window: List<Triple<Long, ExerciseClass, Float>>): Pair<ExerciseClass, Float> {
        val counts = window.groupingBy { it.second }.eachCount()
        val winner = counts.maxByOrNull { it.value }!!.key
        val avgConf = window.filter { it.second == winner }.map { it.third }.average().toFloat()
        return winner to avgConf
    }

    private fun countReps(cls: ExerciseClass, slice: List<PoseFrame>): Int {
        val cfg = RepDetector.forExercise(cls) ?: return 0
        val det = RepDetector(cfg)
        for (f in slice) det.update(f)
        return det.reps()
    }

    private fun avgAudio(audio: AudioEnvelope?, fromMs: Long, toMs: Long): Float {
        if (audio == null || audio.rms.isEmpty()) return 0f
        val fromIdx = (fromMs / audio.windowMs).toInt().coerceIn(0, audio.rms.size - 1)
        val toIdx = (toMs / audio.windowMs).toInt().coerceIn(fromIdx, audio.rms.size - 1)
        if (toIdx == fromIdx) return audio.rms[fromIdx]
        return audio.rms.subList(fromIdx, toIdx + 1).average().toFloat()
    }

    private fun mergeAdjacentSameClass(segments: List<Segment>): List<Segment> {
        if (segments.size < 2) return segments
        val out = ArrayList<Segment>(segments.size)
        var current = segments[0]
        for (k in 1 until segments.size) {
            val next = segments[k]
            if (next.exercise == current.exercise && next.startMs - current.endMs < 1500L) {
                current = current.copy(
                    endMs = next.endMs,
                    repCount = current.repCount + next.repCount,
                    avgConfidence = (current.avgConfidence + next.avgConfidence) / 2f,
                    avgAudioRms = (current.avgAudioRms + next.avgAudioRms) / 2f,
                    personVisible = current.personVisible || next.personVisible,
                )
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }
}
