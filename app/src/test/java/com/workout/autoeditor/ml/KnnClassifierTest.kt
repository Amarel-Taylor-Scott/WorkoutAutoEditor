package com.workout.autoeditor.ml

import com.workout.autoeditor.data.ExerciseClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KnnClassifierTest {

    private fun vec(seed: Float, dim: Int = 40): FloatArray = FloatArray(dim) { seed + it * 0.001f }

    @Test
    fun empty_store_returns_unknown() {
        val k = KnnClassifier(emptyList())
        val r = k.classify(vec(0f))
        assertEquals(ExerciseClass.UNKNOWN, r.exercise)
    }

    @Test
    fun nearest_cluster_wins() {
        val samples = listOf(
            PoseSample(ExerciseClass.SQUAT, vec(0.0f)),
            PoseSample(ExerciseClass.SQUAT, vec(0.001f)),
            PoseSample(ExerciseClass.SQUAT, vec(-0.001f)),
            PoseSample(ExerciseClass.PUSHUP, vec(2.0f)),
            PoseSample(ExerciseClass.PUSHUP, vec(2.001f)),
        )
        val k = KnnClassifier(samples, k = 3, minDistanceForConfidence = 5f)
        val r = k.classify(vec(0.0005f))
        assertEquals(ExerciseClass.SQUAT, r.exercise)
        assertTrue(r.confidence > 0.5f)
    }

    @Test
    fun far_query_returns_unknown_due_to_floor() {
        val samples = listOf(PoseSample(ExerciseClass.SQUAT, vec(0f)))
        val k = KnnClassifier(samples, minDistanceForConfidence = 0.1f)
        val r = k.classify(vec(10f))
        assertEquals(ExerciseClass.UNKNOWN, r.exercise)
    }

    @Test
    fun weighted_vote_can_beat_majority() {
        val samples = listOf(
            PoseSample(ExerciseClass.SQUAT, vec(0.0f)),
            PoseSample(ExerciseClass.PUSHUP, vec(1.0f)),
            PoseSample(ExerciseClass.PUSHUP, vec(1.001f)),
            PoseSample(ExerciseClass.PUSHUP, vec(1.002f)),
        )
        val k = KnnClassifier(samples, k = 4, minDistanceForConfidence = 100f)
        val r = k.classify(vec(0.0001f))
        assertEquals(ExerciseClass.SQUAT, r.exercise)
    }
}
