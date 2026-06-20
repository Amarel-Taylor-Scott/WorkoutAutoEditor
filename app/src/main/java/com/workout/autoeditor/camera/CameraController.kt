package com.workout.autoeditor.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.workout.autoeditor.camera.CameraController.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Wraps CameraX Recorder for fixed-quality 1080p workout recording.
 *
 * Recording goes to app-specific external storage so we don't need
 * MediaStore permissions on minSdk 29+.
 */
class CameraController(private val context: Context) {

    sealed class RecordingState {
        data object Idle : RecordingState()
        data class Recording(val durationMs: Long) : RecordingState()
        data class Finalized(val file: File) : RecordingState()
        data class Failed(val reason: String) : RecordingState()
    }

    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var recorder: Recorder? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null

    suspend fun bind(
        previewView: PreviewView,
        owner: LifecycleOwner,
    ) {
        // ListenableFuture.get() blocks the calling thread. Move off main.
        val provider = withContext(Dispatchers.IO) {
            ProcessCameraProvider.getInstance(context).get()
        }
        provider.unbindAll()
        val preview = androidx.camera.core.Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val rec = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.FHD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)))
            .build()
        recorder = rec
        videoCapture = VideoCapture.withOutput(rec)
        provider.bindToLifecycle(
            owner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            videoCapture,
        )
    }

    fun startRecording(executor: Executor): File? {
        val cap = videoCapture ?: return null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) return null

        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }
        val name = "workout-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.mp4"
        val out = File(dir, name)

        val opts = FileOutputOptions.Builder(out).build()
        val pending = cap.output.prepareRecording(context, opts)
        val withAudio = if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            pending.withAudioEnabled()
        } else pending

        activeRecording = withAudio.start(executor) { event ->
            when (event) {
                is VideoRecordEvent.Start -> _state.value = RecordingState.Recording(0L)
                is VideoRecordEvent.Status -> {
                    val ns = event.recordingStats.recordedDurationNanos
                    _state.value = RecordingState.Recording(ns / 1_000_000L)
                }
                is VideoRecordEvent.Finalize -> {
                    if (event.hasError()) {
                        _state.value = RecordingState.Failed(event.cause?.message ?: "error ${event.error}")
                    } else {
                        _state.value = RecordingState.Finalized(out)
                    }
                    activeRecording = null
                }
            }
        }
        return out
    }

    fun stop() {
        activeRecording?.stop()
    }

    fun pause() { activeRecording?.pause() }
    fun resume() { activeRecording?.resume() }

    fun reset() {
        _state.value = RecordingState.Idle
    }

    fun fileForState(): File? = when (val s = _state.value) {
        is RecordingState.Finalized -> s.file
        else -> null
    }

    @Suppress("unused")
    private fun debugRef() = RecordingState.Idle
}
