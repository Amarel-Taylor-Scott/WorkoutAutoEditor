package com.workout.autoeditor.ml

import com.workout.autoeditor.data.ExerciseClass
import kotlin.math.sqrt

data class PoseSample(
    val exercise: ExerciseClass,
    val embedding: FloatArray,
)

data class ClassificationResult(
    val exercise: ExerciseClass,
    val confidence: Float,
    val nearestDistance: Float,
)

class KnnClassifier(
    private val samples: List<PoseSample>,
    private val k: Int = 10,
    private val minDistanceForConfidence: Float = 2.5f,
) {
    fun classify(embedding: FloatArray): ClassificationResult {
        if (samples.isEmpty()) {
            return ClassificationResult(ExerciseClass.UNKNOWN, 0f, Float.MAX_VALUE)
        }

        val scored = samples.map { sample ->
            val d = euclidean(embedding, sample.embedding)
            sample to d
        }.sortedBy { it.second }

        val nearestDistance = scored[0].second
        if (nearestDistance > minDistanceForConfidence) {
            return ClassificationResult(ExerciseClass.UNKNOWN, 0f, nearestDistance)
        }

        val topK = scored.take(k.coerceAtMost(scored.size))
        val votes = HashMap<ExerciseClass, Float>()
        var total = 0f
        for ((sample, dist) in topK) {
            val w = 1f / (dist + 1e-6f)
            votes[sample.exercise] = (votes[sample.exercise] ?: 0f) + w
            total += w
        }
        val (winner, winnerWeight) = votes.maxByOrNull { it.value }!!
        val confidence = if (total > 0f) winnerWeight / total else 0f
        return ClassificationResult(winner, confidence, nearestDistance)
    }

    private fun euclidean(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) {
            val d = a[i] - b[i]
            s += d * d
        }
        return sqrt(s)
    }
}
