package com.ldlywt.camera.fragments

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.ldlywt.camera.R

private var PERMISSIONS_REQUIRED = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO)

class PermissionsFragment : Fragment() {

    private val activityResultLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
                var permissionGranted = true
                permissions.entries.forEach {
                    if (it.key in PERMISSIONS_REQUIRED && it.value == false)
                        permissionGranted = false
                }
                if (!permissionGranted) {
                    Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
                } else {
                    navigateToCamera()
                }
            }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val permissionList = PERMISSIONS_REQUIRED.toMutableList()
            permissionList.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            PERMISSIONS_REQUIRED = permissionList.toTypedArray()
        }

        if (hasPermissions(requireContext())) {
            navigateToCamera()
        } else {
            Log.e(PermissionsFragment::class.java.simpleName, "Re-requesting permissions ...")
            activityResultLauncher.launch(PERMISSIONS_REQUIRED)
        }
    }

    private fun navigateToCamera() {
        lifecycleScope.launchWhenStarted {
            Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    PermissionsFragmentDirections.actionPermissionsToCamera())
        }
    }

    companion object {
        fun hasPermissions(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
