package com.shahzaib.ripetrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shahzaib.ripetrack.databinding.ActivityMainBinding

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
		const val RIPETRACK_APPLICATION = 0
		lateinit var fruitID: String
		lateinit var originalRGBBitmap: Bitmap
		lateinit var originalNIRBitmap: Bitmap
		lateinit var croppableRGBBitmap: Bitmap
		lateinit var croppableNIRBitmap: Bitmap
		lateinit var originalImageRGB: String
		lateinit var originalImageNIR: String
		var processedImageRGB = ""
		var processedImageNIR = ""
		var croppedImageRGB: String = ""
		var croppedImageNIR: String = ""
		var minMaxRGB = Pair(1000, -1000)
		var minMaxNIR = Pair(1000, -1000)
		var actualLabel: String = ""
		// var reconstructionTime: String = " s"
		lateinit var tempRGBBitmap: Bitmap
		lateinit var tempNIRBitmap: Bitmap
		var cameraIDList: Pair<String, String> = Pair("", "")
		var dataCapturing = false
		var illuminationOption = "Halogen"
		var executionTime = 0L

		lateinit var rgbAbsolutePath: String
		lateinit var nirAbsolutePath: String

		private val defaultPaint by lazy {
			Paint().apply {
				color = Color.argb(255, 253,250,114)
				strokeWidth = 5F
				style = Paint.Style.STROKE
			}
		}

		val dottedPaint by lazy {
			Paint(defaultPaint).apply {
				strokeWidth = 1F
				pathEffect = DashPathEffect(floatArrayOf(10F, 4F), 0F)
			}
		}

		val highlightPaint by lazy {
			Paint(defaultPaint).apply {
				color = Color.argb(255, 126,255,0)
			}
		}

		// for drawing text on the Bitmaps
		val textPaint by lazy {
			Paint(defaultPaint).apply {
				strokeWidth = 1F
				style = Paint.Style.FILL
				textSize = 20F
				typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
				isAntiAlias = true
			}
		}

		// to hold 64x64 bounding boxes to be drawn for detected objects
		data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float ) {
			// secondary constructor to use with Rectangles
			constructor(coordRect: Rect): this(coordRect.left.toFloat(), coordRect.top.toFloat(), coordRect.right.toFloat(), coordRect.bottom.toFloat())
		}
		val fruitBoxes by lazy { mutableListOf<Box>() }
		val centralBoxes by lazy { mutableListOf<Box>() }

		fun generateAlertBox(context: Context, title: String, text: String, onPositiveButtonFunction: () -> Unit) {
			val alertDialogBuilder = MaterialAlertDialogBuilder(context, R.style.AlertDialogTheme)
			alertDialogBuilder.setMessage(text)
			alertDialogBuilder.setTitle(title)
			alertDialogBuilder.setCancelable(false)
			if (title == "Information" || title.isEmpty())
				alertDialogBuilder.setPositiveButton("Okay") { dialog, _ -> dialog?.cancel() }
			else
				alertDialogBuilder.setPositiveButton("Reload") { _, _ -> onPositiveButtonFunction() }

			val alertDialog = alertDialogBuilder.create()
			alertDialog.show()
		}
	}
}