package com.workout.autoeditor.ml

import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.data.PoseLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PoseEmbedderTest {

    private fun standingFrame(originX: Float = 0f, originY: Float = 0f, scale: Float = 1f): PoseFrame {
        val lm = MutableList(33) { PoseLandmark(0f, 0f, 0f, 1f, 1f) }
        fun put(i: Int, x: Float, y: Float) {
            lm[i] = PoseLandmark(originX + x * scale, originY + y * scale, 0f, 1f, 1f)
        }
        put(11, -0.2f, 0f); put(12, 0.2f, 0f)
        put(13, -0.3f, 0.3f); put(14, 0.3f, 0.3f)
        put(15, -0.4f, 0.6f); put(16, 0.4f, 0.6f)
        put(23, -0.15f, 0.5f); put(24, 0.15f, 0.5f)
        put(25, -0.15f, 1.0f); put(26, 0.15f, 1.0f)
        put(27, -0.15f, 1.5f); put(28, 0.15f, 1.5f)
        return PoseFrame(0L, lm)
    }

    @Test
    fun translation_invariance() {
        val a = PoseEmbedder.embed(standingFrame())!!
        val b = PoseEmbedder.embed(standingFrame(originX = 0.5f, originY = -0.3f))!!
        for (i in a.indices) assertTrue("dim $i", abs(a[i] - b[i]) < 1e-4f)
    }

    @Test
    fun scale_invariance() {
        val a = PoseEmbedder.embed(standingFrame())!!
        val b = PoseEmbedder.embed(standingFrame(scale = 2.5f))!!
        for (i in a.indices) assertTrue("dim $i", abs(a[i] - b[i]) < 1e-4f)
    }

    @Test
    fun returns_null_when_visibility_low() {
        val frame = standingFrame()
        val lm = frame.landmarks.toMutableList()
        lm[11] = lm[11].copy(visibility = 0.1f)
        val frame2 = PoseFrame(0L, lm)
        assertNull(PoseEmbedder.embed(frame2))
    }

    @Test
    fun dimensions_match() {
        val emb = PoseEmbedder.embed(standingFrame())
        assertNotNull(emb)
        assertEquals(40, emb!!.size)
    }

    @Test
    fun distinct_poses_produce_distinct_embeddings() {
        val standing = PoseEmbedder.embed(standingFrame())!!
        val crouchedFrame = run {
            val lm = standingFrame().landmarks.toMutableList()
            lm[25] = lm[25].copy(y = lm[23].y + 0.05f)
            lm[26] = lm[26].copy(y = lm[24].y + 0.05f)
            PoseFrame(0L, lm)
        }
        val crouched = PoseEmbedder.embed(crouchedFrame)!!
        var dist = 0f
        for (i in standing.indices) {
            val d = standing[i] - crouched[i]
            dist += d * d
        }
        assertTrue("expected nonzero distance, got ${sqrt(dist)}", sqrt(dist) > 0.05f)
    }
}
