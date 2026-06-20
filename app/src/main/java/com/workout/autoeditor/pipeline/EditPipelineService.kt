package com.workout.autoeditor.pipeline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.workout.autoeditor.MainActivity
import com.workout.autoeditor.R
import com.workout.autoeditor.data.EditPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Foreground service that runs the EditPipeline. Holds a low-priority
 * notification so the OS does not kill us during long pose passes.
 *
 * Two-phase API:
 *   1. startParsing(...)        -> moves to PARSING_INSTRUCTION then AWAITING_CONFIRMATION
 *   2. confirmAndProceed(...)   -> moves to PROCESSING then DONE/FAILED
 *
 * UI binds via local Binder and observes phase / parsedPolicy / stage / outputFile / error.
 */
class EditPipelineService : LifecycleService() {

    enum class Phase { IDLE, PARSING_INSTRUCTION, AWAITING_CONFIRMATION, PROCESSING, DONE, FAILED }

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _parsedPolicy = MutableStateFlow<EditPolicy?>(null)
    val parsedPolicy: StateFlow<EditPolicy?> = _parsedPolicy.asStateFlow()

    private val _stage = MutableStateFlow<EditPipeline.Stage>(EditPipeline.Stage.Idle)
    val stage: StateFlow<EditPipeline.Stage> = _stage.asStateFlow()

    private val _outputFile = MutableStateFlow<File?>(null)
    val outputFile: StateFlow<File?> = _outputFile.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pipeline: EditPipeline? = null
    private var pendingSource: Uri? = null
    private var pendingModelPath: String? = null
    private var pendingOutput: File? = null
    private var currentJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    inner class LocalBinder : Binder() {
        fun service(): EditPipelineService = this@EditPipelineService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        pipeline = EditPipeline(this)
        startForegroundCompat()
    }

    override fun onDestroy() {
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    fun startParsing(
        source: Uri,
        instruction: String,
        modelPath: String?,
        output: File,
    ) {
        if (_phase.value == Phase.PARSING_INSTRUCTION || _phase.value == Phase.PROCESSING) return
        pendingSource = source
        pendingModelPath = modelPath
        pendingOutput = output
        _phase.value = Phase.PARSING_INSTRUCTION
        _error.value = null
        currentJob = scope.launch {
            try {
                val policy = pipeline!!.parseInstruction(instruction, modelPath)
                _parsedPolicy.value = policy
                _phase.value = Phase.AWAITING_CONFIRMATION
            } catch (ce: kotlinx.coroutines.CancellationException) {
                // Deliberate cancel - return cleanly, don't show FAILED.
                throw ce
            } catch (t: Throwable) {
                _error.value = t.message ?: t::class.simpleName
                _phase.value = Phase.FAILED
            }
        }
    }

    fun confirmAndProceed(override: EditPolicy?) {
        if (_phase.value != Phase.AWAITING_CONFIRMATION) return
        val source = pendingSource ?: return
        val modelPath = pendingModelPath
        val output = pendingOutput ?: return
        val policy = override ?: _parsedPolicy.value ?: EditPolicy.DEFAULT_TIGHT
        _parsedPolicy.value = policy
        _phase.value = Phase.PROCESSING

        currentJob = scope.launch {
            try {
                pipeline!!.runFromPolicy(source, policy, modelPath, output).collect { st ->
                    _stage.value = st
                    if (st is EditPipeline.Stage.Done) {
                        _outputFile.value = st.outputFile
                        _phase.value = Phase.DONE
                    } else if (st is EditPipeline.Stage.Failed) {
                        _error.value = st.reason
                        _phase.value = Phase.FAILED
                    }
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _error.value = t.message ?: t::class.simpleName
                _phase.value = Phase.FAILED
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _phase.value = Phase.IDLE
        _stage.value = EditPipeline.Stage.Idle
        _parsedPolicy.value = null
        _outputFile.value = null
        _error.value = null
        pendingSource = null
        pendingOutput = null
    }

    fun acknowledgeTerminal() {
        if (_phase.value == Phase.DONE || _phase.value == Phase.FAILED) {
            cancel()
        }
    }

    private fun startForegroundCompat() {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.processing_notification_title))
            .setContentText(getString(R.string.processing_notification_text))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.processing_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.processing_channel_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_ID = "video_processing"
        const val NOTIF_ID = 1001
    }
}
