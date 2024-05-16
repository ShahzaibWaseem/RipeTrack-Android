package com.shahzaib.mobislp.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
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
import com.shahzaib.mobislp.*
import com.shahzaib.mobislp.databinding.FragmentReconstructionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.round
import kotlin.properties.Delegates

class ReconstructionFragment: Fragment() {
	private lateinit var predictedHS: FloatArray
	private lateinit var classificationPair: Pair<Int, Int>
	private var bandsHS: MutableList<Bitmap> = mutableListOf()
	private var reconstructionDuration = 0F
	private var classificationDuration = 0F
	private val numberOfBands = 68
	private val bandSpacing = 204 / numberOfBands
	private var outputLabelString: String = ""
	private var clickedX = 0.0F
	private var clickedY = 0.0F
	private val bandsChosen = mutableListOf<Int>()
	private val loadingDialogFragment = LoadingDialogFragment()
	private val randomColor = Random()
	private var boundedAnalysis = true // assume user wants to reconstruct a portion of the whole image
	private var color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))

	/** Host's navigation controller */
	private val navController: NavController by lazy {
		Navigation.findNavController(requireActivity(), R.id.fragment_container)
	}

	private var _fragmentReconstructionBinding: FragmentReconstructionBinding? = null
	private val fragmentReconstructionBinding get() = _fragmentReconstructionBinding!!

	private lateinit var sharedPreferences: SharedPreferences
	private lateinit var mobiSpectralApplication: String
	private lateinit var reconstructionFile: String
	private lateinit var classificationFile: String
	private var reconstructionFiles = arrayOf("RipeTrack_reconstruction_mobile_pa_68.pt", "RipeTrack_reconstruction_mobile_bmn_68.pt")
	private var classificationFiles = arrayOf("RipeTrack_classification_mobile_pa.pt", "RipeTrack_classification_mobile_bmn.pt")
	private lateinit var mobiSpectralControlOption: String
	private var advancedControlOption by Delegates.notNull<Boolean>()

	private lateinit var classificationLabels: Map<Pair<String, Long>, String>

	private var bitmapsWidth = Utils.torchWidth
	private var bitmapsHeight = Utils.torchHeight
	private var alreadyMultiLabelInferred = false
	private var applyOffset = false

	private fun imageViewFactory() = ImageView(requireContext()).apply {
		layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
	}

	// set up the progress bar, progress text, & ripeness buttons' constraint for classification
	private lateinit var progressBar: ProgressBar
	private lateinit var progressText: TextView
	private lateinit var ripenessBtnConstraint: ConstraintLayout

	// flag for analysis
	private var analyze = false

	// views for toggling graph visibility
	private lateinit var toggleVisibilityViews: Array<View>

	private suspend fun displayClassification()
	{
		// delay the function to give a "growth" effect to the progress bar
		delay(50L)

		if (!boundedAnalysis) {
//			val classifyBtn = requireView().findViewById<Button>(R.id.classifyButton)
//			classifyBtn.visibility = View.INVISIBLE
			val classificationConstraint =
				requireView().findViewById<ConstraintLayout>(R.id.classificationConstraint)
			classificationConstraint.visibility = View.VISIBLE
		}

		// lifetime classification (in percentages) to be used to grow progress bar
		val remainingLifetimePct = 100 - classificationPair.second * 10

		// make progress bar, progress text, & ripeness buttons visible
		if (progressBar.visibility != View.VISIBLE && ripenessBtnConstraint.visibility != View.VISIBLE) {
			progressBar.visibility = View.VISIBLE
			ripenessBtnConstraint.visibility = View.VISIBLE
			progressText.visibility = View.VISIBLE

		}

		if (progressBar.progress < remainingLifetimePct) {
			progressBar.incrementProgressBy(1)
			progressText.text = getString(R.string.remaining_lifetime_placeholder, progressBar.progress)
			displayClassification()
		}
		else {

			val ripeness = classificationPair.first

			// change color of progressbar
			progressBar.progressTintList = (
					when (ripeness) {
						// unripe
						0 -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_green))
						// ripe
						1 -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_orange))
						// expired
						else -> ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
					}
					)

			when (ripeness)
			{
				0 -> {
					val unripeBtn = requireView().findViewById<MaterialButton>(R.id.unripeBtn)
					unripeBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_green))
					unripeBtn.setTextColor(Color.WHITE)
					unripeBtn.strokeWidth = 8
					unripeBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
				}
				1 -> {
					val ripeBtn = requireView().findViewById<MaterialButton>(R.id.ripeBtn)
					ripeBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.progress_orange))
					ripeBtn.setTextColor(Color.WHITE)
					ripeBtn.strokeWidth = 8
					ripeBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
				}
				else -> {
					val expiredBtn = requireView().findViewById<MaterialButton>(R.id.expiredBtn)
					expiredBtn.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.sfu_primary))
					expiredBtn.setTextColor(Color.WHITE)
					expiredBtn.strokeWidth = 8
					expiredBtn.strokeColor = ColorStateList.valueOf(Color.BLACK)
				}
			}

		}
	}
	private fun performClassification()
	{
		lifecycleScope.launch(Dispatchers.Main)
		{
			// (initialize or reset) progress value on both bar & progress text
			progressBar.progress = 0
			progressText.text = getString(R.string.remaining_lifetime_placeholder, progressBar.progress)

			classifyFruit()
			displayClassification()

		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		super.onCreateView(inflater, container, savedInstanceState)
		_fragmentReconstructionBinding = FragmentReconstructionBinding.inflate(
			inflater, container, false)
		LoadingDialogFragment.text = getString(R.string.reconstructing_hypercube_string)
		loadingDialogFragment.isCancelable = false

		sharedPreferences = requireActivity().getSharedPreferences("mobislp_preferences", Context.MODE_PRIVATE)
		mobiSpectralApplication = sharedPreferences.getString("application", getString(R.string.apple_string))!!
		mobiSpectralControlOption = sharedPreferences.getString("option", getString(R.string.advanced_option_string))!!

		// set reconstruction & classification file options based on fruit
		val fruit = sharedPreferences.getString("fruit", null)

//		Log.i("Shared Preferences (Fruit Selected)", "$fruit")

		if (fruit.equals("Pear") || fruit.equals("Avocado"))
		{
			reconstructionFile = reconstructionFiles[0]
			classificationFile = classificationFiles[0]
		}
		else
		{
			reconstructionFile = reconstructionFiles[1]
			classificationFile = classificationFiles[1]
		}

		advancedControlOption = when (mobiSpectralControlOption) {
			getString(R.string.advanced_option_string) -> true
			getString(R.string.simple_option_string) -> false
			else -> true
		}

//		fragmentReconstructionBinding.textViewClass.text = mobiSpectralApplication

		toggleVisibilityViews = arrayOf(
			fragmentReconstructionBinding.graphView,
			fragmentReconstructionBinding.informationText,
			fragmentReconstructionBinding.classificationConstraint,
			fragmentReconstructionBinding.progressConstraint,
			fragmentReconstructionBinding.analyzeButton
		)

		if (!advancedControlOption) {
			fragmentReconstructionBinding.analysisButton.visibility = View.INVISIBLE
//			fragmentReconstructionBinding.simpleModeSignaturePositionTextView.visibility = View.VISIBLE
			fragmentReconstructionBinding.graphView.visibility = View.INVISIBLE
			// fragmentReconstructionBinding.textViewClassTime.text = ""
//			fragmentReconstructionBinding.simpleModeSignaturePositionTextView.text = getString(R.string.simple_mode_signature_string, MainActivity.tempRectangle.centerX(), MainActivity.tempRectangle.centerY())
			// fragmentReconstructionBinding.textViewReconTime.visibility = View.INVISIBLE
			// fragmentReconstructionBinding.textViewClassTime.visibility = View.INVISIBLE
		}

		return fragmentReconstructionBinding.root
	}
	val reloadLambda = {}

	@SuppressLint("ClickableViewAccessibility")
	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		/*
		fragmentReconstructionBinding.information.setOnClickListener {
/*			if (!advancedControlOption)
				generateAlertBox(requireContext(), "Information", resources.getString(R.string.reconstruction_analysis_information_simple_string), reloadLambda)
			else
				generateAlertBox(requireContext(),"Information", resources.getString(R.string.reconstruction_analysis_information_string), reloadLambda)*/
			if (!advancedControlOption)
				generateAlertBox(requireContext(), "", resources.getString(R.string.reconstruction_analysis_information_simple_string), reloadLambda)
			else
				generateAlertBox(requireContext(),"", resources.getString(R.string.reconstruction_analysis_information_string), reloadLambda)
		}
		 */



		fragmentReconstructionBinding.viewpager.apply {
			offscreenPageLimit=2
			adapter = GenericListAdapter(bandsHS, itemViewFactory = { imageViewFactory() })
			{ view, item, _ ->
				view as ImageView
				view.scaleType = ImageView.ScaleType.FIT_XY
				var bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
				var canvas = Canvas(bitmapOverlay)
				canvas.drawBitmap(item, Matrix(), null)
				var itemTouched = false
				var savedClickedX = 0.0F
				var savedClickedY = 0.0F

				if (advancedControlOption) {
					view.setOnTouchListener { v, event ->
						if (analyze)
						{
							clickedX = (event!!.x / v!!.width) * bitmapsWidth
							clickedY = (event.y / v.height) * bitmapsHeight
							if (!itemTouched) {
								savedClickedX = clickedX
								savedClickedY = clickedY
								itemTouched = true
								Log.i("Pixel Clicked", "X: ${clickedX.toInt()} ($bitmapsWidth), Y: ${clickedY.toInt()} ($bitmapsHeight)")
								color = Color.argb(255, randomColor.nextInt(256), randomColor.nextInt(256), randomColor.nextInt(256))

								val paint = Paint()
								paint.color = color
								paint.style = Paint.Style.STROKE
								paint.strokeWidth = 2.5F

								canvas.drawCircle(clickedX, clickedY, 5F, paint)
								view.setImageBitmap(bitmapOverlay)
								try {
									//inference()
									val inputSignature = getSignature(predictedHS, clickedY.toInt(), clickedX.toInt())
									MainActivity.actualLabel = ""
//                                addCSVLog(requireContext())
								} catch (e: NullPointerException) {
									e.printStackTrace()
								}
							}
							if (itemTouched && savedClickedX != clickedX && savedClickedY != clickedY)
								itemTouched = false
						}


						false
					}

					view.setOnLongClickListener {

						bitmapOverlay = Bitmap.createBitmap(item.width, item.height, item.config)
						canvas = Canvas(bitmapOverlay)
						canvas.drawBitmap(item, Matrix(), null)
						view.setImageBitmap(bitmapOverlay)
						fragmentReconstructionBinding.graphView.removeAllSeries()         // remove all previous series



						/*

							val leftCrop = item.width/2 - Utils.boundingBoxWidth
							val topCrop = item.height/2 - Utils.boundingBoxHeight
							val rightCrop = item.width/2 + Utils.boundingBoxWidth
							val bottomCrop = item.height/2 + Utils.boundingBoxHeight



							val paint = Paint()
							paint.color = Color.argb(255, 0, 0, 0)
							paint.strokeWidth = 2.5F
							paint.style = Paint.Style.STROKE

							Log.i("Crop Location", "L: $leftCrop, R: $rightCrop, T: $topCrop, B: $bottomCrop")

							canvas.drawRect(leftCrop-2.5F, topCrop-2.5F, rightCrop+2.5F, bottomCrop+2.5F, paint)
							view.setImageBitmap(bitmapOverlay)
							applyOffset = true

							// default coordinates for classifying a 64x64 region
							clickedX = 239F
							clickedY = 319F

							// start up classification thread
							if (::predictedHS.isInitialized) {
								performClassification()
							}


							try {
								MainActivity.actualLabel = ""
//                                addCSVLog(requireContext())
							} catch (e: NullPointerException) {
								e.printStackTrace()
							}
							*/

						false
					}
				}
				else {

					color = Color.argb(
						255,
						randomColor.nextInt(256),
						randomColor.nextInt(256),
						randomColor.nextInt(256)
					)
					val paint = Paint()
					paint.color = color
					paint.style = Paint.Style.STROKE
					canvas.drawCircle(Utils.torchWidth/2F, Utils.torchHeight/2F, 10F, paint)
					view.setImageBitmap(bitmapOverlay)
					//inference()
//                    addCSVLog(requireContext())

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

		fragmentReconstructionBinding.analysisButton.setOnClickListener {
			fragmentReconstructionBinding.textConstraintView.visibility = View.INVISIBLE
			fragmentReconstructionBinding.graphView.visibility = View.VISIBLE
		}
		loadingDialogFragment.show(childFragmentManager, LoadingDialogFragment.TAG)

		// initialize these variables for the classification step
		progressBar = requireView().findViewById<ProgressBar>(R.id.progressBar)
		progressText = requireView().findViewById<TextView>(R.id.progressText)
		ripenessBtnConstraint = requireView().findViewById<ConstraintLayout>(R.id.ripenessBtnConstraint)

	}

	private fun toggleGraphVisibility()
	{
		// safety check, should be initialized by then
		if (::toggleVisibilityViews.isInitialized)
		{
			toggleVisibilityViews.forEach {
				if (it.visibility == View.VISIBLE)
				{
					it.visibility = View.INVISIBLE
				}
				else {
					it.visibility = View.VISIBLE
				}
			}
		}

	}

	override fun onStart() {
		super.onStart()

		Timer().schedule(1000) {
			val reconstructionThread = Thread {
				if (!::predictedHS.isInitialized)
					generateHypercube()
			}
			reconstructionThread.start()
			try { reconstructionThread.join() }
			catch (exception: InterruptedException) { exception.printStackTrace() }

			val graphView = _fragmentReconstructionBinding!!.graphView
			graphView.gridLabelRenderer.horizontalAxisTitle = "Wavelength λ (nm)"
			graphView.gridLabelRenderer.verticalAxisTitle = "Reflectance"
			graphView.gridLabelRenderer.padding = 50
			graphView.gridLabelRenderer.textSize = 50F
			graphView.gridLabelRenderer.horizontalAxisTitleTextSize = 50F
			graphView.gridLabelRenderer.verticalAxisTitleTextSize = 50F
//			graphView.title = "Click on the image to show the signature"
//			graphView.titleTextSize = 50F
			graphView.viewport.isXAxisBoundsManual = true
			graphView.viewport.setMaxX(1000.0)
			graphView.viewport.setMinX(400.0)
			graphView.viewport.isYAxisBoundsManual = true
			graphView.viewport.setMaxY(1.0)
			graphView.gridLabelRenderer.setHumanRounding(true)

			/*
			graphView.setOnLongClickListener{
				fragmentReconstructionBinding.textConstraintView.visibility = View.VISIBLE
				fragmentReconstructionBinding.graphView.visibility = View.INVISIBLE
				false
			}
			 */

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
					for (i in selectedIndices)
					{
						bandsChosen.add(i)
						addItemToViewPager(fragmentReconstructionBinding.viewpager, getBand(predictedHS, i), selectedIndices.indexOf(i))
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
				if (analyze)
				{
					tab.text = (round(
							ACTUAL_BAND_WAVELENGTHS[bandsChosen[position] * bandSpacing] / 100
						) * 100).toInt().toString()
				}
				else
				{

					when (position) {
						0 -> tab.text = "RGB"
						1 -> tab.text = "NIR"
					}
				}
			}.attach()

			val reloadBtn = fragmentReconstructionBinding.reloadButton
			reloadBtn.setOnClickListener {
				if (analyze)
				{
					// go back to classification page
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
				else
				{
					// navigate back to the imageviewerfragment
					lifecycleScope.launch(Dispatchers.Main) {
						navController.navigate(
							ReconstructionFragmentDirections.actionReconstructionFragmentToImageViewerFragment(MainActivity.rgbAbsolutePath, MainActivity.nirAbsolutePath)
						)
					}
				}
			}



//			addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempRGBBitmap, 7)

			addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempRGBBitmap, 0)
			addItemToViewPager(fragmentReconstructionBinding.viewpager, MainActivity.tempNIRBitmap, 1)

			// fragmentReconstructionBinding.viewpager.currentItem = fragmentReconstructionBinding.viewpager.adapter!!.itemCount - 1
			loadingDialogFragment.dismissDialog()

		}
	}

	override fun onResume()
	{
		super.onResume()

		/* Perform Classification */
		// putting it here instead of onStart() prevents classification from happening multiple times when you move back & forth between the classification & analysis pages
		if (boundedAnalysis)
		{
			performClassification()
			lifecycleScope.launch(Dispatchers.Main) {
				Toast.makeText(requireContext(), "Total Execution Time: ${MainActivity.executionTime} ms", LENGTH_LONG).show()
				MainActivity.executionTime = 0L
			}

		} else
		{
			// modify viewmodel in main (UI) thread to prevent data race
			lifecycleScope.launch(Dispatchers.Main)
			{
				val classificationConstraint = requireView().findViewById<ConstraintLayout>(R.id.classificationConstraint)
				classificationConstraint.visibility = View.INVISIBLE
//					val classifyBtn = requireView().findViewById<Button>(R.id.classifyButton)
//					classifyBtn.visibility = View.VISIBLE
//					classifyBtn.setClickable(true)
			}

		}
	}

	private fun classifyFruit()
	{
		val classificationModel = context?.let { Classification(it, classificationFile) }!!

		val startTime = System.currentTimeMillis()

		if (boundedAnalysis)
			classificationPair = classificationModel.predict(predictedHS, 68, 64, 64)
		else
		{
/*			val targetX = clickedX.toInt()
			val targetY = clickedY.toInt()
			// traverse the 68 hyperspectral bands and pick out the selected region from all of them
			val predictedHSRegion: ArrayList<Float> = arrayListOf()
			for (i in 0 until numberOfBands)
			{
//				val topLeftIdx = i * 640 * 480 + (640*targetY + targetX)
				val topLeftIdx = i * 640 * 480 + (480*targetY + targetX)
				// here (j,k) is the relative coordinates of a point on the selected boundary region
				for (k in 0 until 64)
				{
					for (j in 0 until 64)
					{
						predictedHSRegion.add(
							predictedHS[topLeftIdx + 64*k + j]
						)
					}
				}
			}
			val startX = clickedX.toInt() - 32
			val startY = clickedY.toInt() - 32
			val endX = startX + 64
			val endY = startY + 64
			val predictedHSRegion: ArrayList<Float> = arrayListOf()
			for (i in 0 until numberOfBands)
			{
				for (x in startX until endX)
				{
					for (y in startY until endY)
					{
						val idx = i*640*480 + (480*y+x)
						predictedHSRegion.add(
							predictedHS[idx]
						)
					}
				}
			}
			classificationPair = classificationModel.predict(predictedHSRegion.toFloatArray(), 68, 64, 64)
 */
		}

		val endTime = System.currentTimeMillis()

		// in milliseconds
		classificationDuration = (endTime - startTime).toFloat()
		MainActivity.executionTime += (endTime - startTime)
		MainActivity.classificationTime = "$classificationDuration ms"


		// use a toast to display classification time
		lifecycleScope.launch(Dispatchers.Main)
		{
			val classificationToast = Toast.makeText(requireContext(), "Classification Time ${MainActivity.classificationTime}", LENGTH_LONG)
			classificationToast.show()
		}
	}

	private fun inference() {
		if (!advancedControlOption) {
			clickedX = bitmapsWidth/2F
			clickedY = bitmapsHeight/2F
		}
		val finalResults = ArrayList<Long> ()

		if (applyOffset && !alreadyMultiLabelInferred) {

			val multiClassificationThread = Thread {
				val zoneHeight = 8
				val zoneWidth = 8
				val numberOfZones = (2*Utils.boundingBoxWidth.toInt())/zoneWidth
				val offsetX = if (applyOffset) bitmapsWidth/2 - Utils.boundingBoxWidth.toInt() else 0
				val offsetY = if (applyOffset) bitmapsHeight/2 - Utils.boundingBoxHeight.toInt() else 0

				for (z1 in 0 until numberOfZones) {
					for (z2 in 0 until numberOfZones) {
						val results = ArrayList<Long> ()

						Log.i("Number of Zones", "Zones $numberOfZones X: ${offsetY+z1*zoneHeight} Y:  ${offsetX+z2*zoneWidth} ")
						val frequencies = results.groupingBy { it }.eachCount()
						finalResults.add(frequencies.maxBy { it.value }.key)
						Log.i("Signatures OneClassify", "Final Results: $finalResults")
					}
				}
			}
			multiClassificationThread.start()
			try { multiClassificationThread.join() }
			catch (exception: InterruptedException) { exception.printStackTrace() }

			alreadyMultiLabelInferred = true

			val finalFrequencies = finalResults.groupingBy { it }.eachCount()
			Log.i("Signatures OneClassify", "$finalResults $finalFrequencies")
			var frequenciesString = ""
			for (item in finalFrequencies) {
				val substring = if (Pair(mobiSpectralApplication, item.key) !in classificationLabels)
					"Something went wrong = ${item.value.toFloat()/finalResults.size.toFloat()}\n"
				else
					"${classificationLabels[Pair(mobiSpectralApplication, item.key)]!!} = ${item.value.toFloat()/finalResults.size.toFloat()}\n"

				frequenciesString += substring
			}
			Log.i("Frequency String", frequenciesString)
			val maxLabel = finalFrequencies.maxBy { it.value }


//            fragmentReconstructionBinding.textViewClassTime.text = frequenciesString
//			fragmentReconstructionBinding.textViewClassTime.visibility = View.VISIBLE
//			fragmentReconstructionBinding.textViewClass.text = classificationLabels[Pair(mobiSpectralApplication, maxLabel.key)]
			MainActivity.predictedLabel = frequenciesString


		}
		if (advancedControlOption){
			val inputSignature = getSignature(predictedHS, clickedY.toInt(), clickedX.toInt())
			MainActivity.predictedLabel = outputLabelString
//            fragmentReconstructionBinding.textViewClass.text = outputLabelString
//			fragmentReconstructionBinding.graphView.title = "$outputLabelString Signature at (x: ${clickedX.toInt()}, y: ${clickedY.toInt()})"
		}
//        addCSVLog(requireContext())
	}

	private fun getSignature(predictedHS: FloatArray, SignatureX: Int, SignatureY: Int): FloatArray {
		val signature = FloatArray(numberOfBands)
		// Log.i("Touch Coordinates", "$SignatureX, $SignatureY")
		val leftX = bitmapsWidth - 1 - SignatureY       // -1 is the pixel itself
		val leftY = bitmapsHeight - 1 - SignatureX      // -1 is the pixel itself

		var idx = bitmapsWidth*SignatureX
		idx += SignatureY
		// print("Signature is:")
		val series = LineGraphSeries<DataPoint>()

		for (i in 0 until numberOfBands) {
			signature[i] = predictedHS[idx]
			if (advancedControlOption)
				series.appendData(DataPoint(ACTUAL_BAND_WAVELENGTHS[i*bandSpacing], predictedHS[idx].toDouble()), true, numberOfBands)
			idx += leftX + bitmapsWidth*leftY + 1 + (bitmapsWidth*SignatureX + SignatureY)
		}

		if (advancedControlOption) {
			val graphView = fragmentReconstructionBinding.graphView
			// graphView.removeAllSeries()         // remove all previous series
//			graphView.title = "$outputLabelString Signature at (x: $SignatureX, y: $SignatureY)"
			graphView.gridLabelRenderer.padding = 50
			graphView.gridLabelRenderer.textSize = 60F
			series.dataPointsRadius = 20F
			series.thickness = 10
			series.color = color
			graphView.addSeries(series)
		}

		return signature
	}

	private fun generateHypercube() {
		val reconstructionModel = context?.let { Reconstruction(it, reconstructionFile) }!!

		val rgbBitmap = MainActivity.originalRGBBitmap
		val nirBitmap = MainActivity.originalNIRBitmap
		bitmapsWidth = rgbBitmap.width
		bitmapsHeight = rgbBitmap.height

		if (!(bitmapsWidth == 64 && bitmapsHeight == 64)) {
			// the entire image is being reconstructed
			boundedAnalysis = false
		}

		val startTime = System.currentTimeMillis()
		predictedHS = reconstructionModel.predict(rgbBitmap, nirBitmap)

		val endTime = System.currentTimeMillis()
		reconstructionDuration = (endTime - startTime).toFloat()
		MainActivity.executionTime += (endTime - startTime)
		reconstructionDuration /= 1000.0F
		println(getString(R.string.reconstruction_time_string, reconstructionDuration))
		MainActivity.reconstructionTime = "$reconstructionDuration s"
//		fragmentReconstructionBinding.textViewReconTime.text = getString(R.string.reconstruction_time_string, reconstructionDuration)
	}
	private fun getBand(predictedHS: FloatArray, bandNumber: Int, reverseScale: Boolean = false): Bitmap {
		val alpha: Byte = (255).toByte()

		val byteBuffer = ByteBuffer.allocate((bitmapsWidth + 1) * (bitmapsHeight + 1) * 4)
		var bmp = Bitmap.createBitmap(bitmapsWidth, bitmapsHeight, Bitmap.Config.ARGB_8888)

		val startOffset = bandNumber * bitmapsWidth * bitmapsHeight
		val endOffset = (bandNumber+1) * bitmapsWidth * bitmapsHeight - 1

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
		bmp = Bitmap.createBitmap(bmp, 0, 0, bitmapsWidth, bitmapsHeight, null, false)
		return bmp
	}

	/** Utility function used to add an item to the viewpager and notify it, in the main thread */
	private fun addItemToViewPager(view: ViewPager2, item: Bitmap, position: Int) = view.post {
		bandsHS.add(item)
		view.adapter!!.notifyItemChanged(position)
	}

	companion object {
		private val ACTUAL_BAND_WAVELENGTHS = listOf(397.32, 400.20, 403.09, 405.97, 408.85, 411.74, 414.63, 417.52, 420.40, 423.29, 426.19, 429.08, 431.97, 434.87, 437.76, 440.66, 443.56, 446.45, 449.35, 452.25, 455.16, 458.06, 460.96, 463.87, 466.77, 469.68, 472.59, 475.50, 478.41, 481.32, 484.23, 487.14, 490.06, 492.97, 495.89, 498.80, 501.72, 504.64, 507.56, 510.48, 513.40, 516.33, 519.25, 522.18, 525.10, 528.03, 530.96, 533.89, 536.82, 539.75, 542.68, 545.62, 548.55, 551.49, 554.43, 557.36, 560.30, 563.24, 566.18, 569.12, 572.07, 575.01, 577.96, 580.90, 583.85, 586.80, 589.75, 592.70, 595.65, 598.60, 601.55, 604.51, 607.46, 610.42, 613.38, 616.34, 619.30, 622.26, 625.22, 628.18, 631.15, 634.11, 637.08, 640.04, 643.01, 645.98, 648.95, 651.92, 654.89, 657.87, 660.84, 663.81, 666.79, 669.77, 672.75, 675.73, 678.71, 681.69, 684.67, 687.65, 690.64, 693.62, 696.61, 699.60, 702.58, 705.57, 708.57, 711.56, 714.55, 717.54, 720.54, 723.53, 726.53, 729.53, 732.53, 735.53, 738.53, 741.53, 744.53, 747.54, 750.54, 753.55, 756.56, 759.56, 762.57, 765.58, 768.60, 771.61, 774.62, 777.64, 780.65, 783.67, 786.68, 789.70, 792.72, 795.74, 798.77, 801.79, 804.81, 807.84, 810.86, 813.89, 816.92, 819.95, 822.98, 826.01, 829.04, 832.07, 835.11, 838.14, 841.18, 844.22, 847.25, 850.29, 853.33, 856.37, 859.42, 862.46, 865.50, 868.55, 871.60, 874.64, 877.69, 880.74, 883.79, 886.84, 889.90, 892.95, 896.01, 899.06, 902.12, 905.18, 908.24, 911.30, 914.36, 917.42, 920.48, 923.55, 926.61, 929.68, 932.74, 935.81, 938.88, 941.95, 945.02, 948.10, 951.17, 954.24, 957.32, 960.40, 963.47, 966.55, 969.63, 972.71, 975.79, 978.88, 981.96, 985.05, 988.13, 991.22, 994.31, 997.40, 1000.49, 1003.58)
	}
}