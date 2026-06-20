package com.workout.autoeditor.edit

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.workout.autoeditor.data.CutList
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Hardware-accelerated video editor backed by Media3 Transformer.
 *
 * Each cut becomes a clipped MediaItem; the EditedMediaItemSequence concats
 * them; the Transformer exports to an MP4 at outputFile.
 */
class VideoEditor(private val ctx: Context) {

    suspend fun render(cutList: CutList, outputFile: File): File =
        suspendCancellableCoroutine { cont ->
            if (cutList.items.isEmpty()) {
                cont.resumeWithException(IllegalArgumentException("empty cut list"))
                return@suspendCancellableCoroutine
            }
            val sourceUri = Uri.parse(cutList.sourceUri)

            val edited = cutList.items.map { item ->
                val media = MediaItem.Builder()
                    .setUri(sourceUri)
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(item.startMs)
                            .setEndPositionMs(item.endMs)
                            .build(),
                    )
                    .build()
                EditedMediaItem.Builder(media).build()
            }
            val sequence = EditedMediaItemSequence.Builder(edited).build()
            val composition = Composition.Builder(sequence).build()

            val transformer = Transformer.Builder(ctx)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(comp: Composition, result: ExportResult) {
                        if (cont.isActive) cont.resume(outputFile)
                    }

                    override fun onError(comp: Composition, result: ExportResult, exception: ExportException) {
                        if (cont.isActive) cont.resumeWithException(exception)
                    }
                })
                .build()

            try {
                if (outputFile.exists()) outputFile.delete()
                outputFile.parentFile?.mkdirs()
                transformer.start(composition, outputFile.absolutePath)
            } catch (t: Throwable) {
                if (cont.isActive) cont.resumeWithException(t)
            }

            cont.invokeOnCancellation {
                try { transformer.cancel() } catch (_: Throwable) {}
            }
        }
}
