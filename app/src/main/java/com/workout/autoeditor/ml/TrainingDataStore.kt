package com.workout.autoeditor.ml

import android.content.Context
import com.workout.autoeditor.data.ExerciseClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredSample(
    val exercise: String,
    val embedding: FloatArray,
    val schemaVersion: Int,
    val state: String? = null,
    val sourceClip: String? = null,
    val createdAtMs: Long,
)

@Serializable
data class StoredSamples(val samples: List<StoredSample>)

class TrainingDataStore(private val ctx: Context) {
    companion object {
        const val SCHEMA_VERSION = 1
        private const val DIR = "training"
        private const val FILE = "samples.json"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    private fun file(): File {
        val dir = File(ctx.getExternalFilesDir(null), DIR).apply { mkdirs() }
        return File(dir, FILE)
    }

    fun load(): List<PoseSample> {
        val f = file()
        if (!f.exists()) return emptyList()
        return try {
            val stored = json.decodeFromString(StoredSamples.serializer(), f.readText())
            stored.samples
                .filter { it.schemaVersion == SCHEMA_VERSION }
                .mapNotNull { s ->
                    val ex = runCatching { ExerciseClass.valueOf(s.exercise) }.getOrNull() ?: return@mapNotNull null
                    PoseSample(ex, s.embedding)
                }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun appendAll(newSamples: List<StoredSample>) {
        val f = file()
        val existing = if (f.exists()) {
            runCatching { json.decodeFromString(StoredSamples.serializer(), f.readText()).samples }
                .getOrDefault(emptyList())
        } else emptyList()
        val combined = StoredSamples(existing + newSamples)
        f.writeText(json.encodeToString(StoredSamples.serializer(), combined))
    }

    fun count(): Int = load().size

    fun countsByClass(): Map<ExerciseClass, Int> =
        load().groupingBy { it.exercise }.eachCount()

    fun clearAll() {
        val f = file()
        if (f.exists()) f.delete()
    }
}
