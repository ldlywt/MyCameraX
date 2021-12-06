package com.ldlywt.camera.fragments.photo

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
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
import androidx.camera.core.*
import androidx.camera.core.ImageCapture.Metadata
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.concurrent.futures.await
import androidx.core.net.toFile
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.ldlywt.camera.MainActivity
import com.ldlywt.camera.R
import com.ldlywt.camera.databinding.FragmentCameraBinding
import com.ldlywt.camera.fragments.PermissionsFragment
import com.ldlywt.camera.utils.ANIMATION_FAST_MILLIS
import com.ldlywt.camera.utils.ANIMATION_SLOW_MILLIS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TakePhotoFragment : Fragment() {

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private lateinit var outputDirectory: File

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var isBack = true

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?,
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }


    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initCameraFragment(view)
    }

    override fun onResume() {
        super.onResume()
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    TakePhotoFragmentDirections.actionCameraToPermissions()
            )
        }
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
    }

    private fun setGalleryThumbnail(uri: Uri) {
        fragmentCameraBinding.photoViewButton.let { photoViewButton ->
            photoViewButton.post {
                photoViewButton.setPadding(resources.getDimension(R.dimen.stroke_small).toInt())
                Glide.with(photoViewButton)
                        .load(uri)
                        .apply(RequestOptions.circleCropTransform())
                        .into(photoViewButton)
            }
        }
    }

    private fun initCameraFragment(view: View) {
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Determine the output directory
        outputDirectory = MainActivity.getOutputDirectory(requireContext())

        // Wait for the views to be properly laid out
        fragmentCameraBinding.cameraPreview.post {

            // Build UI controls
            initializeUI()

            // Set up the camera and its use cases
            lifecycleScope.launch {
                bindCameraUseCases()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    /** Declare and bind preview, capture and analysis use cases */
    private suspend fun bindCameraUseCases() {
        val cameraProvider: ProcessCameraProvider = ProcessCameraProvider.getInstance(requireContext()).await()
        val cameraSelector = if (isBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

        imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build()

        try {
            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture)
            preview.setSurfaceProvider(fragmentCameraBinding.cameraPreview.surfaceProvider)
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    private fun initializeUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            outputDirectory.listFiles { file ->
                EXTENSION_WHITELIST.contains(file.extension.uppercase(Locale.ROOT))
            }?.maxOrNull()?.let {
                setGalleryThumbnail(Uri.fromFile(it))
            }
        }

        fragmentCameraBinding.cameraCaptureButton.setOnClickListener {
            takePicture()
        }

        fragmentCameraBinding.cameraSwitchButton.setOnClickListener {
            switchCamera()
        }

        fragmentCameraBinding.photoViewButton.setOnClickListener {
            if (true == outputDirectory.listFiles()?.isNotEmpty()) {
                Navigation.findNavController(requireActivity(), R.id.fragment_container)
                        .navigate(TakePhotoFragmentDirections.actionCameraToGallery(outputDirectory.absolutePath))
            }
        }

        fragmentCameraBinding.ivTorch.setOnClickListener {
            changeFlashMode()
        }
        fragmentCameraBinding.ivCameraVideo.setOnClickListener {
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                    .navigate(TakePhotoFragmentDirections.actionCameraFragmentToCameraVideoFragment())
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

    private fun switchCamera() {
        isBack = !isBack
        lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    private fun takePicture() {
        imageCapture?.let { imageCapture ->
            val photoFile = MainActivity.createFile(outputDirectory, FILENAME, PHOTO_EXTENSION)
            val metadata = Metadata().apply {
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
                            MimeTypeMap.getSingleton().getMimeTypeFromExtension(savedUri.toFile().extension)
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
                            { fragmentCameraBinding.root.foreground = null }, ANIMATION_FAST_MILLIS)
                }, ANIMATION_SLOW_MILLIS)
            }
        }
    }

    companion object {

        private const val TAG = "MyCameraX"
        private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
        private const val PHOTO_EXTENSION = ".jpg"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0

    }
}
