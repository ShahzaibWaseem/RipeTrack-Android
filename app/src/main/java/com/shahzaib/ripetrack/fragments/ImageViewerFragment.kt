package com.shahzaib.ripetrack.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.android.camera.utils.GenericListAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.shahzaib.ripetrack.MainActivity
import com.shahzaib.ripetrack.MainActivity.Companion.generateAlertBox
import com.shahzaib.ripetrack.R
import com.shahzaib.ripetrack.Utils
import com.shahzaib.ripetrack.Utils.cropImage
import com.shahzaib.ripetrack.Utils.imageFormat
import com.shahzaib.ripetrack.addCSVLog
import com.shahzaib.ripetrack.databinding.FragmentImageviewerBinding
import com.shahzaib.ripetrack.makeFolderInRoot
import com.shahzaib.ripetrack.saveProcessedImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File

class ImageViewerFragment: Fragment() {
	private val correctionMatrix = Matrix().apply { postRotate(90F); }

	/** AndroidX navigation arguments */
	private val args: ImageViewerFragmentArgs by navArgs()

	/** Host's navigation controller */
	private val navController: NavController by lazy {
		Navigation.findNavController(requireActivity(), R.id.fragment_container)
	}
	private lateinit var sharedPreferences: SharedPreferences
	private var _fragmentImageViewerBinding: FragmentImageviewerBinding? = null
	private val fragmentImageViewerBinding get() = _fragmentImageViewerBinding!!
	/** Default Bitmap decoding options */
	private val bitmapOptions = BitmapFactory.Options().apply {
		inJustDecodeBounds = false
		inPreferredConfig = Bitmap.Config.ARGB_8888
	}

	/** Data backing our Bitmap viewpager */
	private val bitmapList: MutableList<Bitmap> = mutableListOf()
	private var bitmapsWidth = Utils.torchWidth
	private var bitmapsHeight = Utils.torchHeight

	private var topCrop = -1F
	private var bottomCrop = -1F
	private var leftCrop = -1F
	private var rightCrop = -1F
	private val loadingDialogFragment = LoadingDialogFragment()

	private var advancedControlOption: Boolean = false

	// to hold 64x64 bounding boxes to be drawn for detected objects
	data class Box(
		val left: Float,
		val top: Float,
		val right: Float,
		val bottom: Float,
	)

	private val detectedBoxes by lazy { mutableListOf<Box>() }

	private val defaultPaint by lazy {
		Paint().apply {
			color = Color.argb(255, 253,250,114)
			strokeWidth = 5F
			style = Paint.Style.STROKE
		}
	}

	private val highlightPaint by lazy {
		Paint(defaultPaint).apply {
			color = Color.argb(255, 126,255,0)
		}
	}

	/*
	// used to decide for white balancing
	private val offlineMode by lazy {
		sharedPreferences.getBoolean("offline_mode", true)
	}
	*/

