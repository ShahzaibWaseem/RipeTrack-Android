package com.shahzaib.mobislp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shahzaib.mobislp.databinding.ActivityMainBinding

class MainActivity: AppCompatActivity() {
	private lateinit var activityMainBinding: ActivityMainBinding
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
		setContentView(activityMainBinding.root)
		makeDirectory(Utils.rawImageDirectory)
		makeDirectory(Utils.croppedImageDirectory)
		makeDirectory(Utils.processedImageDirectory)
		makeDirectory(Utils.hypercubeDirectory)
	}

	companion object {
		const val MOBISPECTRAL_APPLICATION = 0
		lateinit var fruitID: String
		lateinit var fruitName: String
		lateinit var originalRGBBitmap: Bitmap
		lateinit var originalNIRBitmap: Bitmap
		lateinit var originalImageRGB: String
		lateinit var originalImageNIR: String
		var processedImageRGB = ""
		var processedImageNIR = ""
		var croppedImageRGB: String = ""
		var croppedImageNIR: String = ""
		var minMaxRGB = Pair(1000, -1000)
		var minMaxNIR = Pair(1000, -1000)
		var actualLabel: String = ""
		var predictedLabel: String = ""
		var normalizationTime: String = " s"
		var reconstructionTime: String = " s"
		var classificationTime: String = " ms"
		lateinit var tempRGBBitmap: Bitmap
		lateinit var tempRectangle: Rect
		var cameraIDList: Pair<String, String> = Pair("", "")
		var dataCapturing = false
		var illuminationOption = "Halogen"


		fun generateAlertBox(context: Context, title: String, text: String, onPositiveButtonFunction: () -> Unit) {
			val alertDialogBuilder = MaterialAlertDialogBuilder(context, R.style.AlertDialogTheme)
			alertDialogBuilder.setMessage(text)
			alertDialogBuilder.setTitle(title)
			alertDialogBuilder.setCancelable(false)
			if (title == "Information")
				alertDialogBuilder.setPositiveButton("Okay") { dialog, _ -> dialog?.cancel() }
			else
				alertDialogBuilder.setPositiveButton("Reload") { _, _ -> onPositiveButtonFunction() }

			val alertDialog = alertDialogBuilder.create()
			alertDialog.show()
		}
	}
}