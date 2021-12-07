package com.ldlywt.camera.fragments.video

import android.os.Bundle
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
        val mc = MediaController(requireContext())
        binding.videoViewer.apply {
            setVideoURI(args.uri)
            setMediaController(mc)
            requestFocus()
        }.start()
        mc.show(0)
    }
}