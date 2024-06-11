package com.shahzaib.ripetrack.fragments

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shahzaib.ripetrack.databinding.FragmentLoadingDialogBinding

class LoadingDialogFragment: DialogFragment() {
	private lateinit var alertDialog: AlertDialog
	private var _fragmentReconstructionDialogBinding: FragmentLoadingDialogBinding? = null
	private lateinit var progressBar: ProgressBar
	private lateinit var alertDialogBuilder: MaterialAlertDialogBuilder

	override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
		super.onCreateDialog(savedInstanceState)
		_fragmentReconstructionDialogBinding = FragmentLoadingDialogBinding.inflate(layoutInflater)
		progressBar = _fragmentReconstructionDialogBinding!!.progressBar
		alertDialogBuilder = MaterialAlertDialogBuilder(requireContext()).setCancelable(false)
		progressBar.visibility = View.VISIBLE
		progressBar.progress = 30
		_fragmentReconstructionDialogBinding!!.textView.text = text
		alertDialog = alertDialogBuilder.create()

		alertDialog.setView(_fragmentReconstructionDialogBinding!!.root)
		return alertDialog
	}

	fun dismissDialog() {
		alertDialog.dismiss()
	}

	companion object {
		const val TAG = "LoadingDialog"
		var text = ""
	}
}