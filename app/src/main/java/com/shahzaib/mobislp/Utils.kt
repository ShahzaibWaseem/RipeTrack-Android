package com.shahzaib.mobislp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.net.toUri
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.opencsv.CSVWriter
import com.shahzaib.mobislp.Utils.appRootPath
import com.shahzaib.mobislp.Utils.croppedImageDirectory
import com.shahzaib.mobislp.Utils.imageFormat
import com.shahzaib.mobislp.Utils.processedImageDirectory
import com.shahzaib.mobislp.Utils.rawImageDirectory
import com.shahzaib.mobislp.Utils.torchHeight

object Utils {
	const val previewHeight = 800
	const val previewWidth = 600
	const val torchHeight = 640
	const val torchWidth = 480
	private const val aligningFactorX = 37  //This is 37 if picture captured in portrait [35-41 if un-warped] 83 if landscape
	private const val aligningFactorY = 87  //This is 83 if picture captured in portrait [74 if un-warped] 100 if landscape
	const val appRootPath = "MobiSLP"
	const val rawImageDirectory = "rawImages"
	const val croppedImageDirectory = "croppedImages"
	const val processedImageDirectory = "processedImages"
	const val hypercubeDirectory = "reconstructedHypercubes"
	const val boundingBoxWidth = 22.5F
	const val boundingBoxHeight = 22.5F
	const val imageFormat = ImageFormat.JPEG

	fun assetFilePath(context: Context, assetName: String): String? {
		val file = File(context.filesDir, assetName)
		if (file.exists() && file.length() > 0) {
			return file.absolutePath
		}
		try {
			context.assets.open(assetName).use { `is` ->
				FileOutputStream(file).use { os ->
					val buffer = ByteArray(4 * 1024)
					var read: Int
					while (`is`.read(buffer).also { read = it } != -1) {
						os.write(buffer, 0, read)
					}
					os.flush()
				}
				return file.absolutePath
			}
		} catch (e: IOException) {
			Log.e("PyTorch Android", "Error process asset $assetName to file path")
		}
		return null
	}

	fun getCameraIDs(context: Context, application: Int): Pair<String, String> {
		var cameraIdRGB = ""
		var cameraIdNIR = ""
		val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

		var cameraList = enumerateCameras(cameraManager)
		if (application == MainActivity.MOBISPECTRAL_APPLICATION) {
			cameraList = getMobiSpectralConfigCameras(cameraList)

			for (camera in cameraList) {
				Log.i("Available Cameras", camera.title)
				if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR)
					cameraIdNIR = camera.cameraId
				else
					cameraIdRGB = camera.cameraId
			}
			// OnePlus has hidden their Photochrom camera, so accessing it via Intent.
			if (cameraIdNIR == "") {
				cameraIdNIR = if (Build.PRODUCT == "OnePlus8Pro") "OnePlus" else "No NIR Camera"
				cameraIdRGB = if (Build.PRODUCT == "OnePlus8Pro") "0" else cameraIdRGB
			}
		}
		return Pair(cameraIdRGB, cameraIdNIR)
	}

	fun cropImage(bitmap: Bitmap, left: Float, top: Float): Bitmap {
		return Bitmap.createBitmap(bitmap, left.toInt(), top.toInt(), (boundingBoxWidth*2).toInt(), (boundingBoxHeight*2).toInt(), null, false)
	}

	fun fixedAlignment(imageRGB: Bitmap): Bitmap {
		Log.i("Aligned RGB", "$aligningFactorX + $torchWidth = ${torchWidth + aligningFactorX} (${imageRGB.width})")
		Log.i("Aligned RGB", "$aligningFactorY + $torchHeight = ${torchHeight + aligningFactorY} (${imageRGB.height})")
		val alignedImageRGB = Bitmap.createBitmap(imageRGB, aligningFactorX, aligningFactorY, torchWidth, torchHeight, null, false)
		Log.i("Aligned RGB", "Resulting Bitmap: W ${alignedImageRGB.width} H ${alignedImageRGB.height}")
		return alignedImageRGB
	}

	@Suppress("DEPRECATION")
	fun vibrate(context: Context) {
		val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			val vibratorManager =  context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
			vibratorManager.defaultVibrator
		} else {
			context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
		}

		val vibrationDuration = 500L
		if (vibrator.hasVibrator()) {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				vibrator.vibrate(VibrationEffect.createOneShot(vibrationDuration, VibrationEffect.DEFAULT_AMPLITUDE))
			}
			else {
				vibrator.vibrate(vibrationDuration)
			}
		}
	}
}
lateinit var csvFile: File

