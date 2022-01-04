package com.ldlywt.camera

import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object FileManager {

    private const val FILENAME = "yyyy-MM-dd-HH-mm-ss"
    private const val PHOTO_EXTENSION = ".jpg"

    private fun getCameraOutputDirectory(): File {
        val mediaDir = App.instance.externalMediaDirs?.firstOrNull()?.let {
            File(it, "MyCameraX").apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else App.instance.filesDir
    }

    val originalPhotoFile
        get() = File(
            getCameraOutputDirectory(),
            SimpleDateFormat(FILENAME, Locale.ENGLISH)
                .format(System.currentTimeMillis()) + PHOTO_EXTENSION
        )

    val compressPhotoFile
        get() = File(
            getCameraOutputDirectory(),
            SimpleDateFormat(FILENAME, Locale.ENGLISH)
                .format(System.currentTimeMillis()) + "-comp" + PHOTO_EXTENSION
        )

}