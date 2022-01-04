package com.ldlywt.camera

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.ImageView
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ldlywt.camera.fragments.CameraFragment
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.default
import id.zelory.compressor.constraint.destination
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

interface CameraViewComponentListener {

    fun saveImageFile(file: File)
    fun startLoadingView()
    fun stopLoadingView()
}

enum class CameraFace {
    BACK, FRONT
}

class CameraViewComponent(
    var context: FragmentActivity,
    var cameraFace: CameraFace,
    var cameraPreviewView: PreviewView,
    var listener: CameraViewComponentListener
) : DefaultLifecycleObserver {

    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService? = null

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        init()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        cameraExecutor?.shutdown()
    }

    private fun init() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        context.lifecycleScope.launch {
            bindCameraUseCases()
        }
    }


    private suspend fun bindCameraUseCases() {
        val cameraProvider: ProcessCameraProvider =
            ProcessCameraProvider.getInstance(context).await()
        val cameraSelector =
            if (cameraFace == CameraFace.BACK) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

        val preview = Preview.Builder()
            .setTargetAspectRatio(CameraFragment.DEFAULT_ASPECT_RATIO)
            .build()
            .apply { setSurfaceProvider(cameraPreviewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .setTargetAspectRatio(CameraFragment.DEFAULT_ASPECT_RATIO)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                context,
                cameraSelector,
                imageCapture,
                preview
            )
        } catch (exc: Exception) {
            Log.e(CameraFragment.TAG, "Use case binding failed", exc)
        }
    }

    fun changeFlashMode(flashImageView: ImageView) {
        when (imageCapture?.flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_ON
                flashImageView.setImageResource(R.mipmap.icon_flash_always_on)
            }
            ImageCapture.FLASH_MODE_ON -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_OFF
                flashImageView.setImageResource(R.mipmap.icon_flash_always_off)
            }
            ImageCapture.FLASH_MODE_OFF -> {
                imageCapture?.flashMode = ImageCapture.FLASH_MODE_AUTO
                flashImageView.setImageResource(R.mipmap.icon_flash_auto)
            }
            else -> Unit
        }

    }

    fun switchCamera() {
        cameraFace = (if (cameraFace == CameraFace.BACK) CameraFace.FRONT else CameraFace.BACK)
        context.lifecycleScope.launch {
            bindCameraUseCases()
        }
    }

    fun takePicture() {
        listener.startLoadingView()
        val originalFile = FileManager.originalPhotoFile
        imageCapture?.let { imageCapture ->
            val metadata = ImageCapture.Metadata().apply { isReversedHorizontal = false }
            val outputOptions = ImageCapture.OutputFileOptions.Builder(originalFile)
                .setMetadata(metadata)
                .build()
            val executor = cameraExecutor ?: return
            imageCapture.takePicture(outputOptions, executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        listener.stopLoadingView()
                        exc.printStackTrace()
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedUri: Uri = output.savedUri
                            ?: Uri.fromFile(originalFile)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            context.sendBroadcast(
                                Intent(android.hardware.Camera.ACTION_NEW_PICTURE, savedUri)
                            )
                        }
                        handleImage(savedUri.path)
                    }
                })
        }
    }

    private fun handleImage(path: String?) = context.lifecycleScope.launch {
        if (path == null) return@launch
        val file = File(path)
        if (!file.isFile || !file.exists()) return@launch
        val compressedImageFile: File = Compressor.compress(context, file) {
            default()
            destination(FileManager.compressPhotoFile)
        }
        val outFile: File = if (compressedImageFile.isFile && compressedImageFile.exists()) {
            file.delete()
            compressedImageFile
        } else {
            file
        }
        listener.stopLoadingView()
        listener.saveImageFile(outFile)
    }
}