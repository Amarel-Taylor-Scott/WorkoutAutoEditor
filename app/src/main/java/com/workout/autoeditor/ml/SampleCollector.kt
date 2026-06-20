package com.workout.autoeditor.ml

import android.content.Context
import android.net.Uri
import com.workout.autoeditor.data.ExerciseClass
import com.workout.autoeditor.pose.PoseAnalyzer

class SampleCollector(
    private val ctx: Context,
    private val store: TrainingDataStore,
) {
    companion object {
        private const val MAX_PER_CLIP = 60
        private const val MIN_TORSO = 0.05f
    }

    suspend fun ingestClip(
        videoUri: Uri,
        label: ExerciseClass,
        sourceTag: String? = null,
        progress: ((Float) -> Unit)? = null,
    ): Int {
        val analyzer = PoseAnalyzer(ctx)
        val frames = try {
            analyzer.analyze(videoUri, sampleFps = 5f, onProgress = progress)
        } finally {
            analyzer.close()
        }

        val candidates = frames.mapNotNull { frame ->
            if (PoseEmbedder.torsoSize(frame) < MIN_TORSO) return@mapNotNull null
            PoseEmbedder.embed(frame)
        }

        val subsampled = if (candidates.size <= MAX_PER_CLIP) candidates else {
            val step = candidates.size.toDouble() / MAX_PER_CLIP
            (0 until MAX_PER_CLIP).map { candidates[(it * step).toInt()] }
        }

        val now = System.currentTimeMillis()
        val stored = subsampled.map {
            StoredSample(
                exercise = label.name,
                embedding = it,
                schemaVersion = TrainingDataStore.SCHEMA_VERSION,
                sourceClip = sourceTag,
                createdAtMs = now,
            )
        }
        store.appendAll(stored)
        return stored.size
    }
}
