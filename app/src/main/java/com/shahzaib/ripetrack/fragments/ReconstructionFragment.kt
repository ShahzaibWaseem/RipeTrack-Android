package com.shahzaib.ripetrack.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.example.android.camera.utils.GenericListAdapter
import com.google.android.material.button.MaterialButton
import com.google.android.material.tabs.TabLayoutMediator
import com.jjoe64.graphview.series.DataPoint
import com.jjoe64.graphview.series.LineGraphSeries
import com.shahzaib.ripetrack.*
import com.shahzaib.ripetrack.MainActivity.Companion.centralBoxes
import com.shahzaib.ripetrack.MainActivity.Companion.croppableNIRBitmap
import com.shahzaib.ripetrack.MainActivity.Companion.croppableRGBBitmap
import com.shahzaib.ripetrack.MainActivity.Companion.dottedPaint
import com.shahzaib.ripetrack.MainActivity.Companion.fruitBoxes
import com.shahzaib.ripetrack.MainActivity.Companion.generateAlertBox
import com.shahzaib.ripetrack.MainActivity.Companion.highlightPaint
import com.shahzaib.ripetrack.MainActivity.Companion.textPaint
import com.shahzaib.ripetrack.Utils.cropImage
import com.shahzaib.ripetrack.databinding.FragmentReconstructionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.properties.Delegates