/** Helper class used as a data holder for each selectable camera format item */
data class FormatItem(val title: String, val cameraId: String, val format: Int, val orientation: String, val sensorArrangement: Int)

data class MobiSLPCSVFormat(val fruitID: String, val originalImageRGB: String, val originalImageNIR: String, val actualLabel: String) {
	fun csvFormat(): Array<String> {
		return arrayOf(fruitID, originalImageRGB, originalImageNIR, actualLabel)
	}
}

/** Helper function used to convert a lens orientation enum into a human-readable string */
private fun lensOrientationString(value: Int) = when(value) {
	CameraCharacteristics.LENS_FACING_BACK -> "Back"
	CameraCharacteristics.LENS_FACING_FRONT -> "Front"
	CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
	else -> "Unknown"
}

fun getMobiSpectralConfigCameras(availableCameras: MutableList<FormatItem>): MutableList<FormatItem> {
	val usableCameraList: MutableList<FormatItem> = mutableListOf()

	for (camera in availableCameras) {
		if (camera.sensorArrangement == CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) {
			val nirLensOrientation = camera.orientation
			usableCameraList.add(camera)
			for (otherCamera in availableCameras) {
				if ((otherCamera.orientation) == nirLensOrientation && otherCamera.cameraId != camera.cameraId) {
					usableCameraList.add(otherCamera)
				}
			}
		}
	}
	return availableCameras
}

/** Helper function used to list all compatible cameras and supported pixel formats */
@SuppressLint("InlinedApi")
fun enumerateCameras(cameraManager: CameraManager): MutableList<FormatItem> {
	val availableCameras: MutableList<FormatItem> = mutableListOf()

	// Get list of all compatible cameras
	val cameraIds = cameraManager.cameraIdList.filter {
		val characteristics = cameraManager.getCameraCharacteristics(it)
		val orientation = lensOrientationString(characteristics.get(CameraCharacteristics.LENS_FACING)!!)
		val isNIR = if(characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
			== CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) "NIR" else "RGB"

		Log.i("All Cameras", "Orientation: $orientation,\tRGB: $isNIR,\tLogical: $it,\tPhysical: ${characteristics.physicalCameraIds}")

		val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
		capabilities?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
	}

	// Iterate over the list of cameras and return all the compatible ones
	cameraIds.forEach { id ->
		val characteristics = cameraManager.getCameraCharacteristics(id)
		val orientation = lensOrientationString(characteristics.get(CameraCharacteristics.LENS_FACING)!!)
		val isNIR = if(characteristics.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)
			== CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR) "NIR" else "RGB"

		// All cameras *must* support JPEG output so we don't need to check characteristics
		// Return cameras that support NIR Filter Arrangement
		if (isNIR == "NIR")
			availableCameras.add(FormatItem("$orientation, ($id), $isNIR", id, imageFormat,
				orientation, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR))
		else
			availableCameras.add(FormatItem("$orientation, ($id), RGB", id, imageFormat,
				orientation, CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB))
	}

	return availableCameras
}

fun makeFolderInRoot(directoryPath: String, context: Context) {
	val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
	val directory = File(externalStorageDirectory, "/$directoryPath")

	csvFile = File(directory, "MobiSLP_Logs.csv")
	if (!csvFile.exists()) {
		val header = MobiSLPCSVFormat("Fruit ID", "Original RGB Path", "Original NIR Path", "Actual Label")

		val writer = CSVWriter(
			FileWriter(csvFile, false),
			',',
			CSVWriter.NO_QUOTE_CHARACTER,
			CSVWriter.DEFAULT_ESCAPE_CHARACTER,
			"\r\n"
		)
		writer.writeNext(header.csvFormat())
		writer.close()
		MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null)
	}
}

fun makeDirectory(folder: String) {
	val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
	val rootDirectory = File(externalStorageDirectory, appRootPath)
	val directory = File(rootDirectory, "/$folder")
	if (!directory.exists()) { directory.mkdirs() }
}

