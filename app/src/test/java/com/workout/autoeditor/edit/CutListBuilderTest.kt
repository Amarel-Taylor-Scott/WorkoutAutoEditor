package com.workout.autoeditor.edit

import com.workout.autoeditor.data.EditPolicy
import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.data.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutListBuilderTest {

    private fun seg(
        startMs: Long, endMs: Long, ex: ExerciseClass,
        reps: Int = 5, conf: Float = 0.9f, visible: Boolean = true,
        annotations: Map<String, String> = emptyMap(),
    ) = Segment(startMs, endMs, ex, reps, conf, 0.05f, visible, annotations)

    @Test
    fun drops_warmups_when_policy_says_so() {
        val policy = EditPolicy(dropWarmups = true)
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 5000, ExerciseClass.WARMUP),
            seg(5000, 12000, ExerciseClass.SQUAT, reps = 6),
        )
        val cut = builder.build("file://x", segments)
        assertEquals(1, cut.items.size)
        assertEquals(ExerciseClass.SQUAT, cut.items[0].exercise)
    }

    @Test
    fun drops_low_rep_segments() {
        val policy = EditPolicy(minRepsPerSegment = 3)
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 5000, ExerciseClass.SQUAT, reps = 1),
            seg(5000, 12000, ExerciseClass.SQUAT, reps = 6),
        )
        val cut = builder.build("file://x", segments)
        assertEquals(1, cut.items.size)
    }

    @Test
    fun caps_per_exercise_duration() {
        val policy = EditPolicy(perExerciseCapMs = 10_000L, targetTotalMs = null)
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 6000, ExerciseClass.SQUAT, reps = 5, conf = 0.9f),
            seg(6000, 12000, ExerciseClass.SQUAT, reps = 5, conf = 0.85f),
            seg(12000, 18000, ExerciseClass.SQUAT, reps = 5, conf = 0.7f),
        )
        val cut = builder.build("file://x", segments)
        val total = cut.items.sumOf { it.endMs - it.startMs }
        assertTrue("total $total <= 10000 + padding", total <= 10_000L + 2 * 200L * cut.items.size + 1000)
    }

    @Test
    fun trims_to_target_total() {
        val policy = EditPolicy(targetTotalMs = 8_000L, perExerciseCapMs = 60_000L)
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 6000, ExerciseClass.SQUAT, reps = 5, conf = 0.95f),
            seg(6000, 12000, ExerciseClass.PUSHUP, reps = 5, conf = 0.5f),
            seg(12000, 18000, ExerciseClass.OVERHEAD_PRESS, reps = 5, conf = 0.85f),
        )
        val cut = builder.build("file://x", segments)
        val total = cut.items.sumOf { it.endMs - it.startMs }
        assertTrue("total $total within ~8s", total <= 8_000L + 2 * 200L * cut.items.size + 500)
    }

    @Test
    fun drops_invisible_person_segments() {
        val policy = EditPolicy()
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 6000, ExerciseClass.SQUAT, reps = 5, visible = false),
            seg(6000, 12000, ExerciseClass.SQUAT, reps = 5, visible = true),
        )
        val cut = builder.build("file://x", segments)
        assertEquals(1, cut.items.size)
    }

    @Test
    fun ranks_higher_confidence_when_capping() {
        val policy = EditPolicy(perExerciseCapMs = 6_000L, targetTotalMs = null)
        val builder = CutListBuilder(policy)
        val segments = listOf(
            seg(0, 6000, ExerciseClass.SQUAT, reps = 5, conf = 0.5f),
            seg(7000, 13000, ExerciseClass.SQUAT, reps = 5, conf = 0.95f),
        )
        val cut = builder.build("file://x", segments)
        assertEquals(1, cut.items.size)
        assertTrue(cut.items[0].rationale.contains("conf=0.95"))
    }
}
