package com.workout.autoeditor.data

import kotlinx.serialization.Serializable

@Serializable
data class PoseLandmark(
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float,
    val presence: Float,
)

data class PoseFrame(
    val timestampMs: Long,
    val landmarks: List<PoseLandmark>,
) {
    val isPersonVisible: Boolean
        get() = landmarks.isNotEmpty() &&
            landmarks.count { it.visibility > 0.5f } >= 20
}

@Serializable
data class AudioEnvelope(
    val sampleRateHz: Int,
    val windowMs: Int,
    val rms: List<Float>,
) {
    fun rmsAtMs(timeMs: Long): Float {
        val idx = (timeMs / windowMs).toInt().coerceIn(0, rms.size - 1)
        return rms[idx]
    }
}

@Serializable
enum class ExerciseClass {
    UNKNOWN, SQUAT, PUSHUP, BICEP_CURL, OVERHEAD_PRESS, REST, WARMUP, IDLE
}

data class FrameClassification(
    val timestampMs: Long,
    val exercise: ExerciseClass,
    val confidence: Float,
)

data class Segment(
    val startMs: Long,
    val endMs: Long,
    val exercise: ExerciseClass,
    val repCount: Int,
    val avgConfidence: Float,
    val avgAudioRms: Float,
    val personVisible: Boolean,
    val annotations: Map<String, String> = emptyMap(),
) {
    val durationMs: Long get() = endMs - startMs
}

@Serializable
data class EditPolicy(
    val keepClasses: Set<ExerciseClass> = setOf(
        ExerciseClass.SQUAT, ExerciseClass.PUSHUP,
        ExerciseClass.BICEP_CURL, ExerciseClass.OVERHEAD_PRESS,
    ),
    val dropWarmups: Boolean = true,
    val dropRest: Boolean = true,
    val dropIdle: Boolean = true,
    val minRepsPerSegment: Int = 2,
    val minSegmentMs: Long = 1500L,
    val perExerciseCapMs: Long = 25_000L,
    val targetTotalMs: Long? = 90_000L,
    val mergeGapMs: Long = 2_000L,
    val padHeadMs: Long = 200L,
    val padTailMs: Long = 200L,
) {
    companion object {
        val DEFAULT_TIGHT = EditPolicy()
        val DEFAULT_FULL = EditPolicy(
            dropWarmups = false,
            dropRest = true,
            dropIdle = true,
            perExerciseCapMs = 60_000L,
            targetTotalMs = null,
        )
    }
}

data class CutListItem(
    val sourceUri: String,
    val startMs: Long,
    val endMs: Long,
    val exercise: ExerciseClass,
    val rationale: String,
)

data class CutList(
    val sourceUri: String,
    val items: List<CutListItem>,
    val totalMs: Long,
)
