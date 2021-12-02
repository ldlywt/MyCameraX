package com.ldlywt.camera.fragments.video

import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.MediaController
import androidx.navigation.fragment.navArgs
import com.ldlywt.camera.databinding.FragmentVideoViewerBinding

class VideoViewerFragment : androidx.fragment.app.Fragment() {
    private val args: VideoViewerFragmentArgs by navArgs()
    private var _binding: FragmentVideoViewerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentVideoViewerBinding.inflate(inflater, container, false)
        val tv = TypedValue()
        if (requireActivity().theme.resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            val actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data, resources.displayMetrics)
            binding.videoViewerTips.y  = binding.videoViewerTips.y - actionBarHeight
        }

        return binding.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showVideo()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun showVideo() {
        if (args.uri.scheme.toString().compareTo("content") == 0) {
            val resolver = requireContext().contentResolver
            resolver.query(args.uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                cursor.moveToFirst()

                val fileSize = cursor.getLong(sizeIndex)
                if (fileSize <= 0) {
                    Log.e("VideoViewerFragment", "Recorded file size is 0, could not be played!")
                    return
                }

                val dataIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val fileInfo =  "FileSize: $fileSize" + dataIndex.let { "\n ${cursor.getString(it)}" }

                Log.i("VideoViewerFragment", fileInfo)
                binding.videoViewerTips.text = fileInfo
            }

            val mc = MediaController(requireContext())
            binding.videoViewer.apply {
                setVideoURI(args.uri)
                setMediaController(mc)
                requestFocus()
            }.start()
            mc.show(0)
        }
    }
}