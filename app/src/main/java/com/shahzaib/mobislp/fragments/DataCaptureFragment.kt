package com.shahzaib.mobislp.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.media.Image
import android.media.ImageReader
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.fragment.navArgs
import com.example.android.camera.utils.getPreviewOutputSize
import com.shahzaib.mobislp.MainActivity
import com.shahzaib.mobislp.R
import com.shahzaib.mobislp.Utils
import com.shahzaib.mobislp.Utils.imageFormat
import com.shahzaib.mobislp.databinding.FragmentDatacaptureBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class DataCaptureFragment: Fragment() {
	/** Android ViewBinding */
	private var _fragmentDataCaptureBinding: FragmentDatacaptureBinding? = null

	private val fragmentDataCaptureBinding get() = _fragmentDataCaptureBinding!!

	/** AndroidX navigation arguments */
	private val args: CameraFragmentArgs by navArgs()

	/** Host's navigation controller */
	private val navController: NavController by lazy {
		Navigation.findNavController(requireActivity(), R.id.fragment_container)
	}

	/** Detects, characterizes, and connects to a CameraDevice (used for all camera operations) */
	private val cameraManager: CameraManager by lazy {
		val context = requireContext().applicationContext
		context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
	}

	/** [CameraCharacteristics] corresponding to the provided Camera ID */
	private val characteristics: CameraCharacteristics by lazy {
		cameraManager.getCameraCharacteristics(args.cameraId)
	}

	/** Readers used as buffers for camera still shots */
	private lateinit var imageReader: ImageReader

	/** [HandlerThread] where all camera operations run */
	private val cameraThread = HandlerThread("CameraThread").apply { start() }

	/** [Handler] corresponding to [cameraThread] */
	private val cameraHandler = Handler(cameraThread.looper)

	/** [HandlerThread] where all buffer reading operations run */
	private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }

	/** [Handler] corresponding to [imageReaderThread] */
	private val imageReaderHandler = Handler(imageReaderThread.looper)

	/** The [CameraDevice] that will be opened in this fragment */
	private lateinit var camera: CameraDevice

	/** Internal reference to the ongoing [CameraCaptureSession] configured with our parameters */
	private lateinit var session: CameraCaptureSession

	private lateinit var cameraIdRGB: String
	private lateinit var cameraIdNIR: String

	private lateinit var sharedPreferences: SharedPreferences
	private var mobiSpectralApplicationID = 0
	private var fruitApplication = ""
	private var fruitID = 0
	private var offlineMode = false

	private val cameraSurfaceHolderCallback = object: SurfaceHolder.Callback {
		override fun surfaceDestroyed(holder: SurfaceHolder) = Unit

		override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

		override fun surfaceCreated(holder: SurfaceHolder) {
			// Selects appropriate preview size and configures view finder
			val previewSize = getPreviewOutputSize(fragmentDataCaptureBinding.viewFinder.display,
				characteristics, SurfaceHolder::class.java)
			// fragmentDataCaptureBinding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
			holder.setFixedSize(previewSize.width, previewSize.height)

			Log.i("Preview Size", "AutoFitSurface Holder: Width ${fragmentDataCaptureBinding.viewFinder.width}, Height ${fragmentDataCaptureBinding.viewFinder.height}")

			// To ensure that size is set, initialize camera in the view's thread
			view?.post { initializeCamera() }
		}
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		_fragmentDataCaptureBinding = FragmentDatacaptureBinding.inflate(inflater, container, false)
		sharedPreferences = requireActivity().getSharedPreferences("mobislp_preferences", Context.MODE_PRIVATE)
		mobiSpectralApplicationID = when(sharedPreferences.getString("application", "Organic Identification")!!) {
			else -> MainActivity.MOBISPECTRAL_APPLICATION
		}
		fruitID = sharedPreferences.getInt("fruitID", 0)
		offlineMode = sharedPreferences.getBoolean("offline_mode", false)
		fruitApplication = sharedPreferences.getString("fruit", "Avocado")!!
		cameraIdRGB = MainActivity.cameraIDList.first
		cameraIdNIR = MainActivity.cameraIDList.second
		_fragmentDataCaptureBinding!!.fruitIDTextView.text = getString(R.string.object_id_string, fruitID)
		MainActivity.dataCapturing = true
		MainActivity.fruitID = fruitID.toString()

		Log.i("Camera IDs", "RGB: $cameraIdRGB, NIR: $cameraIdNIR")
		return fragmentDataCaptureBinding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		fragmentDataCaptureBinding.viewFinder.holder.addCallback(cameraSurfaceHolderCallback)

		fragmentDataCaptureBinding.Title.setOnClickListener {
			lifecycleScope.launch(Dispatchers.Main) {
				navController.navigate(DataCaptureFragmentDirections.actionCameraToApplicationsTitle())
			}
		}

		val editor = sharedPreferences.edit()

		fragmentDataCaptureBinding.plusButton.setOnClickListener {
			fruitID += 1
			fragmentDataCaptureBinding.fruitIDTextView.text = getString(R.string.object_id_string, fruitID)
			editor!!.putInt("fruitID", fruitID)
			editor.apply()
		}
		fragmentDataCaptureBinding.minusButton.setOnClickListener {
			fruitID -= if (fruitID == 0) 0 else 1                     // No Negative IDs
			fragmentDataCaptureBinding.fruitIDTextView.text = getString(R.string.object_id_string, fruitID)
			editor!!.putInt("fruitID", fruitID)
			editor.apply()
		}

	}

	override fun onResume() {
		super.onResume()
		fragmentDataCaptureBinding.viewFinder.holder.addCallback(cameraSurfaceHolderCallback)
	}

	/**
	 * Begin all camera operations in a coroutine in the main thread. This function:
	 * - Opens the camera
	 * - Configures the camera session
	 * - Starts the preview by dispatching a repeating capture request
	 * - Sets up the still image capture listeners
	 */
	private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
		// Open the selected camera
		camera = openCamera(cameraManager, args.cameraId, cameraHandler)

		val size = if (cameraIdNIR == "OnePlus") Size(Utils.torchHeight, Utils.torchWidth) else Size(Utils.previewWidth, Utils.previewHeight)
		// val size = Size(Utils.previewWidth, Utils.previewHeight)

		imageReader = ImageReader.newInstance(size.width, size.height, args.pixelFormat, IMAGE_BUFFER_SIZE)
		Log.i("OutputSizes", "$camera.")
		val streamConfigurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
		val formats = streamConfigurationMap?.outputFormats
		val outSizes = streamConfigurationMap?.getOutputSizes(imageFormat)
		for (format in formats!!) {
			Log.i("Stream Configuration", "Format: $format")
		}
		for (size1 in outSizes!!) {
			Log.i("Stream Configuration", "Size: $size1")
		}

		// Creates list of Surfaces where the camera will output frames
		val targets = listOf(fragmentDataCaptureBinding.viewFinder.holder.surface, imageReader.surface)

		// Start a capture session using our open camera and list of Surfaces where frames will go
		session = createCaptureSession(camera, targets, cameraHandler)

		val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
			.apply { addTarget(fragmentDataCaptureBinding.viewFinder.holder.surface)
				set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
				set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO) }

		// This will keep sending the capture request as frequently as possible until the
		// session is torn down or session.stopRepeating() is called
		session.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

		if (args.cameraId == cameraIdNIR) {
			fragmentDataCaptureBinding.captureButton.performClick()
			fragmentDataCaptureBinding.captureButton.isPressed = true
			fragmentDataCaptureBinding.captureButton.invalidate()
		}

		// Listen to the capture button
		if (args.cameraId == cameraIdRGB) {
			fragmentDataCaptureBinding.captureButton.setOnClickListener {
				if (args.cameraId == cameraIdNIR) {
					fragmentDataCaptureBinding.captureButton.isPressed = false
					fragmentDataCaptureBinding.captureButton.invalidate()
				}
				// Disable click listener to prevent multiple requests simultaneously in flight
				it.isEnabled = false
				fragmentDataCaptureBinding.timer.visibility = View.VISIBLE

				val beepingTone = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

				object: CountDownTimer(3000, 1000) {
					override fun onTick(millisUntilFinished: Long) {
						val secondsRemaining = millisUntilFinished / 1000
						fragmentDataCaptureBinding.timer.text = "$secondsRemaining"
						beepingTone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
					}
					override fun onFinish() {
						fragmentDataCaptureBinding.timer.visibility = View.INVISIBLE
						Utils.vibrate(requireContext())
						savePhoto(args.cameraId)
					}
				}.start()
				// Re-enable click listener after photo is taken
				it.post { it.isEnabled = true }
			}
		}
		else {
			savePhoto(args.cameraId)
		}
	}

	private fun savePhoto(cameraId: String) {
		// Perform I/O heavy operations in a different scope
		lifecycleScope.launch(Dispatchers.IO) {
			takePhoto().use { result ->
				Log.d(TAG, "Result received: $result")

				// Save the result to disk
				val output = saveResult(result)
				MainActivity.originalImageRGB = rgbAbsolutePath
				MainActivity.originalImageNIR = output.absolutePath
				lifecycleScope.launch(Dispatchers.Main) {
					if (cameraId == cameraIdRGB){
						when (cameraIdNIR) {
							"OnePlus" -> navController.navigate(DataCaptureFragmentDirections.actionCameraToJpegViewer(rgbAbsolutePath, nirAbsolutePath))
							else -> navController.navigate(DataCaptureFragmentDirections.actionDataCaptureFragmentSelf(cameraIdNIR, imageFormat))
						}
					}
					else
						navController.navigate(DataCaptureFragmentDirections.actionCameraToJpegViewer(rgbAbsolutePath, output.absolutePath))
				}
			}
		}
	}

	/** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
	@SuppressLint("MissingPermission")
	private suspend fun openCamera(manager: CameraManager, cameraId: String, handler: Handler? = null):
			CameraDevice = suspendCancellableCoroutine { cont ->
		manager.openCamera(cameraId, object: CameraDevice.StateCallback() {
			override fun onOpened(device: CameraDevice) = cont.resume(device)

			override fun onDisconnected(device: CameraDevice) {
				Log.w(TAG, "Camera $cameraId has been disconnected")
				requireActivity().finish()
			}

			override fun onError(device: CameraDevice, error: Int) {
				val msg = when (error) {
					ERROR_CAMERA_DEVICE -> "Fatal (device)"
					ERROR_CAMERA_DISABLED -> "Device policy"
					ERROR_CAMERA_IN_USE -> "Camera in use"
					ERROR_CAMERA_SERVICE -> "Fatal (service)"
					ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
					else -> "Unknown"
				}
				val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
				Log.e(TAG, exc.message, exc)
				if (cont.isActive) cont.resumeWithException(exc)
			}
		}, handler)
	}

	/**
	 * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
	 * suspend coroutine
	 */
	@Suppress("DEPRECATION")
	private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler? = null):
			CameraCaptureSession = suspendCoroutine { cont ->
		// Create a capture session using the predefined targets; this also involves defining the
		// session state callback to be notified of when the session is ready
		device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
			override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

			override fun onConfigureFailed(session: CameraCaptureSession) {
				val exc = RuntimeException("Camera ${device.id} session configuration failed")
				Log.e(TAG, exc.message, exc)
				cont.resumeWithException(exc)
			}
		}, handler)
	}

	/**
	 * Helper function used to capture a still image using the [CameraDevice.TEMPLATE_STILL_CAPTURE]
	 * template. It performs synchronization between the [CaptureResult] and the [Image] resulting
	 * from the single capture, and outputs a [CombinedCaptureResult] object.
	 */
	private suspend fun takePhoto(): CombinedCaptureResult = suspendCoroutine { cont ->
		// Flush any images left in the image reader
		@Suppress("ControlFlowWithEmptyBody")
		while (imageReader.acquireNextImage() != null) {}

		// Start a new image queue
		val imageQueue = ArrayBlockingQueue<Image>(IMAGE_BUFFER_SIZE)
		imageReader.setOnImageAvailableListener({ reader ->
			val image = reader.acquireNextImage()
			Log.d(TAG, "Image available in queue: ${image.timestamp} W ${image.width}, H ${image.height}")

			imageQueue.add(image)
		}, imageReaderHandler)

		val captureRequest = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
			.apply { addTarget(imageReader.surface) }
		session.capture(captureRequest.build(), object: CameraCaptureSession.CaptureCallback() {
			override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
				super.onCaptureCompleted(session, request, result)

				val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
				Log.d(TAG, "Capture result received: $resultTimestamp")

				// Set a timeout in case image captured is dropped from the pipeline
				val exc = TimeoutException("Image dequeuing took too long")
				val timeoutRunnable = Runnable { cont.resumeWithException(exc) }
				imageReaderHandler.postDelayed(timeoutRunnable, IMAGE_CAPTURE_TIMEOUT_MILLIS)

				// Loop in the coroutine's context until an image with matching timestamp comes
				// We need to launch the coroutine context again because the callback is done in
				// the handler provided to the `capture` method, not in our coroutine context
				@Suppress("BlockingMethodInNonBlockingContext")
				lifecycleScope.launch(cont.context) {
					while (true) {
						// Dequeue images while timestamps don't match
						val image = imageQueue.take()

						// if (image.timestamp != resultTimestamp) continue
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
							image.format != ImageFormat.DEPTH_JPEG &&
							image.timestamp != resultTimestamp
						) continue
						Log.d(TAG, "Matching image dequeued: ${image.timestamp}, W ${image.width}, H ${image.height}")

						// Unset the image reader listener
						imageReaderHandler.removeCallbacks(timeoutRunnable)
						imageReader.setOnImageAvailableListener(null, null)

						// Clear the queue of images, if there are left
						while (imageQueue.size > 0) { imageQueue.take().close() }
						Log.d(TAG, "Capture Request DIMENSIONS: width ${image.width} Height: ${image.height}")

						// Build the result and resume progress
						cont.resume(CombinedCaptureResult(image, result, imageReader.imageFormat))
						// There is no need to break out of the loop, this coroutine will suspend
					}
				}
			}
		}, cameraHandler)
	}

	private fun isDark(bitmap: Bitmap): Boolean {
		val histogram = IntArray(256)

		for (i in 0..255) {
			histogram[i] = 0
		}

		var dark = false
		val darkThreshold = 0.25F
		var darkPixels = 0
		val pixels = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		for (color in pixels) {
			val r: Int = Color.red(color)
			val g: Int = Color.green(color)
			val b: Int = Color.blue(color)
			val brightness = (0.2126*r + 0.7152*g + 0.0722*b).toInt()
			histogram[brightness]++
		}

		for (i in 0..9)
			darkPixels += histogram[i]
		if (darkPixels > (bitmap.height * bitmap.width) * darkThreshold) {
			dark = true
		}
		Log.i("Dark", "DarkPixels: $darkPixels")
		return dark
	}

	/** Helper function used to save a [CombinedCaptureResult] into a [File] */
	private suspend fun saveResult(result: CombinedCaptureResult): File = suspendCoroutine { cont ->
		when (result.format) {
			ImageFormat.RAW_SENSOR -> {
				val dngCreator = DngCreator(characteristics, result.metadata)
				try {
					val output = createFile("RGB", fruitApplication, fruitID,"lossless")
					FileOutputStream(output).use { dngCreator.writeImage(it, result.image) }
					cont.resume(output)
				} catch (exc: IOException) {
					Log.e(TAG, "Unable to write DNG image to file", exc)
					cont.resumeWithException(exc)
				}
			}
			ImageFormat.YUV_420_888 -> {
				val yBuffer = result.image.planes[0].buffer
				val uBuffer = result.image.planes[1].buffer
				val vBuffer = result.image.planes[2].buffer

				val ySize = yBuffer.remaining()
				val uSize = uBuffer.remaining()
				val vSize = vBuffer.remaining()

				val yuvByteArray = ByteArray(ySize + uSize + vSize)

				yBuffer.get(yuvByteArray, 0, ySize)
				uBuffer.get(yuvByteArray, ySize, uSize)
				vBuffer.get(yuvByteArray, ySize + uSize, vSize)
			}
			// When the format is JPEG or DEPTH JPEG we can simply save the bytes as-is
			ImageFormat.JPEG, ImageFormat.DEPTH_JPEG -> {
				val buffer = result.image.planes[0].buffer
				val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }

				var rotatedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
				val correctionMatrix = Matrix().apply { postRotate(-90F); postScale(-1F, 1F); }
				val nirCameraID = MainActivity.cameraIDList.second
				rotatedBitmap = Bitmap.createBitmap(rotatedBitmap, 0, 0, rotatedBitmap.width,
					rotatedBitmap.height, if (nirCameraID == "OnePlus") Matrix().apply { postRotate(90F) } else correctionMatrix, false)
				val stream = ByteArrayOutputStream()
				rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
				val rotatedBytes = stream.toByteArray()
				Log.i("Save Photo", "Bitmap Size: W ${rotatedBitmap.width} H ${rotatedBitmap.height}, byte size: ${bytes.size}, rotated Bytes Size: ${rotatedBytes.size} buffer Size: $buffer")

				try {
					val nir = if (args.cameraId == cameraIdNIR) "NIR" else "RGB"

					if (isDark(rotatedBitmap)) {
						Log.i("Dark", "The bitmap is too dark")
						fragmentDataCaptureBinding.illumination.text = resources.getString(R.string.formatted_illumination_string, "Inadequate")
						fragmentDataCaptureBinding.illumination.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_error))
						Toast.makeText(context, "The bitmap is too dark", Toast.LENGTH_SHORT).show()
					}
					else {
						fragmentDataCaptureBinding.illumination.text = resources.getString(R.string.formatted_illumination_string, "Adequate")
						fragmentDataCaptureBinding.illumination.setTextColor(ContextCompat.getColor(requireContext(), com.google.android.material.R.color.design_default_color_secondary))
					}
					val output = createFile(nir, fruitApplication, fruitID, "lossless")
					FileOutputStream(output).use { it.write(rotatedBytes) }
					cont.resume(output)
					Log.i("Filename", output.toString())
				} catch (exc: IOException) {
					Log.e(TAG, "Unable to write JPEG image to file", exc)
					cont.resumeWithException(exc)
				}
			}

			// No other formats are supported by this sample
			else -> {
				val exc = RuntimeException("Unknown image format: ${result.image.format}")
				Log.e(TAG, exc.message, exc)
				cont.resumeWithException(exc)
			}
		}
	}

	override fun onStop() {
		super.onStop()
		try {
			camera.close()
		} catch (exc: Throwable) {
			Log.e(TAG, "Error closing camera", exc)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		cameraThread.quitSafely()
		imageReaderThread.quitSafely()
	}

	companion object {
		private val TAG = CameraFragment::class.java.simpleName
		private lateinit var fileFormat: String
		lateinit var rgbAbsolutePath: String
		lateinit var nirAbsolutePath: String

		/** Maximum number of images that will be held in the reader's buffer */
		private const val IMAGE_BUFFER_SIZE: Int = 3

		/** Maximum time allowed to wait for the result of an image capture */
		private const val IMAGE_CAPTURE_TIMEOUT_MILLIS: Long = 5000

		/** Helper data class used to hold capture metadata with their associated image */
		data class CombinedCaptureResult(val image: Image, val metadata: CaptureResult, val format: Int):
			Closeable {
			override fun close() = image.close()
		}

		/**
		 * Create a [File] named a using formatted timestamp with the current date and time.
		 *
		 * @return [File] created.
		 */
		@Suppress("SameParameterValue")
		private fun createFile(nir: String, fruitApplication: String, fruitID: Int, compressionLevel: String): File {
			val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
			val rootDirectory = File(externalStorageDirectory, "/${Utils.appRootPath}")
			val imageDirectory = File(rootDirectory, "/${Utils.rawImageDirectory}")

			val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
			fileFormat = sdf.format(Date())
			val fileExtension = when (compressionLevel) {
				"RAW" -> "dng"
				"lossless" -> "png"
				"lossy" -> "jpg"
				else -> "jpg"
			}
			val output = File(imageDirectory, "IMG_${fileFormat}_${nir}_(${fruitApplication.first()}${fruitApplication.last()}_ID_$fruitID).$fileExtension")
			if (nir == "RGB")
				rgbAbsolutePath = output.absolutePath
			return output
		}
	}
}