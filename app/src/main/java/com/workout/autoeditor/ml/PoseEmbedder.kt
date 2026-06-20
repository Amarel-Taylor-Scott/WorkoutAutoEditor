package com.workout.autoeditor.ml

import com.workout.autoeditor.data.PoseFrame
import kotlin.math.sqrt

/**
 * Translation- and scale-invariant pose embedding.
 *
 * Recipe:
 *   1. Translate all landmarks so the hip midpoint is the origin.
 *   2. Scale so the torso (hip-midpoint to shoulder-midpoint) has unit length.
 *   3. Output (dx, dy) for each pair in PAIRS - directional, so the classifier
 *      can distinguish wrist-above-shoulder (press) from wrist-below-shoulder (curl).
 */
object PoseEmbedder {

    private const val LSH = 11
    private const val RSH = 12
    private const val LELB = 13
    private const val RELB = 14
    private const val LWR = 15
    private const val RWR = 16
    private const val LHIP = 23
    private const val RHIP = 24
    private const val LKNEE = 25
    private const val RKNEE = 26
    private const val LANK = 27
    private const val RANK = 28

    val PAIRS: List<Pair<Int, Int>> = listOf(
        LHIP to LSH, RHIP to RSH,
        LSH to LELB, RSH to RELB,
        LELB to LWR, RELB to RWR,
        LSH to LWR, RSH to RWR,
        LHIP to LKNEE, RHIP to RKNEE,
        LKNEE to LANK, RKNEE to RANK,
        LHIP to LANK, RHIP to RANK,
        LSH to LHIP, RSH to RHIP,
        LSH to RSH, LHIP to RHIP,
        LWR to LHIP, RWR to RHIP,
    )

    const val DIM = 40

    private const val MIN_VISIBILITY = 0.4f
    private const val MIN_TORSO_PX = 1e-4f

    fun embed(frame: PoseFrame): FloatArray? {
        val lm = frame.landmarks
        if (lm.size < 33) return null

        val keyIndices = listOf(LSH, RSH, LHIP, RHIP)
        if (keyIndices.any { lm[it].visibility < MIN_VISIBILITY }) return null

        val hipCx = (lm[LHIP].x + lm[RHIP].x) / 2f
        val hipCy = (lm[LHIP].y + lm[RHIP].y) / 2f
        val shCx = (lm[LSH].x + lm[RSH].x) / 2f
        val shCy = (lm[LSH].y + lm[RSH].y) / 2f

        val torsoDx = shCx - hipCx
        val torsoDy = shCy - hipCy
        val torso = sqrt(torsoDx * torsoDx + torsoDy * torsoDy)
        if (torso < MIN_TORSO_PX) return null

        val out = FloatArray(DIM)
        var k = 0
        for ((a, b) in PAIRS) {
            val dx = (lm[b].x - lm[a].x) / torso
            val dy = (lm[b].y - lm[a].y) / torso
            out[k++] = dx
            out[k++] = dy
        }
        return out
    }

    fun torsoSize(frame: PoseFrame): Float {
        val lm = frame.landmarks
        if (lm.size < 33) return 0f
        val hipCx = (lm[LHIP].x + lm[RHIP].x) / 2f
        val hipCy = (lm[LHIP].y + lm[RHIP].y) / 2f
        val shCx = (lm[LSH].x + lm[RSH].x) / 2f
        val shCy = (lm[LSH].y + lm[RSH].y) / 2f
        val dx = shCx - hipCx
        val dy = shCy - hipCy
        return sqrt(dx * dx + dy * dy)
    }
}
