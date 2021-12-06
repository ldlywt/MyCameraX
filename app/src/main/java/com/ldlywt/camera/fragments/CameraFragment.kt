package com.ldlywt.camera.fragments

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import androidx.core.util.Consumer
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.ldlywt.camera.MainActivity
import com.ldlywt.camera.R
import com.ldlywt.camera.databinding.FragmentCameraBinding
import com.ldlywt.camera.fragments.photo.EXTENSION_WHITELIST
import com.ldlywt.camera.utils.ANIMATION_FAST_MILLIS
import com.ldlywt.camera.utils.ANIMATION_SLOW_MILLIS
import com.ldlywt.camera.widget.CircleProgressButtonView
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var outputDirectory: File
    private lateinit var videoCapture: VideoCapture<Recorder>
    private var activeRecording: ActiveRecording? = null
    private lateinit var recordingState: VideoRecordEvent
    private var isBack = true
    private var audioEnabled = false
    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

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
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment()
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            findNavController().navigate(CameraFragmentDirections.actionCameraToPermissions())
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
        cameraExecutor.shutdown()
    }

    private fun setGalleryThumbnail(uri: Uri) {
        fragmentCameraBinding.btnPhotoView.let { photoViewButton ->
            photoViewButton.post {
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
                Glide.with(photoViewButton)
                    .load(uri)
                    .apply(RequestOptions.circleCropTransform())
                    .into(photoViewButton)
            }
        }
    }

    private suspend fun bindCameraUseCases() {
        val cameraProvider: ProcessCameraProvider =
            ProcessCameraProvider.getInstance(requireContext()).await()
        val cameraSelector =
            if (isBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
            .setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
            .build()
            .apply { setSurfaceProvider(fragmentCameraBinding.previewView.surfaceProvider) }

        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.of(QualitySelector.QUALITY_SD))
            .build()

        videoCapture = VideoCapture.withOutput(recorder)

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .setTargetAspectRatio(DEFAULT_ASPECT_RATIO)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                viewLifecycleOwner,
                cameraSelector,
                videoCapture,
                imageCapture,
                preview
            )
        } catch (exc: Exception) {
            // we are on main thread, let's reset the controls on the UI.
            Log.e(TAG, "Use case binding failed", exc)
            resetUIandState("bindToLifecycle failed: $exc")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val outFile = MainActivity.createFile(outputDirectory, FILENAME, VIDEO_EXTENSION)
        Log.i(TAG, "outFile: $outFile")
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

    private fun takePicture() {
        imageCapture?.let { imageCapture ->
            val photoFile = MainActivity.createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
            val metadata = ImageCapture.Metadata().apply {
                isReversedHorizontal = isBack
            }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile)
                .setMetadata(metadata)
                .build()

            imageCapture.takePicture(
                outputOptions, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri: Uri = output.savedUri ?: Uri.fromFile(photoFile)
                        Log.d(TAG, "Photo capture succeeded: $savedUri")

                        // We can only change the foreground Drawable using API level 23+ API
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            // Update the gallery thumbnail with latest picture taken
                            setGalleryThumbnail(savedUri)
                        }

                        // Implicit broadcasts will be ignored for devices running API level >= 24
                        // so if you only target API level 24+ you can remove this statement
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            requireActivity().sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }

                        // If the folder selected is an external media directory, this is
                        // unnecessary but otherwise other apps will not be able to access our
                        // images unless we scan them using [MediaScannerConnection]
                        val mimeType =
                            MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(savedUri.toFile().extension)
                        MediaScannerConnection.scanFile(
                            context,
                            arrayOf(savedUri.toFile().absolutePath),
                            arrayOf(mimeType)
                        ) { _, uri ->
                            Log.d(TAG, "Image capture scanned into media store: $uri")
                        }
                    }
                })

            // We can only change the foreground Drawable using API level 23+ API
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Display flash animation to indicate that photo was captured
                fragmentCameraBinding.root.postDelayed({
                    fragmentCameraBinding.root.foreground = ColorDrawable(Color.WHITE)
                    fragmentCameraBinding.root.postDelayed(
                        { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS
                    )
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    private fun initCameraFragment() {
        outputDirectory = MainActivity.getOutputDirectory(requireContext())
        cameraExecutor = Executors.newSingleThreadExecutor()
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

    private fun changeFlashMode() {
        when (imageCapture?.flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                fragmentCameraBinding.ivTorch.setImageResource(R.mipmap.icon_flash_always_on)
            }
            ImageCapture.FLASH_MODE_ON -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                fragmentCameraBinding.ivTorch.setImageResource(R.mipmap.icon_flash_always_off)
            }
            ImageCapture.FLASH_MODE_OFF -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                fragmentCameraBinding.ivTorch.setImageResource(R.mipmap.icon_flash_auto)
            }
            else -> Unit
        }
    }

    @SuppressLint("ClickableViewAccessibility", "MissingPermission")
    private fun initializeUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.uppercase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        fragmentCameraBinding.btnSwitchCamera.setOnClickListener {
            switchCamera()
        }

        fragmentCameraBinding.btnPhotoView.setOnClickListener {
            findNavController().navigate(
                CameraFragmentDirections.actionCameraToGallery(
                    outputDirectory.absolutePath
                )
            )
        }

        fragmentCameraBinding.audioSelection.isChecked = audioEnabled
        fragmentCameraBinding.audioSelection.setOnClickListener {
            audioEnabled = fragmentCameraBinding.audioSelection.isChecked
        }

        fragmentCameraBinding.btnRecord.setOnLongClickListener(object :
            CircleProgressButtonView.OnLongClickListener {
            override fun onLongClick() {
                if (!this@CameraFragment::recordingState.isInitialized || recordingState is VideoRecordEvent.Finalize) {
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

        fragmentCameraBinding.btnRecord.setOnClickListener(CircleProgressButtonView.OnClickListener {
            takePicture()
        })

        fragmentCameraBinding.ivCamera.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigateUp()
        }

        fragmentCameraBinding.ivTorch.setOnClickListener {
            changeFlashMode()
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
                    it.btnSwitchCamera.visibility = View.VISIBLE
                    it.audioSelection.visibility = View.VISIBLE
                }
                UiState.RECORDING -> {
                    it.btnSwitchCamera.visibility = View.INVISIBLE
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
            findNavController().navigate(
                CameraFragmentDirections.actionCameraFragmentToVideoViewer(
                    event.outputResults.outputUri
                )
            )
        }
    }

    companion object {
        const val DEFAULT_ASPECT_RATIO = AspectRatio.RATIO_16_9
        val TAG: String = CameraFragment::class.java.simpleName
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val PHOTO_EXTENSION = ".jpg"
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