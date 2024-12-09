package com.lizongying.mytv0

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import com.bumptech.glide.Glide
import com.lizongying.mytv0.databinding.ModalBinding


class ModalFragment : DialogFragment() {

    private var _binding: ModalBinding? = null
    private val binding get() = _binding!!

    private val handler = Handler(Looper.myLooper()!!)
    private val delayHideAppreciateModal = 10000L

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ModalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val url = arguments?.getString(KEY_URL)
        if (!url.isNullOrEmpty()) {
            val size = Utils.dpToPx(200)
            val img = QrCodeUtil().createQRCodeBitmap(url, size, size)

            Glide.with(requireContext())
                .load(img)
                .into(binding.modalImage)
            binding.modalText.text = url.removePrefix("http://")
            binding.modalText.visibility = View.VISIBLE
            binding.modal.setOnClickListener {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            Glide.with(requireContext())
                .load(arguments?.getInt(KEY_DRAWABLE_ID))
                .into(binding.modalImage)
            binding.modalText.visibility = View.GONE
        }

        handler.postDelayed(hideAppreciateModal, delayHideAppreciateModal)
    }

    private val hideAppreciateModal = Runnable {
        if (!this.isHidden) {
            this.dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val KEY_DRAWABLE_ID = "drawable_id"
        const val KEY_URL = "url"
        const val TAG = "ModalFragment"
    }
}