fun saveProcessedImages (context: Context, rgbBitmap: Bitmap, nirBitmap: Bitmap, rgbFileName: String, nirFileName: String, directory: String) {
	val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
	val rootDirectory = File(externalStorageDirectory, appRootPath)
	val imageDirectory = File(rootDirectory, "/$directory")

	val rgbImage = File(imageDirectory, rgbFileName)
	val nirImage = File(imageDirectory, nirFileName)

	when (directory) {
		rawImageDirectory -> {
			MainActivity.originalImageRGB = rgbImage.absolutePath.toUri().toString()
			MainActivity.originalImageNIR = nirImage.absolutePath.toUri().toString()
		}
		processedImageDirectory -> {
			MainActivity.processedImageRGB = rgbImage.absolutePath.toUri().toString()
			MainActivity.processedImageNIR = nirImage.absolutePath.toUri().toString()
		}
		croppedImageDirectory -> {
			MainActivity.croppedImageRGB = rgbImage.absolutePath.toUri().toString()
			MainActivity.croppedImageNIR = nirImage.absolutePath.toUri().toString()
		}
	}

	Log.i("FilePath", "${rgbImage.absolutePath.toUri()} ${nirImage.absolutePath.toUri()}")

	Thread {
		try {
			var fos = FileOutputStream(rgbImage)
			rgbBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
			fos.flush()
			fos.close()

			fos = FileOutputStream(nirImage)
			nirBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
			fos.flush()
			fos.close()
		} catch (e: IOException) {
			e.printStackTrace()
		}
	}.start()
	MediaScannerConnection.scanFile(context, arrayOf(rgbImage.absolutePath, nirImage.absolutePath), null, null)
}

@Suppress("unused")
fun saveHypercube(hypercubeFileName: String, hypercube: FloatArray, directory: String) {
	val externalStorageDirectory = Environment.getExternalStorageDirectory().toString()
	val rootDirectory = File(externalStorageDirectory, appRootPath)
	val imageDirectory = File(rootDirectory, "/$directory")
	val hypercubeFile = File(imageDirectory, hypercubeFileName)
	BufferedWriter(FileWriter(hypercubeFile)).use { stream ->
		for (value in hypercube) {
			stream.write(value.toString())
			stream.write(",")
		}
	}
}

fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
	var width = bitmap.width
	var height = bitmap.height

	val aspectRatio = width.toFloat() / height.toFloat()
	if (aspectRatio > 1) {
		width = maxSize
		height = (width / aspectRatio).toInt()
	}
	else {
		height = maxSize
		width = (height * aspectRatio).toInt()
	}
	return Bitmap.createScaledBitmap(bitmap, width, height, true)
}

fun readImage(inputFile: String): Bitmap {
	return BitmapFactory.decodeFile(inputFile, BitmapFactory.Options().apply { inJustDecodeBounds = false; inPreferredConfig = Bitmap.Config.ARGB_8888 })
}

fun compressImage(bmp: Bitmap): Bitmap {
	var bitmap = resizeBitmap(bmp, torchHeight)
	Log.i("Utils.copyFile", "${bitmap.height} ${bitmap.width}")
	if (bitmap.width > bitmap.height) {     // rotate so the image is always up right (portrait)
		bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90F); }, false)
	}
	return bitmap
}

suspend fun saveImage(bitmap: Bitmap, outputFile: File): File = suspendCoroutine { cont ->
	val stream = ByteArrayOutputStream()
	bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
	val imageBytes = stream.toByteArray()

	try{
		FileOutputStream(outputFile).use { it.write(imageBytes) }
		cont.resume(outputFile)
		Log.i("Filename", outputFile.toString())
	} catch (exc: IOException) {
		Log.e("Utils.saveOneImage", "Unable to write JPEG image to file $exc")
		cont.resumeWithException(exc)
	}
}

fun addCSVLog (context: Context) {
	if (csvFile.exists()) {
		val entry = MobiSLPCSVFormat(MainActivity.fruitID, MainActivity.originalImageRGB, MainActivity.originalImageNIR, MainActivity.actualLabel)

		val writer = CSVWriter(
			FileWriter(csvFile, true),
			',',
			CSVWriter.NO_QUOTE_CHARACTER,
			CSVWriter.DEFAULT_ESCAPE_CHARACTER,
			"\r\n"
		)
		writer.writeNext(entry.csvFormat())
		writer.close()
		MediaScannerConnection.scanFile(context, arrayOf(csvFile.absolutePath), null, null)
	}
	else
		makeFolderInRoot(appRootPath, context)
}