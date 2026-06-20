package com.workout.autoeditor.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable

/**
 * Thin wrapper around MediaPipe LlmInference (Gemma on-device).
 *
 * Designed for MediaPipe tasks-genai 0.10.14 surface:
 *   - `LlmInference.createFromOptions(ctx, LlmInferenceOptions.builder()...)` constructor
 *   - `inference.generateResponse(prompt: String): String`
 *   - `inference.close()`
 *
 * If you bump tasks-genai versions and the API drifts, this is the file to edit.
 */
class GemmaService(
    private val ctx: Context,
    private val modelPath: String,
    private val maxTokens: Int = 512,
    private val temperature: Float = 0.2f,
    private val topK: Int = 40,
) : Closeable {

    @Volatile private var inference: LlmInference? = null

    private fun ensureLoaded(): LlmInference {
        inference?.let { return it }
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(maxTokens)
            .setTopK(topK)
            .setTemperature(temperature)
            .build()
        val inf = LlmInference.createFromOptions(ctx, opts)
        inference = inf
        return inf
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        val inf = ensureLoaded()
        try {
            inf.generateResponse(prompt) ?: ""
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w("GemmaService", "generateResponse failed", t)
            ""
        }
    }

    override fun close() {
        try {
            inference?.close()
        } catch (_: Throwable) {
        }
        inference = null
    }
}
