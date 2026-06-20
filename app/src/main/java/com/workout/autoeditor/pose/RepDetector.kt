package com.workout.autoeditor.pose

import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.data.PoseLandmark
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Counts reps for a given exercise using a joint-angle state machine with hysteresis.
 *
 * Each ExerciseConfig defines:
 *   - which joint angle to track
 *   - the threshold below which the rep is considered in "down" / contracted state
 *   - the threshold above which it is in "up" / extended state
 * A rep is counted on the up->down->up transition pattern (contracted to extended).
 */
class RepDetector(private val config: ExerciseConfig) {

    enum class State { UP, DOWN, UNKNOWN }

    data class ExerciseConfig(
        val exercise: ExerciseClass,
        val angleJoints: Triple<Int, Int, Int>,
        val downAngle: Float,
        val upAngle: Float,
    )

    private var state: State = State.UNKNOWN
    private var reps: Int = 0
    private var startedDownAt: Long? = null

    fun reset() {
        state = State.UNKNOWN
        reps = 0
        startedDownAt = null
    }

    fun update(frame: PoseFrame): Int {
        val angle = jointAngle(frame, config.angleJoints) ?: return reps
        val newState = when {
            angle <= config.downAngle -> State.DOWN
            angle >= config.upAngle -> State.UP
            else -> state
        }
        if (state == State.DOWN && newState == State.UP) {
            reps += 1
        }
        if (state != State.DOWN && newState == State.DOWN) {
            startedDownAt = frame.timestampMs
        }
        state = newState
        return reps
    }

    fun reps(): Int = reps

    companion object {
        val SQUAT = ExerciseConfig(
            exercise = ExerciseClass.SQUAT,
            angleJoints = Triple(23, 25, 27),
            downAngle = 100f,
            upAngle = 160f,
        )
        val PUSHUP = ExerciseConfig(
            exercise = ExerciseClass.PUSHUP,
            angleJoints = Triple(11, 13, 15),
            downAngle = 90f,
            upAngle = 160f,
        )
        val BICEP_CURL = ExerciseConfig(
            exercise = ExerciseClass.BICEP_CURL,
            angleJoints = Triple(11, 13, 15),
            downAngle = 60f,
            upAngle = 150f,
        )
        val OVERHEAD_PRESS = ExerciseConfig(
            exercise = ExerciseClass.OVERHEAD_PRESS,
            angleJoints = Triple(13, 11, 23),
            downAngle = 60f,
            upAngle = 160f,
        )

        fun forExercise(ex: ExerciseClass): ExerciseConfig? = when (ex) {
            ExerciseClass.SQUAT -> SQUAT
            ExerciseClass.PUSHUP -> PUSHUP
            ExerciseClass.BICEP_CURL -> BICEP_CURL
            ExerciseClass.OVERHEAD_PRESS -> OVERHEAD_PRESS
            else -> null
        }

        fun jointAngle(frame: PoseFrame, joints: Triple<Int, Int, Int>): Float? {
            val (i1, i2, i3) = joints
            val lm = frame.landmarks
            if (lm.size <= maxOf(i1, i2, i3)) return null
            val a = lm[i1]
            val b = lm[i2]
            val c = lm[i3]
            if (a.visibility < 0.4f || b.visibility < 0.4f || c.visibility < 0.4f) return null
            return angleBetween(a, b, c)
        }

        private fun angleBetween(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Float {
            val v1x = a.x - b.x
            val v1y = a.y - b.y
            val v2x = c.x - b.x
            val v2y = c.y - b.y
            val dot = v1x * v2x + v1y * v2y
            val n1 = sqrt(v1x * v1x + v1y * v1y)
            val n2 = sqrt(v2x * v2x + v2y * v2y)
            if (n1 == 0f || n2 == 0f) return 0f
            val cosA = (dot / (n1 * n2)).coerceIn(-1f, 1f)
            return (acos(cosA) * 180.0 / PI).toFloat()
        }
    }
}
