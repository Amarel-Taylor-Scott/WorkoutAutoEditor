package com.workout.autoeditor.pipeline

import android.content.Context
import com.workout.autoeditor.data.AppPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resumable HTTP download with progress events.
 *
 * Default URL points at a Hugging Face hosted Gemma model. Most Gemma URLs
 * are gated and return HTTP 401; supply a token via AppPrefs.hfToken or
 * override the URL via AppPrefs.customModelUrl to point at a public host.
 */
class ModelDownloader(private val ctx: Context) {

    companion object {
        // Gemma 4 E2B (2 B effective params, INT4) - publicly downloadable (no
        // HF token required), confirmed via HEAD: 200 OK, ~1.86 GB.
        const val DEFAULT_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it-web.task"
        private const val MODELS_DIR = "models"
        private const val MODEL_FILE = "gemma.task"
    }

    private val prefs by lazy { AppPrefs(ctx) }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            // Security: if the user attaches a HF bearer token, we MUST NOT
            // follow redirects, otherwise a malicious redirect could exfil
            // the token to an attacker host via the Authorization header.
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    /** Block schemes other than https to prevent SSRF / file:// reads. */
    private fun isUrlSafe(url: String): Boolean = url.startsWith("https://")

    fun modelFile(): File {
        val dir = File(ctx.getExternalFilesDir(null), MODELS_DIR).apply { mkdirs() }
        return File(dir, MODEL_FILE)
    }

    fun isModelPresent(minBytes: Long = 1_500_000_000L): Boolean {
        val f = modelFile()
        return f.exists() && f.length() >= minBytes
    }

    /** Effective URL — user override wins over default. */
    fun activeUrl(): String = prefs.customModelUrl ?: DEFAULT_MODEL_URL

    sealed class Event {
        data class Progress(val downloadedBytes: Long, val totalBytes: Long) : Event()
        data class Done(val file: File) : Event()
        data class Failed(val reason: String, val httpCode: Int? = null) : Event()
    }

    fun download(
        url: String = activeUrl(),
        token: String? = prefs.hfToken,
    ): Flow<Event> = flow<Event> {
        if (!isUrlSafe(url)) {
            emit(Event.Failed("Model URL must start with https:// — file:// and http:// are not allowed."))
            return@flow
        }
        val target = modelFile()
        val partial = File(target.parentFile, target.name + ".part")
        val resumeFrom = if (partial.exists()) partial.length() else 0L

        val req = Request.Builder()
            .url(url)
            .apply {
                if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        val response = try {
            client.newCall(req).execute()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(Event.Failed("network: ${t.message ?: t::class.simpleName}"))
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful && resp.code != 206) {
                val msg = when (resp.code) {
                    401 -> "Model URL needs authentication (401). Add a Hugging Face token in Configure, or use a public model URL."
                    403 -> "Access forbidden (403). Accept the model license on Hugging Face first, then add a token."
                    404 -> "Model not found at this URL (404). Check the URL in Configure."
                    in 500..599 -> "Server error ${resp.code}. Try again later."
                    else -> "HTTP ${resp.code}"
                }
                emit(Event.Failed(msg, resp.code))
                return@flow
            }
            val body = resp.body ?: run {
                emit(Event.Failed("empty response body"))
                return@flow
            }
            val totalAdditional = body.contentLength().takeIf { it >= 0L } ?: -1L
            val totalBytes = if (totalAdditional > 0) resumeFrom + totalAdditional else -1L

            partial.parentFile?.mkdirs()
            val out = if (resumeFrom > 0) {
                java.io.RandomAccessFile(partial, "rw").apply { seek(resumeFrom) }
            } else null
            val sink: java.io.OutputStream = if (out != null) {
                object : java.io.OutputStream() {
                    override fun write(b: Int) { out.write(b) }
                    override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
                    override fun close() { out.close() }
                }
            } else java.io.FileOutputStream(partial)

            val src = body.byteStream()
            try {
                val buf = ByteArray(64 * 1024)
                var written = resumeFrom
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    written += n
                    emit(Event.Progress(written, totalBytes))
                }
                sink.flush()
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                emit(Event.Failed("io: ${t.message ?: t::class.simpleName}"))
                return@flow
            } finally {
                runCatching { sink.close() }
                runCatching { src.close() }
            }
        }

        if (target.exists()) target.delete()
        if (!partial.renameTo(target)) {
            emit(Event.Failed("could not finalize file rename"))
            return@flow
        }
        emit(Event.Done(target))
    }.flowOn(Dispatchers.IO)
}
