package com.workout.autoeditor.pose

import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.ml.KnnClassifier
import com.workout.autoeditor.ml.PoseEmbedder
import kotlin.math.abs

/**
 * Classifies a pose frame as an exercise. Uses KNN over learned embeddings if
 * a classifier is provided; falls back to rule-based heuristics otherwise.
 *
 * Rules cover squat / pushup / bicep curl / overhead press based on:
 *   - body orientation (vertical vs horizontal torso)
 *   - wrist position relative to shoulder
 */
class ExerciseClassifier(private val knn: KnnClassifier? = null) {

    data class Decision(
        val exercise: ExerciseClass,
        val confidence: Float,
    )

    fun classify(frame: PoseFrame): Decision {
        if (knn != null) {
            val emb = PoseEmbedder.embed(frame)
            if (emb != null) {
                val r = knn.classify(emb)
                if (r.exercise != ExerciseClass.UNKNOWN) return Decision(r.exercise, r.confidence)
            }
        }
        return ruleBased(frame)
    }

    private fun ruleBased(frame: PoseFrame): Decision {
        val lm = frame.landmarks
        if (lm.size < 33 || !frame.isPersonVisible) return Decision(ExerciseClass.UNKNOWN, 0f)

        val lsh = lm[11]; val rsh = lm[12]
        val lhip = lm[23]; val rhip = lm[24]
        val lwr = lm[15]; val rwr = lm[16]

        val torsoVert = abs(((lsh.y + rsh.y) / 2f) - ((lhip.y + rhip.y) / 2f))
        val torsoHoriz = abs(((lsh.x + rsh.x) / 2f) - ((lhip.x + rhip.x) / 2f))
        val isHorizontal = torsoHoriz > torsoVert

        if (isHorizontal) {
            return Decision(ExerciseClass.PUSHUP, 0.5f)
        }

        val shY = (lsh.y + rsh.y) / 2f
        val wrY = (lwr.y + rwr.y) / 2f
        val wristAboveShoulder = wrY < shY - 0.05f
        val wristNearShoulder = abs(wrY - shY) < 0.10f

        if (wristAboveShoulder) {
            return Decision(ExerciseClass.OVERHEAD_PRESS, 0.5f)
        }
        if (wristNearShoulder) {
            return Decision(ExerciseClass.BICEP_CURL, 0.5f)
        }

        val kneeAngle = RepDetector.jointAngle(frame, Triple(23, 25, 27))
        if (kneeAngle != null && kneeAngle < 150f) {
            return Decision(ExerciseClass.SQUAT, 0.5f)
        }

        return Decision(ExerciseClass.IDLE, 0.3f)
    }
}