	private fun imageViewFactory() = ImageView(requireContext()).apply {
		layoutParams = ViewGroup.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT,
			ViewGroup.LayoutParams.MATCH_PARENT
		)
	}

	private fun boundingBox(left: Float, right: Float, top: Float, bottom: Float,
							canvas: Canvas, view: ImageView, bitmapOverlay: Bitmap, position: Int) {
		Log.i("Crop Location", "L: $left, R: $right, T: $top, B: $bottom")

		canvas.drawRect(left-2.5F, top-2.5F, right+2.5F, bottom+2.5F, highlightPaint)

		view.setImageBitmap(bitmapOverlay)

		if (bitmapOverlay.width > Utils.boundingBoxWidth*2 && bitmapOverlay.height > Utils.boundingBoxHeight*2 && position == 0) {
			MainActivity.tempRGBBitmap = bitmapOverlay
			MainActivity.tempRectangle = Rect(bottom.toInt(), left.toInt(), right.toInt(), top.toInt())
		}
	}

	private fun drawBox(box: Box, paint: Paint, canvas: Canvas, view: ImageView, bitmapOverlay: Bitmap)
	{
		canvas.drawRect(box.left-2.5F, box.top-2.5F, box.right+2.5F, box.bottom+2.5F, paint)

		view.setImageBitmap(bitmapOverlay)

		if (bitmapOverlay.width > Utils.boundingBoxWidth*2 && bitmapOverlay.height > Utils.boundingBoxHeight*2) {
			MainActivity.tempRGBBitmap = bitmapOverlay
			MainActivity.tempRectangle = Rect(box.bottom.toInt(), box.left.toInt(), box.right.toInt(), box.top.toInt())
		}
	}

	private fun startViewPager(rgbImageBitmap: Bitmap, nirImageBitmap: Bitmap)
	{
		val viewpagerThread = Thread {
			addItemToViewPager(fragmentImageViewerBinding.viewpager, rgbImageBitmap, 0)
			addItemToViewPager(fragmentImageViewerBinding.viewpager, nirImageBitmap, 1)
		}

		viewpagerThread.start()
		try { viewpagerThread.join() }
		catch (exception: InterruptedException) { exception.printStackTrace() }
	}

	private fun pointWithinBox(point: Pair<Int, Int>, box: Box): Boolean
	{
		val pointFloat = Pair(point.first.toFloat(), point.second.toFloat())
		return pointFloat.first in box.left ..box.right && pointFloat.second in box.top .. box.bottom
	}

	@SuppressLint("ClickableViewAccessibility")
	@Suppress("KotlinConstantConditions")
	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {

		sharedPreferences = requireActivity().getSharedPreferences("ripetrack_preferences", Context.MODE_PRIVATE)
		advancedControlOption = when (sharedPreferences.getString("option", getString(R.string.advanced_option_string))!!) {
			getString(R.string.advanced_option_string) -> true
			getString(R.string.simple_option_string) -> false
			else -> true
		}
		LoadingDialogFragment.text = getString(R.string.normalizing_image_string)
		loadingDialogFragment.isCancelable = false
		makeFolderInRoot(Utils.appRootPath, requireContext())
		if(MainActivity.dataCapturing)
			addCSVLog(requireContext())

		var firstTap = false

		_fragmentImageViewerBinding = FragmentImageviewerBinding.inflate(inflater, container, false)

		fragmentImageViewerBinding.viewpager.apply {
			offscreenPageLimit=2
			adapter = GenericListAdapter(bitmapList,
				itemViewFactory = { imageViewFactory() }) { view, item, position ->
				view as ImageView
				view.scaleType = ImageView.ScaleType.FIT_XY
				val bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
				val canvas = Canvas(bitmapOverlay)

				canvas.drawBitmap(item, Matrix(), null)

				when (position)
				{
					1 -> MainActivity.tempNIRBitmap = bitmapOverlay
					0 ->
					{
						MainActivity.tempRGBBitmap = bitmapOverlay

						Log.i("Crop Coordinates (ViewPager)", "($leftCrop,$topCrop), ($rightCrop,$bottomCrop)")

						// draws rectangles based on the result of object detection
						// note: needs to run after a 100-millisecond delay otherwise the bounding box will not be drawn
						Handler(Looper.getMainLooper()).postDelayed({
							detectedBoxes.forEach {
								Log.i("Box", "$it")
								drawBox(it, defaultPaint, canvas, view, bitmapOverlay) }
						}, 100)

						view.setOnTouchListener { v, event ->
							canvas.drawBitmap(item, Matrix(), null)
							detectedBoxes.forEach { drawBox(it, defaultPaint, canvas, view, bitmapOverlay) }

							var clickedX = ((event!!.x / v!!.width) * bitmapsWidth).toInt()
							var clickedY = ((event.y / v.height) * bitmapsHeight).toInt()

							// Make sure the bounding box doesn't go outside the bounds of the image
							if (clickedX + Utils.boundingBoxWidth > item.width)
								clickedX = (item.width - Utils.boundingBoxWidth).toInt()
							if (clickedY + Utils.boundingBoxHeight > item.height)
								clickedY = (item.height - Utils.boundingBoxHeight).toInt()

							if (clickedX - Utils.boundingBoxWidth < 0)
								clickedX = (0 + Utils.boundingBoxWidth).toInt()
							if (clickedY - Utils.boundingBoxHeight < 0)
								clickedY = (0 + Utils.boundingBoxHeight).toInt()

							var boxSelected = false

							detectedBoxes.forEach {
								// TODO need to account for overlapping boxes?
								if (pointWithinBox(Pair(clickedX, clickedY), it))
								{
									Log.i("Tapped Within a Box", "Drawing Green Box: $it")
									drawBox(it, highlightPaint, canvas, view, bitmapOverlay)

									// set crop coordinates for reconstruction
									leftCrop = it.left
									topCrop = it.top
									rightCrop = it.right
									bottomCrop = it.bottom

									boxSelected = true
								}
							}

							if (!boxSelected)
							{
								Log.i("Box Added", "X: $clickedX ($bitmapsWidth), Y: $clickedY ($bitmapsHeight)")

								if (!firstTap) {
									leftCrop = item.width/2 - Utils.boundingBoxWidth
									topCrop = item.height/2 - Utils.boundingBoxHeight
									rightCrop = item.width/2 + Utils.boundingBoxWidth
									bottomCrop = item.height/2 + Utils.boundingBoxHeight
									firstTap = true
								}
								else {
									leftCrop = clickedX - Utils.boundingBoxWidth
									topCrop = clickedY - Utils.boundingBoxHeight
									rightCrop = clickedX + Utils.boundingBoxWidth
									bottomCrop = clickedY + Utils.boundingBoxHeight
								}

								boundingBox(leftCrop, rightCrop, topCrop, bottomCrop, canvas, view, bitmapOverlay, position)
							}

							false
						}
					}
				}

				Glide.with(view).load(item).into(view)
			}
		}
		TabLayoutMediator(fragmentImageViewerBinding.tabLayout,
			fragmentImageViewerBinding.viewpager) { tab, position ->
			tab.text = if (position%2==0) "RGB" else "NIR"
		}.attach()
		return fragmentImageViewerBinding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		fragmentImageViewerBinding.Title.setOnClickListener {
			lifecycleScope.launch(Dispatchers.Main) {
				navController.navigate(
					ImageViewerFragmentDirections
						.actionImageViewerFragmentToApplicationTitle()
				)
			}
		}

		fragmentImageViewerBinding.reloadButton.setOnClickListener {
			lifecycleScope.launch(Dispatchers.Main) {
				navController.navigate(
					ImageViewerFragmentDirections
						.actionImageViewerFragmentToCameraFragment(
							MainActivity.cameraIDList.first, imageFormat)
				)
			}
		}

		loadingDialogFragment.show(childFragmentManager, LoadingDialogFragment.TAG)
	}

	override fun onStart() {
		super.onStart()

		lifecycleScope.launch(Dispatchers.IO) {
			// Load input image file
			val (bufferRGB, bufferNIR) = loadInputBuffer()

			// Load the main JPEG image
			var rgbImageBitmap = decodeBitmap(bufferRGB, bufferRGB.size, true)
			var nirImageBitmap = decodeBitmap(bufferNIR, bufferNIR.size, false)

			if (rgbImageBitmap.width > nirImageBitmap.width && rgbImageBitmap.height > nirImageBitmap.height)
				rgbImageBitmap = Utils.fixedAlignment(rgbImageBitmap)

			saveMinMax(rgbImageBitmap, isRGB=true)
			saveMinMax(nirImageBitmap, isRGB=false)
			Log.i("MinMax values", "${MainActivity.minMaxRGB}, ${MainActivity.minMaxNIR}")
			Log.i("Bitmap Size", "Decoded RGB: ${rgbImageBitmap.width} x ${rgbImageBitmap.height}, Decoded NIR: ${nirImageBitmap.width} x ${nirImageBitmap.height}")

			bitmapsWidth = rgbImageBitmap.width
			bitmapsHeight = rgbImageBitmap.height

			/* Object Detection */
			// Important to run this code in the I/O thread so that the main thread isn't slowed down

			val detectorOptions = ObjectDetectorOptions.Builder()
				.setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
				.enableMultipleObjects()
				.build()

			val detector = ObjectDetection.getClient(detectorOptions)

			val inputRGBImg = InputImage.fromBitmap(rgbImageBitmap, 0)

			// only perform detection for the RGB image as the coordinates will apply to the NIR as well
			// + the NIR will be cropped on the same coordinates for reconstruction/classification
			detector.process(inputRGBImg)
				.addOnSuccessListener { detections ->
					Log.i("Detected Objects!", "${detections.size}, $detections")

					if (detections.size > 0)
					{
						detections.forEach {
							val currRect = it.boundingBox

							// Find coordinates of the 64x64 bounding box in the middle of the detected box
							val width = (currRect.right - currRect.left).toFloat()
							val height = (currRect.bottom - currRect.top).toFloat() // note it's bottom - top because of how the coordinates are designed
							val left = (currRect.left + (width/2) - Utils.boundingBoxWidth)
							val top = currRect.top + (height/2) - Utils.boundingBoxHeight
							val right = currRect.left + (width/2) + Utils.boundingBoxWidth
							val bottom = currRect.top + (height/2) + Utils.boundingBoxHeight

							val middleBox = Box(left, top, right, bottom)
							detectedBoxes.add(middleBox)
						}

					}
					else // if the object cannot be detected, select the 64x64 region in the middle
					{
						Toast.makeText(requireContext(), "No Objects Detected", LENGTH_LONG).show()

						val left = rgbImageBitmap.width/2 - Utils.boundingBoxWidth
						val top = rgbImageBitmap.height/2 - Utils.boundingBoxHeight
						val right = rgbImageBitmap.width/2 + Utils.boundingBoxWidth
						val bottom = rgbImageBitmap.height/2 + Utils.boundingBoxHeight

						val middleBox = Box(left, top, right, bottom)
						detectedBoxes.add(middleBox)
					}


					startViewPager(rgbImageBitmap, nirImageBitmap)
				}
				.addOnFailureListener {e ->
					Log.i("Detection Failed", e.message.toString())
					Toast.makeText(requireContext(), "Object Detection Failed", LENGTH_LONG).show()

					val left = rgbImageBitmap.width/2 - Utils.boundingBoxWidth
					val top = rgbImageBitmap.height/2 - Utils.boundingBoxHeight
					val right = rgbImageBitmap.width/2 + Utils.boundingBoxWidth
					val bottom = rgbImageBitmap.height/2 + Utils.boundingBoxHeight

					val middleBox = Box(left, top, right, bottom)
					detectedBoxes.add(middleBox)

					startViewPager(rgbImageBitmap, nirImageBitmap)
				}

			loadingDialogFragment.dismissDialog()

			val rgbImage = File(args.filePath)
			val directoryPath = rgbImage.absolutePath.split(System.getProperty("file.separator")!!)
			val rgbImageFileName = directoryPath[directoryPath.size-1]
			val nirImage = File(args.filePath2)
			val nirDirectoryPath = nirImage.absolutePath.split(System.getProperty("file.separator")!!)
			val nirImageFileName = nirDirectoryPath[nirDirectoryPath.size-1]
			saveProcessedImages(requireContext(), rgbImageBitmap, nirImageBitmap, rgbImageFileName, nirImageFileName, Utils.processedImageDirectory)

			fragmentImageViewerBinding.button.setOnClickListener {
				val cropTime = System.currentTimeMillis()
				// if crop isn't initialized for simple mode
				Log.i("Cropped Image (before change)", "$leftCrop $topCrop")
				if (leftCrop == -1F && topCrop == -1F && !advancedControlOption) {
					leftCrop = rgbImageBitmap.width/2 - Utils.boundingBoxWidth
					topCrop = rgbImageBitmap.height/2 - Utils.boundingBoxWidth
				}
				Log.i("Cropped Image", "$leftCrop $topCrop")
				// if the app is asked to crop the image
				if (leftCrop != -1F && topCrop != -1F) {
					Log.i("Cropped Image", "Asked to crop")
					rgbImageBitmap = cropImage(rgbImageBitmap, leftCrop, topCrop)
					nirImageBitmap = cropImage(nirImageBitmap, leftCrop, topCrop)
					Log.i("Cropped Image", "${rgbImageBitmap.width} ${rgbImageBitmap.height}")
					Log.i("Cropped Image", "${nirImageBitmap.width} ${nirImageBitmap.height}")
					Log.i("Bitmap dimensions", "(${rgbImageBitmap.width},${rgbImageBitmap.height})")
					saveProcessedImages(requireContext(), rgbImageBitmap, nirImageBitmap, rgbImageFileName, nirImageFileName, Utils.croppedImageDirectory)

					MainActivity.executionTime += System.currentTimeMillis() - cropTime

					MainActivity.originalRGBBitmap = rgbImageBitmap
					MainActivity.originalNIRBitmap = nirImageBitmap

					lifecycleScope.launch(Dispatchers.Main) {
						navController.navigate(ImageViewerFragmentDirections.actionImageViewerFragmentToReconstructionFragment())
					}
				}
				else
				{
					// only navigate to the next fragment if the user has selected a bounded region
					generateAlertBox(requireContext(), "", "Please select a bounded region to reconstruct", {})
				}

			}
		}
	}

	/** Utility function used to read input file into a byte array */
	private fun loadInputBuffer(): Pair<ByteArray, ByteArray> {
		val rgbFile = File(args.filePath)
		val nirFile = File(args.filePath2)
		val rgbImage = BufferedInputStream(rgbFile.inputStream()).let { stream ->
			ByteArray(stream.available()).also {
				stream.read(it)
				stream.close()
			}
		}
		val nirImage = BufferedInputStream(nirFile.inputStream()).let { stream ->
			ByteArray(stream.available()).also {
				stream.read(it)
				stream.close()
			}
		}
		return Pair(rgbImage, nirImage)
	}

	/** Utility function used to add an item to the viewpager and notify it, in the main thread */
	private fun addItemToViewPager(view: ViewPager2, item: Bitmap, position: Int) = view.post {
		bitmapList.add(item)
		view.adapter!!.notifyItemChanged(position)
	}

	private fun saveMinMax(bitmap: Bitmap, isRGB: Boolean) {
		val width = bitmap.width
		val height = bitmap.height
		val intArrayPixels = IntArray(width * height)
		val intArrayValues = IntArray(width * height * 3)
		bitmap.getPixels(intArrayPixels, 0, width, 0, 0, width, height)
		for (idx in intArrayPixels.indices) {
			val color = intArrayPixels[idx]
			intArrayValues[3 * idx] = Color.red(color)
			intArrayValues[3 * idx + 1] = Color.green(color)
			intArrayValues[3 * idx + 2] = Color.blue(color)
		}
		val min = intArrayValues.min()
		val max = intArrayValues.max()

		if (isRGB)
			MainActivity.minMaxRGB = Pair(min, max)
		else
			MainActivity.minMaxNIR = Pair(min, max)
		Log.i("MinMax", "isRGB: $isRGB\tMin: $min\tMax: $max")
	}

	/** Utility function used to decode a [Bitmap] from a byte array */
	private fun decodeBitmap(buffer: ByteArray, length: Int, isRGB: Boolean): Bitmap {
		var bitmap: Bitmap

		// Load bitmap from given buffer
		val decodedBitmap = BitmapFactory.decodeByteArray(buffer, 0, length, bitmapOptions)
		if (isRGB) RGB_DIMENSION = Pair(decodedBitmap.width, decodedBitmap.height)

		if (isRGB){
			bitmap = Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, null, false)

			/*
			// Perform white balancing on the RGB image if (1) online mode or (2) offline mode and the selected RGB does not have white balancing
			Log.i("WB Conditions (online mode, offline image with -D)", "$offlineMode, ${MainActivity.rgbAbsolutePath}")
			if (
				!offlineMode || ( offlineMode && !MainActivity.rgbAbsolutePath.contains("-D") )
				)
			{
				Log.i("WB", "Process Began")
				val whiteBalancingModel = WhiteBalance(requireContext())
				val whiteBalancingThread = Thread {
					bitmap = whiteBalancingModel.whiteBalance(bitmap)
				}
				whiteBalancingThread.start()
				try { whiteBalancingThread.join() }
				catch (exception: InterruptedException) { exception.printStackTrace() }
				Log.i("WB", "Process Completed")
			}
			 */
		}
		else {
			bitmap = if (decodedBitmap.width > decodedBitmap.height)
				Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, correctionMatrix, false)
			else
				Bitmap.createBitmap(decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, null, false)
			if (MainActivity.cameraIDList.second == "OnePlus")
				bitmap = Bitmap.createScaledBitmap(bitmap, RGB_DIMENSION.first, RGB_DIMENSION.second, true)
		}
		// Transform bitmap orientation using provided metadata
		return bitmap
	}

	companion object {
		private lateinit var RGB_DIMENSION: Pair<Int, Int>
	}
}