package com.workout.autoeditor.pose

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import com.workout.autoeditor.data.PoseFrame
import com.workout.autoeditor.data.PoseLandmark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Runs MediaPipe Pose Landmarker over a saved video clip, returning a list of
 * PoseFrames sampled at the configured fps.
 *
 * Requires `pose_landmarker_full.task` in the assets dir.
 */
class PoseAnalyzer(
    private val ctx: Context,
    private val assetName: String = "pose_landmarker_full.task",
) : Closeable {

    @Volatile private var landmarker: PoseLandmarker? = null

    private fun ensureLoaded(): PoseLandmarker {
        landmarker?.let { return it }
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(assetName)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .build()
        val l = PoseLandmarker.createFromOptions(ctx, options)
        landmarker = l
        return l
    }

    suspend fun analyze(
        videoUri: Uri,
        sampleFps: Float = 5f,
        onProgress: ((Float) -> Unit)? = null,
    ): List<PoseFrame> = withContext(Dispatchers.Default) {
        val l = ensureLoaded()
        val out = ArrayList<PoseFrame>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(ctx, videoUri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return@withContext emptyList()
            val stepMs = (1000f / sampleFps).toLong().coerceAtLeast(33L)
            var t = 0L
            while (t < durationMs) {
                val bmp: Bitmap? = retriever.getFrameAtTime(
                    t * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (bmp != null) {
                    val mpImage = BitmapImageBuilder(bmp).build()
                    val result: PoseLandmarkerResult = l.detectForVideo(mpImage, t)
                    val landmarks = result.landmarks().firstOrNull()?.map { p ->
                        PoseLandmark(
                            x = p.x(),
                            y = p.y(),
                            z = p.z(),
                            visibility = p.visibility().orElse(0f),
                            presence = p.presence().orElse(0f),
                        )
                    } ?: emptyList()
                    out += PoseFrame(t, landmarks)
                    bmp.recycle()
                }
                onProgress?.invoke(t.toFloat() / durationMs.toFloat())
                t += stepMs
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        out
    }

    override fun close() {
        try {
            landmarker?.close()
        } catch (_: Throwable) {
        }
        landmarker = null
    }
}