class ReconstructionFragment: Fragment() {
	// private lateinit var predictedHS: FloatArray
	private lateinit var classificationPair: Pair<String, Int>
	private var bandsHS: MutableList<Bitmap> = mutableListOf()
	// private var reconstructionDuration = 0F
	private val numberOfBands = 68
	private val bandSpacing = 204 / numberOfBands
	private var clickedX = 0.0F
	private var clickedY = 0.0F
	private val bandsChosen = mutableListOf<Int>()
	private val loadingDialogFragment = LoadingDialogFragment()
	private val randomColor = Random()
	private var color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))

	/** Host's navigation controller */
	private val navController: NavController by lazy {
		Navigation.findNavController(requireActivity(), R.id.fragment_container)
	}

	private var _fragmentReconstructionBinding: FragmentReconstructionBinding? = null
	private val fragmentReconstructionBinding get() = _fragmentReconstructionBinding!!

	private lateinit var sharedPreferences: SharedPreferences
	private lateinit var ripeTrackApplication: String
	private lateinit var classificationFile: String
	private lateinit var reconstructionFile: String
	private var classificationFiles = arrayOf("RipeTrack_classification_mobile_pa_oneplus.pt", "RipeTrack_classification_mobile_bmn_oneplus.pt")
	private var reconstructionFiles = arrayOf("RipeTrack_reconstruction_mobile_pa_68.pt", "RipeTrack_reconstruction_mobile_bmn_68.pt")
	private lateinit var ripeTrackControlOption: String
	private var advancedControlOption by Delegates.notNull<Boolean>()

	private var bitmapsWidth = Utils.torchWidth
	private var bitmapsHeight = Utils.torchHeight

	// List of pairs of ripeness level and remaining lifetime (in percent) e.g. Ripe, 60%
	private val processingResults by lazy { mutableListOf<Pair<String, Int>>() }

	// All predicted hypercubes for the regions detected by the object detector
	private val hsCubes by lazy { mutableListOf<FloatArray>() }

	private var boxChosen = false

	private lateinit var chosenHS: FloatArray
	private lateinit var chosenFruitBox: Box
	private lateinit var chosenCentralBox: Box

	private fun imageViewFactory() = ImageView(requireContext()).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
	}

	// set up the progress bar, progress text, & ripeness buttons' constraint for classification
	private lateinit var progressBar: ProgressBar
	private lateinit var progressText: TextView

	// flag for analysis
	private var analyze = false

	// views for toggling graph visibility
	private lateinit var toggleVisibilityViews: Array<View>

	private val reconstructionDone = MutableLiveData(false)

	private suspend fun displayClassification()	{

		// delay the function to give a "growth" effect to the progress bar
		delay(30L)

		// lifetime classification (in percentages) to be used to grow progress bar
		progressBar.progress = classificationPair.second
		progressText.text = getString(R.string.remaining_lifetime_placeholder, progressBar.progress)

		val ripeness = classificationPair.first

		// change color of progressbar
		progressBar.progressTintList = (
			when (ripeness) {
				// unripe
				"Unripe" -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_green))
				// ripe
				"Ripe" -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_orange))
				// expired
				"Expired" -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
				else -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.sfu_black))
			})

		val unripeBtn = requireView().findViewById<MaterialButton>(R.id.unripeBtn)
		val ripeBtn = requireView().findViewById<MaterialButton>(R.id.ripeBtn)
		val expiredBtn = requireView().findViewById<MaterialButton>(R.id.expiredBtn)
		listOf(unripeBtn, ripeBtn, expiredBtn).forEach {
			it.setTextColor(ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_dim)))
			it.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.gray_bg))
			it.strokeWidth = 0
		}

		when (ripeness){
			"Unripe" -> {
				unripeBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_green))
				unripeBtn.setTextColor(Color.WHITE)
				unripeBtn.strokeWidth = 8
				unripeBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
			}
			"Ripe" -> {
				ripeBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_orange))
				ripeBtn.setTextColor(Color.WHITE)
				ripeBtn.strokeWidth = 8
				ripeBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
			}
			"Expired" -> {
				expiredBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
				expiredBtn.setTextColor(Color.WHITE)
				expiredBtn.strokeWidth = 8
				expiredBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
			}
			else -> {
				// don't color anything -- error
			}
		}
	}

	private fun performClassification(){

		lifecycleScope.launch(Dispatchers.Main){
			// (initialize or reset) progress value on both bar & progress text
			progressBar.progress = 0
			progressText.text = getString(R.string.remaining_lifetime_placeholder, progressBar.progress)

		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		super.onCreateView(inflater, container, savedInstanceState)
		_fragmentReconstructionBinding = FragmentReconstructionBinding.inflate(inflater, container, false)
		LoadingDialogFragment.text = getString(R.string.reconstructing_hypercube_string)
		loadingDialogFragment.isCancelable = false

		sharedPreferences = requireActivity().getSharedPreferences("ripetrack_preferences", Context.MODE_PRIVATE)
		ripeTrackApplication = sharedPreferences.getString("application", getString(R.string.apple_string))!!
		ripeTrackControlOption = sharedPreferences.getString("option", getString(R.string.advanced_option_string))!!

		Log.i("Offline Mode?", "${sharedPreferences.getBoolean("offline_mode", true)}")

		// set reconstruction & classification file options based on fruit
		val fruit = sharedPreferences.getString("fruit", null) // this option will never be null, as the user must select a fruit to proceed with analysis

		if (fruit.equals("Pear") || fruit.equals("Avocado")){
			classificationFile = classificationFiles[0]
			reconstructionFile = reconstructionFiles[0]
		}
		else{
			classificationFile = classificationFiles[1]
			reconstructionFile = reconstructionFiles[1]
		}
		Log.i("Classification + Reconstruction Files Chosen", "$classificationFile, $reconstructionFile")

		advancedControlOption = when (ripeTrackControlOption) {
			getString(R.string.advanced_option_string) -> true
			getString(R.string.simple_option_string) -> false
			else -> true
		}

		toggleVisibilityViews = arrayOf(
			fragmentReconstructionBinding.graphView,
			fragmentReconstructionBinding.classificationConstraint,
			fragmentReconstructionBinding.progressConstraint,
			fragmentReconstructionBinding.analyzeButton
		)

		return fragmentReconstructionBinding.root
	}

	@SuppressLint("ClickableViewAccessibility")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		fragmentReconstructionBinding.viewpager.apply {
			offscreenPageLimit=2
			adapter = GenericListAdapter(bandsHS, itemViewFactory = { imageViewFactory() }){ view, item, position ->

				view as ImageView
				view.scaleType = ImageView.ScaleType.FIT_XY
				bitmapsWidth = item.width
				bitmapsHeight = item.height
				var bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
				var canvas = Canvas(bitmapOverlay)
				canvas.drawBitmap(item, Matrix(), null)
				var itemTouched = false
				var savedClickedX = 0.0F
				var savedClickedY = 0.0F

				// default initialization (dummy values, will be changed in the handler below)
				var boxSelectionOverlay: Bitmap
				var boxSelectionCanvas: Canvas

				when (Pair(position, analyze))	{
					Pair(0, false) -> {

						// (ASSUMING at least one object has been detected and passed to this fragment)
						// initialize the (default) chosen box for it to be shown after the handler draws the dotted fruit boxes
						chosenHS = hsCubes[0]
						chosenFruitBox = fruitBoxes[0]
						chosenCentralBox = centralBoxes[0]
						classificationPair = processingResults[0]

						// show bounding boxes and the inference output
						// this task needs to be delayed or all the results won't be shown
						Handler(Looper.getMainLooper()).postDelayed({
							for (i in 0 until processingResults.size) {
								val currFruitBox = fruitBoxes[i]
								val currResult = processingResults[i]

								val text = "${currResult.first} ${currResult.second}% Left"
								val tBounds = Rect()
								textPaint.getTextBounds(text, 0, text.length, tBounds)
								val left =
									(if (tBounds.left < 0) currFruitBox.left - tBounds.left else currFruitBox.left + tBounds.left) - 2.5F
								val top =
									(if (tBounds.top < 0) currFruitBox.top - tBounds.top else currFruitBox.top + tBounds.top) - 2.5F

								canvas.drawText(text, left, top, textPaint)
								drawBoxOnView(currFruitBox, dottedPaint, canvas, view, bitmapOverlay)

							}

							boxSelectionOverlay = Bitmap.createBitmap(bitmapOverlay)
							boxSelectionCanvas = Canvas(boxSelectionOverlay)
							drawBoxOnView(chosenFruitBox, Paint(highlightPaint).apply {
								color = Color.argb(90, 137, 109, 235)
								style = Paint.Style.FILL
								// below is so that the filling is INSIDE the fruit box and doesn't overlap it
								strokeWidth = 1F
							}, boxSelectionCanvas, view, boxSelectionOverlay)
							drawBoxOnView(chosenCentralBox, highlightPaint, boxSelectionCanvas, view, boxSelectionOverlay)
							lifecycleScope.launch(Dispatchers.Main) {
								displayClassification()
							}

						}, 100)

						view.setOnTouchListener { v, event ->
							clickedX = (event!!.x / v!!.width) * item.width
							clickedY = (event.y / v.height) * item.height

							Log.i("Click event", "Analysis? $analyze, $clickedX, $clickedY")
							if (!analyze){

								// reset this image bitmap so that previous highlights are cleared & new ones can be drawn
								boxSelectionOverlay = Bitmap.createBitmap(bitmapOverlay)
								boxSelectionCanvas = Canvas(boxSelectionOverlay)

								// reset the image bitmap so that any previous purple highlights are not shown
								view.setImageBitmap(bitmapOverlay)

								for (i in fruitBoxes.indices){
									val currBox = fruitBoxes[i]
									Log.i("Click Pair & Box", "${Pair(clickedX.roundToInt(), clickedY.roundToInt())}, $currBox")
									if (pointWithinBox(Pair(clickedX.roundToInt(), clickedY.roundToInt()), currBox)) {
										boxChosen = true

										// initialize the variables for the chosen analysis region
										chosenHS = hsCubes[i]
										chosenFruitBox = fruitBoxes[i]
										chosenCentralBox = centralBoxes[i]

										drawBoxOnView(centralBoxes[i], highlightPaint, boxSelectionCanvas, view, boxSelectionOverlay)

										Log.i("Box Picked", "${getBand(chosenHS, 0).width} ${chosenHS.size}")

										classificationPair = processingResults[i]

										drawBoxOnView(fruitBoxes[i], Paint(highlightPaint).apply {
											color = Color.argb(90, 137, 109, 235)
											style = Paint.Style.FILL
											// below is so that the filling is INSIDE the fruit box and doesn't overlap it
											strokeWidth = 1F
										}, boxSelectionCanvas, view, boxSelectionOverlay)

										//fragmentReconstructionBinding.analyzeButton.visibility = View.VISIBLE

										lifecycleScope.launch (Dispatchers.Main){
											displayClassification()
										}

										// this prevents multiple boxes from being highlighted i.e. if (clickedX, clickedY) was within the bounds of multiple boxes
										break
									}
								}

								// apply the new bitmap, but we still have bitmapOverlay which we can use to clear the drawings here
								//view.setImageBitmap(boxSelectionOverlay)
							}
							false
						}
					}
				}

				if (analyze){
					view.setOnTouchListener { v, event ->
						if (!analyze) {
							// if not in analysis mode, the functionality below should be deactivated
							return@setOnTouchListener false
						}

						//var drawSignature = false

						Log.i("TouchListener", "Pressed")

						// need to scale clickedX and clickedY based on bitmap size (different for RGB vs other bands)
						clickedX = (event!!.x / v!!.width)
						clickedY = (event.y / v.height)

						// these values will be initialized to reflect the band's size, see below
						val drawnClickedX: Float
						val drawnClickedY: Float

						if (!itemTouched) {
							savedClickedX = clickedX
							savedClickedY = clickedY
							itemTouched = true

							// prepare color & paint for drawing points on the bitmap and signatures on the graphView
							Log.i("Pixel Clicked", "X: ${clickedX.roundToInt()} ($bitmapsWidth), Y: ${clickedY.roundToInt()} ($bitmapsHeight)")
							color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))

							val paint = Paint()
							paint.color = color
							paint.style = Paint.Style.STROKE
							paint.strokeWidth = 2.5F

							val isRGBTab = position == 0
							var drawPointAndSignature = true

							// RGB viewpager position
							if (isRGBTab) {
								val centerPoint = Pair(item.width/2, item.height/2)

								val paintWidth = MainActivity.defaultPaint.strokeWidth

								var left = centerPoint.first - Utils.boundingBoxWidth
								var top = centerPoint.second - Utils.boundingBoxHeight
								var right = left + patchWidth
								var bottom = top + patchHeight

								// add/subtract paintWidth so that the user can not tap on the green frame but rather only inside the box
								left += paintWidth
								top += paintWidth
								right -= paintWidth
								bottom -= paintWidth

								val relevantBox = Box(left, top, right, bottom)

								// drawing on a cropped part of the image
								drawnClickedX = clickedX*item.width
								drawnClickedY = clickedY*item.height

								// if the point isn't within the green bounding box in the center of the fruit, don't draw point or get signature
								if ( !pointWithinBox(Pair(drawnClickedX.toInt(), drawnClickedY.toInt()), relevantBox) ) drawPointAndSignature = false

							} else
							{
								// we're drawing on the whole image
								drawnClickedX = clickedX * bitmapsWidth
								drawnClickedY = clickedY * bitmapsHeight
							}

							if (drawPointAndSignature)
							{
								// draw the circle tapped by the user and run it through signature analysis
								canvas.drawCircle(drawnClickedX, drawnClickedY, 5F, paint)

								// note: why are drawnClickedX and drawnClickedY not used? because we want the index of the pixel wrt the entire IMAGE,
								// not just the cropped image of the fruit that is present in the RGB tab
								getSignature(chosenHS, (clickedY * bitmapsHeight).roundToInt(), (clickedX * bitmapsWidth).roundToInt())

								view.setImageBitmap(bitmapOverlay)
								MainActivity.actualLabel = ""
							}

						}

						if (itemTouched && savedClickedX != clickedX && savedClickedY != clickedY)
							itemTouched = false

						false
					}

					view.setOnLongClickListener {
						if (!analyze) {
							return@setOnLongClickListener false
						}

						Log.i("LongClickListener", "Pressed")

						bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
						canvas = Canvas(bitmapOverlay)
						canvas.drawBitmap(item, Matrix(), null)
						view.setImageBitmap(bitmapOverlay)
						fragmentReconstructionBinding.graphView.removeAllSeries()         // remove all previous series
						false
					}
				}

				Glide.with(view).load(item).into(view)
			}
		}

		val editor = sharedPreferences.edit()
		MainActivity.fruitID = sharedPreferences.getString("fruitID", "0").toString()
		MainActivity.fruitID = (MainActivity.fruitID.toInt() + 1).toString()
		editor.putString("fruitID", MainActivity.fruitID)
		editor.apply()

		fragmentReconstructionBinding.Title.setOnClickListener {
			lifecycleScope.launch(Dispatchers.Main) {
				navController.navigate(
					ReconstructionFragmentDirections.actionReconstructionFragmentToApplicationTitle()
				)
			}
		}

		loadingDialogFragment.show(childFragmentManager, LoadingDialogFragment.TAG)

		// initialize these variables for the classification step
		progressBar = requireView().findViewById(R.id.progressBar)
		progressText = requireView().findViewById(R.id.progressText)
	}

	private fun toggleGraphVisibility() {
		// safety check, should be initialized by then
		if (::toggleVisibilityViews.isInitialized) {
			toggleVisibilityViews.forEach {
				if (it.visibility == View.VISIBLE){

					it.visibility = View.INVISIBLE
				}
				else {
					it.visibility = View.VISIBLE
				}
			}
		}
	}

	private fun performInference(){

		centralBoxes.forEach {
			Log.i("PerformInference", "$it")
			val currPredictedHS = generateIndividualHypercube(it)
			val result = classifyIndividualFruit(currPredictedHS)
			val state = when (result.first) {
				0 -> "Unripe"
				1 -> "Ripe"
				else -> "Expired"
			}
			Log.i("Output for box", "Box: $it ===> $state, ${100 - result.second * 10}% Remaining Lifetime")
			processingResults.add(Pair(state, 100 - result.second * 10))
			hsCubes.add(currPredictedHS)
		}
		Log.i("Output for box", "All outputs done -- ${processingResults.size}, ${fruitBoxes.size}, ${centralBoxes.size}")
	}

	@SuppressLint("NotifyDataSetChanged")
	override fun onStart() {
		super.onStart()

		fragmentReconstructionBinding.information.setOnClickListener {
			generateAlertBox(requireContext(), "", getString(R.string.reconstruction_analysis_information_string)) {}
		}

		Timer().schedule(1000) {
			val inferenceThread = Thread {
				performInference()
			}
			inferenceThread.start()
			try { inferenceThread.join() }
			catch (exception: InterruptedException) { exception.printStackTrace() }

			val graphView = _fragmentReconstructionBinding!!.graphView
			graphView.gridLabelRenderer.horizontalAxisTitle = "Wavelength λ (nm)"
			graphView.gridLabelRenderer.verticalAxisTitle = "Reflectance"
			graphView.gridLabelRenderer.padding = 50
			graphView.gridLabelRenderer.textSize = 50F
			graphView.gridLabelRenderer.horizontalAxisTitleTextSize = 50F
			graphView.gridLabelRenderer.verticalAxisTitleTextSize = 50F
			graphView.viewport.isXAxisBoundsManual = true
			graphView.viewport.setMaxX(1000.0)
			graphView.viewport.setMinX(400.0)
			graphView.viewport.isYAxisBoundsManual = true
			graphView.viewport.setMaxY(1.2)
			graphView.gridLabelRenderer.setHumanRounding(true)


			fragmentReconstructionBinding.analyzeButton.setOnClickListener {
				analyze = !analyze

				// clear the viewpager first
				fragmentReconstructionBinding.viewpager.post {
					bandsHS.clear()
					fragmentReconstructionBinding.viewpager.adapter!!.notifyDataSetChanged()
				}

				// make the reconstructed bands & their wavelengths visible
				val selectedIndices = listOf(0, 12, 23, 34, 45, 56, 67)
				val viewpagerThread = Thread {
					if (::chosenHS.isInitialized && ::chosenFruitBox.isInitialized && ::chosenCentralBox.isInitialized) {
						val tempCroppableRGBBitmap = MainActivity.tempRGBBitmap.copy(Bitmap.Config.ARGB_8888, true)

						drawBox(chosenCentralBox, highlightPaint, Canvas(tempCroppableRGBBitmap))

						addItemToViewPager(fragmentReconstructionBinding.viewpager, cropImage(tempCroppableRGBBitmap, chosenFruitBox), 0)
						Log.i("Adding RGB Box", "Added")
						for (i in selectedIndices){
							bandsChosen.add(i)
							addItemToViewPager(fragmentReconstructionBinding.viewpager, getBand(chosenHS, i), selectedIndices.indexOf(i)+1)
						}

					}
				}

				viewpagerThread.start()
				try { viewpagerThread.join() }
				catch (exception: InterruptedException) { exception.printStackTrace() }

				// show the graph
				toggleGraphVisibility()
			}

			TabLayoutMediator(fragmentReconstructionBinding.tabLayout,
				fragmentReconstructionBinding.viewpager) { tab, position ->
				Log.i("Position", "$position")
				if (analyze){
					if (position != 0)
						tab.text = (round(ACTUAL_BAND_WAVELENGTHS[bandsChosen[position-1] * bandSpacing] / 100)
							* 100).toInt().toString()
					else
						tab.text = "RGB"
				}
				else {

					when (position) {
						0 -> tab.text = "RGB"
						1 -> tab.text = "NIR"
					}
				}
			}.attach()

			fragmentReconstructionBinding.reloadButton.setOnClickListener {
				if (analyze){

					// go back to classification page
					@Suppress("KotlinConstantConditions")
					analyze = !analyze
					// hide the graph
					toggleGraphVisibility()

					// clear the viewpager first
					fragmentReconstructionBinding.viewpager.post {
						bandsHS.clear()
						fragmentReconstructionBinding.viewpager.adapter!!.notifyDataSetChanged()
					}

					addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempRGBBitmap, 0)
					addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempNIRBitmap, 1)
				}
				else{
					// navigate back to the ImageViewerFragment

					lifecycleScope.launch(Dispatchers.Main) {
						navController.navigate(ReconstructionFragmentDirections
							.actionReconstructionFragmentToImageViewerFragment(
								MainActivity.rgbAbsolutePath, MainActivity.nirAbsolutePath)
						)
					}
				}
			}
			addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempRGBBitmap, 0)
			addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempNIRBitmap, 1)

			loadingDialogFragment.dismissDialog()
		}
	}

	override fun onResume(){

		super.onResume()
		// use an observer so that onResume() doesn't run before the scheduled task in onStart() is complete (then predictedHS may be uninitialized)
		reconstructionDone.observe(this) { initialized ->
			if (initialized == true) {
				/* Perform Classification */
				// putting it here instead of onStart() prevents classification from happening multiple times when you move back & forth between the classification & analysis pages
				performClassification()
				lifecycleScope.launch(Dispatchers.Main) {
					Log.i("Total Execution Time", "Total Execution Time: ${MainActivity.executionTime} ms")
					MainActivity.executionTime = 0L
				}
			}
		}
	}

	private fun classifyIndividualFruit(targetHS: FloatArray): Pair<Int, Int> {
		val classificationModel = context?.let { Classification(it, classificationFile) }!!
		return classificationModel.predict(targetHS, numberOfBands.toLong(), patchWidth.toLong(), patchHeight.toLong())

	}

	private fun getSignature(predictedHS: FloatArray, row: Int, col: Int): FloatArray {
		val signature = FloatArray(numberOfBands)
		var signatureString = ""
		// Log.i("Touch Coordinates", "$row, $col")

		// 'remaining' as in how many pixels to the edges of the current band
		val remainingX = bitmapsWidth - 1 - col       // -1 is the pixel itself
		val remainingY = bitmapsHeight - 1 - row      // -1 is the pixel itself

		var idx = bitmapsWidth*row + col

		val series = LineGraphSeries<DataPoint>()

		for (i in 0 until numberOfBands) {
			signature[i] = predictedHS[idx]
			if (advancedControlOption)
				series.appendData(DataPoint(ACTUAL_BAND_WAVELENGTHS[i*bandSpacing], predictedHS[idx].toDouble()), true, numberOfBands)

			idx += remainingX + bitmapsWidth*remainingY + 1 // go to the first pixel of the next band
			idx += bitmapsWidth*row + col					// go to pixel at the same row & col on the next band
			signatureString += "${signature[i]}, "
		}
		Log.i("Signature", signatureString)

		if (advancedControlOption) {
			val graphView = fragmentReconstructionBinding.graphView

			graphView.gridLabelRenderer.padding = 50
			graphView.gridLabelRenderer.textSize = 60F
			series.dataPointsRadius = 20F
			series.thickness = 10
			series.color = color
			graphView.addSeries(series)
		}

		return signature
	}

	private fun generateIndividualHypercube(box: Box): FloatArray {
		val reconstructionModel = context?.let { Reconstruction(it, reconstructionFile) }!!

		val rgbBitmap = cropImage(croppableRGBBitmap, box.left, box.top)
		val nirBitmap = cropImage(croppableNIRBitmap, box.left, box.top)
		bitmapsWidth = rgbBitmap.width
		bitmapsHeight = rgbBitmap.height

		return reconstructionModel.predict(rgbBitmap, nirBitmap)

	}

	private fun getBand(predictedHS: FloatArray, bandNumber: Int, reverseScale: Boolean = false): Bitmap {
		val alpha: Byte = (255).toByte()

		Log.i("getBand", "$patchHeight, $patchWidth")

		val byteBuffer = ByteBuffer.allocate((patchWidth + 1) * (patchHeight + 1) * 4)
		var bmp = Bitmap.createBitmap(patchWidth, patchHeight, Bitmap.Config.ARGB_8888)

		val startOffset = bandNumber * patchWidth * patchHeight
		val endOffset = (bandNumber+1) * patchWidth * patchHeight - 1

		// mapping smallest value to 0 and largest value to 255
		val maxValue = predictedHS.maxOrNull() ?: 1.0f
		val minValue = predictedHS.minOrNull() ?: 0.0f
		val delta = maxValue-minValue
		var tempValue: Byte

		// Define if float min..max will be mapped to 0..255 or 255..0
		val conversion = when(reverseScale) {
			false -> { v: Float -> (((v - minValue) / delta * 255)).toInt().toByte() }
			true -> { v: Float -> (255 - (v - minValue) / delta * 255).toInt().toByte() }
		}
		var buffIdx = 0
		for (i in startOffset .. endOffset) {
			tempValue = conversion(predictedHS[i])
			byteBuffer.put(4 * buffIdx, tempValue)
			byteBuffer.put(4 * buffIdx + 1, tempValue)
			byteBuffer.put(4 * buffIdx + 2, tempValue)
			byteBuffer.put(4 * buffIdx + 3, alpha)
			buffIdx += 1
		}

		bmp.copyPixelsFromBuffer(byteBuffer)
		bmp = Bitmap.createBitmap(bmp, 0, 0, patchWidth, patchHeight, null, false)
		return bmp
	}

	/** Utility function used to add an item to the viewpager and notify it, in the main thread */
	private fun addItemToViewPager(view: ViewPager2, item: Bitmap, position: Int) = view.post {
		bandsHS.add(item)
		view.adapter!!.notifyItemChanged(position)
	}

	companion object {
		private const val patchWidth = 64
		private const val patchHeight = 64
		private val ACTUAL_BAND_WAVELENGTHS = listOf(397.32, 400.20, 403.09, 405.97, 408.85, 411.74, 414.63, 417.52, 420.40, 423.29, 426.19, 429.08, 431.97, 434.87, 437.76, 440.66, 443.56, 446.45, 449.35, 452.25, 455.16, 458.06, 460.96, 463.87, 466.77, 469.68, 472.59, 475.50, 478.41, 481.32, 484.23, 487.14, 490.06, 492.97, 495.89, 498.80, 501.72, 504.64, 507.56, 510.48, 513.40, 516.33, 519.25, 522.18, 525.10, 528.03, 530.96, 533.89, 536.82, 539.75, 542.68, 545.62, 548.55, 551.49, 554.43, 557.36, 560.30, 563.24, 566.18, 569.12, 572.07, 575.01, 577.96, 580.90, 583.85, 586.80, 589.75, 592.70, 595.65, 598.60, 601.55, 604.51, 607.46, 610.42, 613.38, 616.34, 619.30, 622.26, 625.22, 628.18, 631.15, 634.11, 637.08, 640.04, 643.01, 645.98, 648.95, 651.92, 654.89, 657.87, 660.84, 663.81, 666.79, 669.77, 672.75, 675.73, 678.71, 681.69, 684.67, 687.65, 690.64, 693.62, 696.61, 699.60, 702.58, 705.57, 708.57, 711.56, 714.55, 717.54, 720.54, 723.53, 726.53, 729.53, 732.53, 735.53, 738.53, 741.53, 744.53, 747.54, 750.54, 753.55, 756.56, 759.56, 762.57, 765.58, 768.60, 771.61, 774.62, 777.64, 780.65, 783.67, 786.68, 789.70, 792.72, 795.74, 798.77, 801.79, 804.81, 807.84, 810.86, 813.89, 816.92, 819.95, 822.98, 826.01, 829.04, 832.07, 835.11, 838.14, 841.18, 844.22, 847.25, 850.29, 853.33, 856.37, 859.42, 862.46, 865.50, 868.55, 871.60, 874.64, 877.69, 880.74, 883.79, 886.84, 889.90, 892.95, 896.01, 899.06, 902.12, 905.18, 908.24, 911.30, 914.36, 917.42, 920.48, 923.55, 926.61, 929.68, 932.74, 935.81, 938.88, 941.95, 945.02, 948.10, 951.17, 954.24, 957.32, 960.40, 963.47, 966.55, 969.63, 972.71, 975.79, 978.88, 981.96, 985.05, 988.13, 991.22, 994.31, 997.40, 1000.49, 1003.58)
	}
}