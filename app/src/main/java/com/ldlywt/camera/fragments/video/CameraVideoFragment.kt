package com.ldlywt.camera.fragments.video

import android.annotation.SuppressLint
import android.os.Bundle
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
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.ldlywt.camera.MainActivity
import com.ldlywt.camera.R
import com.ldlywt.camera.databinding.FragmentCameraVideoBinding
import com.ldlywt.camera.fragments.PermissionsFragment
import com.ldlywt.camera.widget.CircleProgressButtonView
import kotlinx.coroutines.*
import java.io.File
import java.util.*

class CameraVideoFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraVideoBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var outputDirectory: File
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var activeRecording: ActiveRecording? = null
    private lateinit var recordingState: VideoRecordEvent
    private var isBack = true
    private var audioEnabled = false
    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }

    enum class UiState {
        IDLE,       // Not recording, all UI controls are active.
        RECORDING,  // Camera is recording, only display Pause/Resume & Stop button.
        FINALIZED,  // Recording just completes, disable all RECORDING UI controls.
        RECOVERY    // For future use.
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
    ): View {
        _fragmentCameraBinding = FragmentCameraVideoBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
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

    private suspend fun bindCameraUseCases() {
        val cameraProvider: ProcessCameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
        val cameraSelector = if (isBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
                .setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
                .build()
                .apply { setSurfaceProvider(fragmentCameraBinding.previewView.surfaceProvider) }

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
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val outFile = MainActivity.createFile(outputDirectory, FILENAME, VIDEO_EXTENSION)
        Log.i("wutao--> ", "outFile: $outFile")
        val outputOptions: FileOutputOptions = FileOutputOptions.Builder(outFile).build()
        activeRecording = videoCapture.output.prepareRecording(requireActivity(), outputOptions)
                .withEventListener(mainThreadExecutor, captureListener)
                .apply { if (audioEnabled) withAudioEnabled() }
                .start()

        Log.i(TAG, "Recording started")
    }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        if (event !is VideoRecordEvent.Status) recordingState = event

        updateUI(event)

        if (event is VideoRecordEvent.Finalize) showVideo(event)
    }

    private fun initCameraFragment() {
        outputDirectory = MainActivity.getOutputDirectory(requireContext())
        initializeUI()
        viewLifecycleOwner.lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    private fun switchCamera() {
        isBack = !isBack
        lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        fragmentCameraBinding.cameraButton.setOnClickListener {
            switchCamera()
        }

        fragmentCameraBinding.audioSelection.isChecked = audioEnabled
        fragmentCameraBinding.audioSelection.setOnClickListener {
            audioEnabled = fragmentCameraBinding.audioSelection.isChecked
        }

        fragmentCameraBinding.btnRecord.setOnLongClickListener(object : CircleProgressButtonView.OnLongClickListener {
            override fun onLongClick() {
                if (!this@CameraVideoFragment::recordingState.isInitialized || recordingState is VideoRecordEvent.Finalize) {
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

        fragmentCameraBinding.btnRecord.setOnClickListener(CircleProgressButtonView.OnClickListener { Toast.makeText(requireContext(), "点击了拍照", Toast.LENGTH_SHORT).show() })

        fragmentCameraBinding.captureStatus.text = getString(R.string.Idle)

        fragmentCameraBinding.ivCamera.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }
    }

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
        showUI(UiState.IDLE, reason)

        audioEnabled = false
        fragmentCameraBinding.audioSelection.isChecked = audioEnabled
    }

    private fun showVideo(event: VideoRecordEvent) {
        if (event !is VideoRecordEvent.Finalize) return
        lifecycleScope.launch {
            findNavController().navigate(CameraVideoFragmentDirections.actionCameraFragmentToVideoViewer(event.outputResults.outputUri))
        }
    }

    companion object {
        const val DEFAULT_ASPECT_RATIO = AspectRatio.RATIO_16_9
        val TAG: String = CameraVideoFragment::class.java.simpleName
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
        private const val VIDEO_EXTENSION = ".mp4"
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