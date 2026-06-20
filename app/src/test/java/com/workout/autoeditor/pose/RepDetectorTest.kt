package com.workout.autoeditor.pose

import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.data.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepDetectorTest {

    private fun frame(t: Long, kneeAngleDeg: Float): PoseFrame {
        // Builds a synthetic pose where the angle hip(23)-knee(25)-ankle(27)
        // equals kneeAngleDeg. Hip at origin, knee directly below at (0, 0.5),
        // ankle swung out so the bend at the knee is exactly the requested angle.
        // For θ=180 ankle=(0,1) (straight leg), θ=90 ankle=(0.5,0.5) (right angle),
        // θ=0 ankle=(0,0) (fully folded).
        val lm = MutableList(33) { PoseLandmark(0f, 0f, 0f, 1f, 1f) }
        val rad = Math.toRadians(kneeAngleDeg.toDouble())
        val sinT = Math.sin(rad).toFloat()
        val cosT = Math.cos(rad).toFloat()
        lm[23] = PoseLandmark(0f, 0f, 0f, 1f, 1f)
        lm[25] = PoseLandmark(0f, 0.5f, 0f, 1f, 1f)
        lm[27] = PoseLandmark(0.5f * sinT, 0.5f - 0.5f * cosT, 0f, 1f, 1f)
        return PoseFrame(t, lm)
    }

    @Test
    fun counts_three_squats_when_alternating_angles() {
        val det = RepDetector(RepDetector.SQUAT)
        val pattern = floatArrayOf(170f, 90f, 170f, 90f, 170f, 90f, 170f)
        for ((i, a) in pattern.withIndex()) det.update(frame((i * 100).toLong(), a))
        assertEquals(3, det.reps())
    }

    @Test
    fun does_not_count_partial_reps_above_down_threshold() {
        val det = RepDetector(RepDetector.SQUAT)
        val pattern = floatArrayOf(170f, 130f, 170f, 130f, 170f)
        for ((i, a) in pattern.withIndex()) det.update(frame((i * 100).toLong(), a))
        assertEquals(0, det.reps())
    }

    @Test
    fun reset_clears_state() {
        val det = RepDetector(RepDetector.SQUAT)
        for ((i, a) in floatArrayOf(170f, 90f, 170f).withIndex()) det.update(frame((i * 100).toLong(), a))
        assertTrue(det.reps() == 1)
        det.reset()
        assertEquals(0, det.reps())
    }
}
