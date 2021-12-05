package com.ldlywt.camera.fragments.video

import android.annotation.SuppressLint
import android.content.ContentValues
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.ldlywt.camera.R
import com.ldlywt.camera.databinding.FragmentCameraVideoBinding
import com.ldlywt.camera.fragments.PermissionsFragment
import com.ldlywt.camera.widget.CircleProgressButtonView
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class CameraVideoFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraVideoBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var videoCapture: VideoCapture<Recorder>
    private var activeRecording: ActiveRecording? = null
    private lateinit var recordingState: VideoRecordEvent
    private var isBack = true

    // Camera UI  states and inputs
    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }

    private var cameraIndex = 0
    private var audioEnabled = false

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var enumerationDeferred: Deferred<Unit>? = null

    private suspend fun bindCameraUseCases() {
        val cameraProvider: ProcessCameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
        val cameraSelector = if (isBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
        val preview = Preview.Builder()
                .setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
                .build()
                .apply { setSurfaceProvider(fragmentCameraBinding.previewView.surfaceProvider) }
        // build a recorder, which can:
        //   - record video/audio to MediaStore(only shown here), File, ParcelFileDescriptor
        //   - be used create recording(s) (the recording performs recording)
        val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.of(QualitySelector.QUALITY_SD))
                .build()
        videoCapture = VideoCapture.withOutput(recorder)

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    videoCapture,
                    preview)
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
        enableUI(true)
    }

    /**
     * Kick start the video recording
     *   - config Recorder to capture to MediaStoreOutput
     *   - register RecordEvent Listener
     *   - apply audio request from user
     *   - start recording!
     * After this function, user could start/pause/resume/stop recording and application listens
     * to VideoRecordEvent for the current recording status.
     */
    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val name = "CameraX-recording-" + SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
        }
        val mediaStoreOutput = MediaStoreOutputOptions.Builder(
                requireActivity().contentResolver,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build()

        activeRecording = videoCapture.output.prepareRecording(requireActivity(), mediaStoreOutput)
                .withEventListener(mainThreadExecutor, captureListener)
                .apply { if (audioEnabled) withAudioEnabled() }
                .start()

        Log.i(TAG, "Recording started")
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        if (event !is VideoRecordEvent.Status)
            recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) {
            showVideo(event)
        }
    }

    init {
        enumerationDeferred = lifecycleScope.async {
            whenCreated {
                val provider = ProcessCameraProvider.getInstance(requireContext()).await()

                provider.unbindAll()
                for (camSelector in arrayOf(
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        CameraSelector.DEFAULT_FRONT_CAMERA
                )) {
                    try {
                        // just want to get the camera.cameraInfo to query capabilities
                        // we are not binding anything here.
                        if (provider.hasCamera(camSelector)) {
                            val camera = provider.bindToLifecycle(requireActivity(), camSelector)
                        }
                    } catch (exc: java.lang.Exception) {
                        Log.e(TAG, "Camera Face $camSelector is not supported")
                    }
                }
            }
        }
    }

    private fun initCameraFragment() {
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            if (enumerationDeferred != null) {
                enumerationDeferred!!.await()
                enumerationDeferred = null
            }
            bindCameraUseCases()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        fragmentCameraBinding.cameraButton.apply {
            setOnClickListener {
                isBack = !isBack
                enableUI(false)
                viewLifecycleOwner.lifecycleScope.launch {
                    bindCameraUseCases()
                }
            }
            isEnabled = false
        }

        fragmentCameraBinding.audioSelection.isChecked = audioEnabled
        fragmentCameraBinding.audioSelection.setOnClickListener {
            audioEnabled = fragmentCameraBinding.audioSelection.isChecked
        }

        fragmentCameraBinding.btnRecord.setOnLongClickListener(object : CircleProgressButtonView.OnLongClickListener {
            override fun onLongClick() {
                if (!this@CameraVideoFragment::recordingState.isInitialized || recordingState is VideoRecordEvent.Finalize) {
                    enableUI(false)
                    startRecording()
                }
            }

            override fun onNoMinRecord(currentTime: Int) = Unit

            override fun onRecordFinishedListener() {
                if (activeRecording == null || recordingState is VideoRecordEvent.Finalize) return
                val recording = activeRecording
                if (recording != null) {
                    recording.stop()
                    activeRecording = null
                }
            }

        })

        fragmentCameraBinding.btnRecord.setOnClickListener(object : CircleProgressButtonView.OnClickListener {
            override fun onClick() {
                Toast.makeText(requireContext(), "点击了拍照", Toast.LENGTH_SHORT).show()
            }

        })

        fragmentCameraBinding.captureStatus.text = getString(R.string.Idle)

        fragmentCameraBinding.ivCamera.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

    /**
     * UpdateUI according to CameraX VideoRecordEvent type:
     *   - user starts capture.
     *   - this app disables all UI selections.
     *   - this app enables capture run-time UI (pause/resume/stop).
     *   - user controls recording with run-time UI, eventually tap "stop" to end.
     *   - this app informs CameraX recording to stop with recording.stop() (or recording.close()).
     *   - CameraX notify this app that the recording is indeed stopped, with the Finalize event.
     *   - this app starts VideoViewer fragment to view the captured result.
     */
    private fun updateUI(event: VideoRecordEvent) {
        val state = if (event is VideoRecordEvent.Status) recordingState.getName()
        else event.getName()
        Log.i(TAG, "event.getName(): ${event.getName()}")
        when (event) {
            is VideoRecordEvent.Status -> {
                // placeholder: we update the UI with new status after this when() block,
                // nothing needs to do here.
            }
            is VideoRecordEvent.Start -> {
                showUI(UiState.RECORDING, event.getName())
            }
            is VideoRecordEvent.Finalize -> {
                showUI(UiState.FINALIZED, event.getName())
            }
            is VideoRecordEvent.Pause -> {
            }
            is VideoRecordEvent.Resume -> {
            }
            else -> {
                Log.e(TAG, "Error(Unknown Event) from Recorder")
                return
            }
        }

        val stats = event.recordingStats
        val size = stats.numBytesRecorded / 1000
        val time = java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(stats.recordedDurationNanos)
        var text = "${state}: recorded ${size}KB, in ${time}second"
        if (event is VideoRecordEvent.Finalize)
            text = "${text}\nFile saved to: ${event.outputResults.outputUri}"

        fragmentCameraBinding.captureStatus.text = text
        Log.i(TAG, "recording event: $text")
    }

    /**
     * Enable/disable UI:
     *    User could select the capture parameters when recording is not in session
     *    Once recording is started, need to disable able UI to avoid conflict.
     */
    private fun enableUI(enable: Boolean) {
        arrayOf(fragmentCameraBinding.cameraButton,
                fragmentCameraBinding.audioSelection).forEach {
            it.isEnabled = enable
        }
    }

    private fun showUI(state: UiState, status: String = "idle") {
        Log.i(TAG, "showUI: UiState: $status")
        fragmentCameraBinding.let {
            when (state) {
                UiState.IDLE -> {
                    it.cameraButton.visibility = View.VISIBLE
                    it.audioSelection.visibility = View.VISIBLE
                }
                UiState.RECORDING -> {
                    it.cameraButton.visibility = View.INVISIBLE
                    it.audioSelection.visibility = View.INVISIBLE
                    it.ivCamera.visibility = View.INVISIBLE
                }
                UiState.FINALIZED -> {
                }
                else -> {
                    val errorMsg = "Error: showUI($state) is not supported"
                    Log.e(TAG, errorMsg)
                    return
                }
            }
            it.captureStatus.text = status
        }
    }

    private fun resetUIandState(reason: String) {
        enableUI(true)
        showUI(UiState.IDLE, reason)

        cameraIndex = 0
        audioEnabled = false
        fragmentCameraBinding.audioSelection.isChecked = audioEnabled
    }

    private fun showVideo(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        lifecycleScope.launch {
            findNavController().navigate(CameraVideoFragmentDirections.actionCameraFragmentToVideoViewer(event.outputResults.outputUri))
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
    ): View {
        _fragmentCameraBinding = FragmentCameraVideoBinding.inflate(inflater, container, false)
        initCameraFragment()
        return fragmentCameraBinding.root
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    CameraVideoFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        const val DEFAULT_ASPECT_RATIO = AspectRatio.RATIO_16_9
        val TAG: String = CameraVideoFragment::class.java.simpleName
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss"
    }
}

fun VideoRecordEvent.getName(): String {
    return when (this) {
        is VideoRecordEvent.Status -> "Status"
        is VideoRecordEvent.Start -> "Started"
        is VideoRecordEvent.Finalize -> "Finalized"
        is VideoRecordEvent.Pause -> "Paused"
        is VideoRecordEvent.Resume -> "Resumed"
        else -> "Error(Unknown)"
    }
}