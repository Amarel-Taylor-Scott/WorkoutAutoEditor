package com.workout.autoeditor.pipeline

import android.content.Context
import android.net.Uri
import com.workout.autoeditor.audio.AudioAnalyzer
import com.workout.autoeditor.data.CutList
import com.workout.autoeditor.data.EditPolicy
import com.workout.autoeditor.edit.CutListBuilder
import com.workout.autoeditor.edit.VideoEditor
import com.workout.autoeditor.llm.GemmaService
import com.workout.autoeditor.llm.InstructionParser
import com.workout.autoeditor.llm.KeyframeAnnotator
import com.workout.autoeditor.ml.KnnClassifier
import com.workout.autoeditor.ml.TrainingDataStore
import com.workout.autoeditor.pose.ExerciseClassifier
import com.workout.autoeditor.pose.PoseAnalyzer
import com.workout.autoeditor.pose.TimelineBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * Orchestrates the full pipeline. Resources are opened and closed in sequence
 * so we never hold Gemma + Pose Landmarker in memory simultaneously.
 *
 * Two phases:
 *   - parseInstruction(...)  -> EditPolicy   (cheap; LLM-optional, falls back to DEFAULT_TIGHT)
 *   - runFromPolicy(...)     -> File          (heavy: pose / audio / [LLM annotate] / render)
 *
 * If `modelPath` is null or points at a missing file, the LLM steps are skipped
 * cleanly and the cut-list is built from cheap signals alone.
 */
class EditPipeline(private val ctx: Context) {

    sealed class Stage {
        data object Idle : Stage()
        data object ParsingInstruction : Stage()
        data object Pose : Stage()
        data object Audio : Stage()
        data object Timeline : Stage()
        data object Keyframes : Stage()
        data object Cutting : Stage()
        data object Rendering : Stage()
        data class Done(val outputFile: File) : Stage()
        data class Failed(val reason: String) : Stage()
    }

    private fun llmAvailable(modelPath: String?): Boolean {
        if (modelPath.isNullOrBlank()) return false
        val f = File(modelPath)
        return f.exists() && f.length() > 1_500_000_000L
    }

    suspend fun parseInstruction(instruction: String, modelPath: String?): EditPolicy {
        if (!llmAvailable(modelPath)) return EditPolicy.DEFAULT_TIGHT
        val gemma = GemmaService(ctx, modelPath!!)
        return try {
            InstructionParser(gemma).parse(instruction)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w("EditPipeline", "InstructionParser failed, using DEFAULT_TIGHT", t)
            EditPolicy.DEFAULT_TIGHT
        } finally {
            runCatching { gemma.close() }
        }
    }

    fun runFromPolicy(
        sourceUri: Uri,
        policy: EditPolicy,
        modelPath: String?,
        outputFile: File,
    ): Flow<Stage> = flow {
        try {
            emit(Stage.Pose)
            val store = TrainingDataStore(ctx)
            val knn = if (store.count() >= 10) KnnClassifier(store.load()) else null
            val classifier = ExerciseClassifier(knn)

            val poseAnalyzer = PoseAnalyzer(ctx)
            val frames = try {
                poseAnalyzer.analyze(sourceUri, sampleFps = 5f)
            } finally {
                poseAnalyzer.close()
            }

            emit(Stage.Audio)
            val envelope = AudioAnalyzer(ctx).analyze(sourceUri)

            emit(Stage.Timeline)
            val segments = TimelineBuilder(classifier).build(frames, envelope)

            val annotated = if (llmAvailable(modelPath)) {
                emit(Stage.Keyframes)
                val gemma = GemmaService(ctx, modelPath!!)
                try {
                    KeyframeAnnotator(gemma).annotate(segments)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w("EditPipeline", "Keyframe annotation failed, skipping", t)
                    segments
                } finally {
                    runCatching { gemma.close() }
                }
            } else segments

            emit(Stage.Cutting)
            val cutList: CutList = CutListBuilder(policy).build(sourceUri.toString(), annotated)
            if (cutList.items.isEmpty()) {
                emit(Stage.Failed("no segments survived the cut policy"))
                return@flow
            }

            emit(Stage.Rendering)
            val out = VideoEditor(ctx).render(cutList, outputFile)
            emit(Stage.Done(out))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(Stage.Failed(t.message ?: t::class.simpleName ?: "unknown"))
        }
    }
}
