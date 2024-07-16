package com.shahzaib.ripetrack

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.apache.commons.math3.linear.*
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.nio.FloatBuffer
import kotlin.math.pow

@Suppress("unused")
class WhiteBalance(private val context: Context) {
    private var modelAwb: Module? = null
    private var modelS: Module? = null
    private var modelT: Module? = null
    private var outputAwb: Tensor? = null
    private var outputT: Tensor? = null
    private var outputS: Tensor? = null

    init {
        modelAwb = Module.load(Utils.assetFilePath(context, "mobile_awb.pt"))
        modelS = Module.load(Utils.assetFilePath(context, "mobile_s.pt"))
        modelT = Module.load(Utils.assetFilePath(context, "mobile_t.pt"))
    }

    // converts an image bitmap to a tensor with the option of normalizing the color values
    private fun bitmapToTensor(bitmap: Bitmap, normalize: Boolean): Tensor {
        val width = bitmap.width
        val height = bitmap.height
        val pixelCount = width*height
        val pixels = IntArray(pixelCount)

        // extract pixels from the bitmap into the 'pixels' array
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // allocate float buffer to be used in creating the tensor
        val outBuffer: FloatBuffer = Tensor.allocateFloatBuffer(3 * width * height) // note: 3 channels

        for (i in 0 until pixelCount) {
            val color = pixels[i]

            // extract R, G, B values from each pixel
            var red = (color shr 16 and 0xFF).toFloat()
            var green = (color shr 8 and 0xFF).toFloat()
            var blue = (color and 0xFF).toFloat()

            if (normalize) {
                red /= 255F
                green /= 255F
                blue /= 255F
            }

            // tensor's float buffer is ordered in terms of channels (R, G, & B)
            outBuffer.put(i, red)
            outBuffer.put(i + pixelCount, green)
            outBuffer.put(i + pixelCount + pixelCount, blue)
        }

        return Tensor.fromBlob(outBuffer, longArrayOf(1, 3, height.toLong(), width.toLong()))
    }

    // reshapes image Tensors of shape [1, 3, row, col] to [row*col, 3], which is the equivalent of np.reshape(image, [-1, 3]) in Python
    private fun rearrangeToRGBPixels(image: Tensor): Tensor {
        val tensorData = image.dataAsFloatArray

        // size is 3 (for r,g,b) * rows * cols e.g. 3 * 640 * 480
        val imageData = FloatArray(tensorData.size)

        val numPixels = (image.shape()[2] * image.shape()[3]).toInt()

        // rearrange imageData into the shape row * col * 3
        var idx = 0 // for iterating over imageData with steps of 3 (pixel-by-pixel)
        for (i in 0 until numPixels) {
            // tensorData is comprised of an entire row for reds, one for greens and one for blues
            // these are extracted from their corresponding rows and the pixel is gathered back together
            imageData[idx] = tensorData[i]
            imageData[idx + 1] = tensorData[i + numPixels]
            imageData[idx + 2] = tensorData[i + (numPixels + numPixels)]
            idx += 3
        }

        return Tensor.fromBlob(imageData, longArrayOf(numPixels.toLong(), 3))
    }

    /*
    Kernel function: kernel(r, g, b) -> (r,g,b,rg,rb,gb,r^2,g^2,b^2,rgb,1)
    Ref: Hong, et al., "A study of digital camera colorimetric characterization
    based on polynomial modeling." Color Research & Application, 2001.
     */
    private fun kernelP(image: Tensor): Tensor {
        val result = mutableListOf<Float>() // could potentially use a FloatArray() for quicker results

        // rearrange image to nx3 form i.e. an array of pixels
        val imageReshaped = rearrangeToRGBPixels(image)
        val imageData = imageReshaped.dataAsFloatArray

        for (i in imageData.indices step 3) {
            val r = imageData[i]
            val g = imageData[i + 1]
            val b = imageData[i + 2]
            result.add(r)
            result.add(g)
            result.add(b)
            result.add(r * g)
            result.add(r * b)
            result.add(g * b)
            result.add(r.pow(2))
            result.add(g.pow(2))
            result.add(b.pow(2))
            result.add(r * g * b)
            result.add(1F)
        }

        // image Tensor is usually of the shape 1 x 3 x row x col, so we use row*col to get the pixel count so that
        // the resulting tensor has shape (row*col)x11
        return Tensor.fromBlob(
            result.toFloatArray(), longArrayOf(image.shape()[2] * image.shape()[3], 11)
        )
    }

    // converts the data inside a tensor to uint8 (UByte) type
    private fun convertTensorDataToUByte(tens: Tensor): Tensor {
        // assuming it's a float32 tensor
        val tensorData = tens.dataAsFloatArray
        val floatArr = FloatArray(tensorData.size)
        for (idx in tensorData.indices) {
            // trick: must convert to Int first before converting to UByte() and then back to Float
            floatArr[idx] = tensorData[idx].toInt().toUByte().toFloat()
        }
        return Tensor.fromBlob(floatArr, tens.shape())
    }

    // convert a 1D array into a 2D array as such: Array<T> of arrangement [row=-1][col] (row is inferred)
    private fun arrayTo2DArray(array: DoubleArray, col: Int): Array<DoubleArray> {
        return array.toList().chunked(col) { it.toDoubleArray() }.toTypedArray()
    }
    private fun arrayTo2DArray(array: FloatArray, col: Int): Array<FloatArray> {
        return array.toList().chunked(col) { it.toFloatArray() }.toTypedArray()
    }

    private fun createORTSession(ortEnvironment: OrtEnvironment, resourceId: Int): OrtSession {
        val modelBytes = context.resources.openRawResource(resourceId).readBytes()
        return ortEnvironment.createSession(modelBytes)
    }

    // use quantized models to perform regression on the image and return a white-balanced output (Tungsten / Shade setting)
    @Suppress("UNCHECKED_CAST")
    private fun regressionInference(image: Array<FloatArray>, ortSession: OrtSession,
                                    ortEnvironment: OrtEnvironment): Array<FloatArray> {
        val inputName = ortSession.inputNames.iterator().next()
        val imageTensor = OnnxTensor.createTensor(ortEnvironment, image)

        val results = ortSession.run(mapOf(inputName to imageTensor))

        return results[0].value as Array<FloatArray>
    }

    private fun convert2DArrayToFloatArray(array: Array<FloatArray>): FloatArray {
        // assuming same # columns for all rows in 'array'

        val floatArray = FloatArray(array.size * array[0].size)

        var idx = 0
        array.forEach {row ->
            row.forEach {
                floatArray[idx] = it
                idx++
            }
        }

        return floatArray
    }

    // clips out-of-gamut values inside a normalized tensor
    private fun outOfGamutClipping(image: Tensor): Tensor {
        val imageData = image.dataAsFloatArray

        imageData.indices.forEach {
            val item = imageData[it]
            imageData[it] = if (item > 1F) 1F else if (item < 0F) 0F else item
        }

        // recreate the tensor using the clipped data
        return Tensor.fromBlob(imageData, image.shape())
    }

    /*
    * This function does the following python equivalent operation:
    * I_D = I_T * g_D + I_S * (1 - g_D)
    * */
    private fun performLinearInterpolation(imageT: Tensor, imageS: Tensor, interpolationCoefficient: Float): Tensor {
        val floatArrayT = imageT.dataAsFloatArray
        val floatArrayS = imageS.dataAsFloatArray

        val result = FloatArray(floatArrayT.size)

        for (i in result.indices)
            result[i] = (interpolationCoefficient * floatArrayT[i]) + ((1F-interpolationCoefficient) * floatArrayS[i])

        return Tensor.fromBlob(result, longArrayOf(1, 3, imageT.shape()[2], imageT.shape()[3]))
    }

    // interpolates color temperatures from Tungsten & Shade setting into Daylight
    private fun colorTempInterpolate(iT: Tensor, iS: Tensor): Tensor {
        val colorTemperatures = mapOf('T' to 2850, 'F' to 3800, 'D' to 5500, 'C' to 6500, 'S' to 7500)
        val cct1 = colorTemperatures['T']!!.toDouble()
        val cct2 = colorTemperatures['S']!!.toDouble()

        // interpolation weight
        val cct1inv = 1.0 / cct1
        val cct2inv = 1.0 / cct2
        val tempinvD = 1.0 / colorTemperatures['D']!!.toDouble()

        // interpolation coefficient
        val gD = (tempinvD - cct2inv) / (cct1inv - cct2inv)

        // perform linear interpolation to acquire image in Daylight setting
        val iD = performLinearInterpolation(iT, iS, gD.toFloat())

        return iD
    }

    // computes the image WB in Tungsten (2850K) & Shade (7500K) settings & performs color temp interpolation to output the final image
    private fun deepWB(image: Tensor, normalizedImage: Tensor): Tensor {

        // get image in Tungsten & Shade WB setting
        val inputs: IValue = IValue.from(normalizedImage)
        outputAwb = modelAwb?.forward(inputs)?.toTensor()!!
        outputT = modelT?.forward(inputs)?.toTensor()!!
        outputS = modelS?.forward(inputs)?.toTensor()!!

        // (1) compute polynomial features of the image
        // (2) important: convert the resulting tensor data into type uint8 (following the original Deep White Balance Editing Code)
        // (3) reshape it into an [n, 11] form, where n = # pixels
        val image2d = arrayTo2DArray(convertTensorDataToUByte(kernelP(image)).dataAsFloatArray, 11)

        // set up an ORT Environment to run quantized linear regression models that
        // extract the mapping matrix M from the image to its polynomial features
        // and apply it to get the final image
        val ortEnvironmentT = OrtEnvironment.getEnvironment()
        val ortEnvironmentS = OrtEnvironment.getEnvironment()
        val ortSessionT = createORTSession(ortEnvironmentT, R.raw.lr_t)
        val ortSessionS = createORTSession(ortEnvironmentS, R.raw.lr_s)
        val linearRegressionOutputT = regressionInference(image2d, ortSessionT, ortEnvironmentT)
        val linearRegressionOutputS = regressionInference(image2d, ortSessionS, ortEnvironmentS)

        // convert outputs from Array<FloatArray> format into FloatArray
        // rearrange the FloatArray into separate red, green, & blue channels
        val outTFloatArray = rearrangePixelsToColorChannels(convert2DArrayToFloatArray(linearRegressionOutputT))
        val outSFloatArray = rearrangePixelsToColorChannels(convert2DArrayToFloatArray(linearRegressionOutputS))

        // create a tensor out of the rearranged float arrrays & clip its out-of-gamut values
        val gamutClippedS = outOfGamutClipping(Tensor.fromBlob(outSFloatArray, normalizedImage.shape()))
        val gamutClippedT = outOfGamutClipping(Tensor.fromBlob(outTFloatArray, normalizedImage.shape()))

        // interpolate color temperature from Tungsten & Shade to Daylight (5500K) WB setting
        return colorTempInterpolate(gamutClippedT, gamutClippedS)
    }

    // combines floating-point color & alpha values into integer ARGB format
    private fun colorsToARGB(red: Float, green: Float, blue: Float, alpha: Float = 1F): Int {
        return ((alpha  * 255.0f).toInt() shl 24) or
                ((red   * 255.0f).toInt() shl 16) or
                ((green * 255.0f).toInt() shl  8) or
                (blue   * 255.0f).toInt()
    }

    // converts a FloatArray comprised of the separated R, G, & B channels in an image into a Bitmap
    private fun floatArrayToBitmap(floatArray: FloatArray, width: Int, height: Int) : Bitmap {
        // Create empty bitmap in ARGB format
        val bmp: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        // combine RGB values and save them as pixels
        for (i in 0 until width * height) {
            val r = floatArray[i]
            val g = floatArray[i+width*height]
            val b = floatArray[i+2*width*height]

            // argb() and rgb() functions expect normalized color values within [0,1] range, here we give default 100% alpha value (opaque) --> refer to colorsToARGB comments
            pixels[i] = colorsToARGB(r, g, b) // you might need to import for rgb()
        }

        bmp.setPixels(pixels, 0, width, 0, 0, width, height)

        return bmp
    }

    fun whiteBalance(rgbBitmap: Bitmap): Bitmap {
        val rgbTensor = bitmapToTensor(rgbBitmap, normalize = false)
        val normalizedRGBTensor = bitmapToTensor(rgbBitmap, normalize = true)

        val outputs = deepWB(rgbTensor, normalizedRGBTensor)    // outputs has shape [1, 3, 640, 480]

        return floatArrayToBitmap(outputs.dataAsFloatArray, outputs.shape()[3].toInt(), outputs.shape()[2].toInt())
    }

    private fun floatToDoubleArray(input: FloatArray): DoubleArray {
        return DoubleArray(input.size) { input[it].toDouble() }
    }

    /* the functions below are not used in this version */

    // helper functions that extract the RGBA values from a floating-point pixel
    private fun extractRGBA(pixel: Float): List<UByte> {
        val intRep = pixel.toRawBits()  // get int representation of the float for bit extraction
        val alpha = (intRep shr 24).toUByte()
        val blue = (intRep shl 8 shr 24).toUByte()
        val green = (intRep shl 16 shr 24).toUByte()
        val red = (intRep shl 24 shr 24).toUByte()

        return listOf(alpha, blue, green, red)
    }
    private fun extractRGBA(pixel: Int): List<Int> {
        val alpha = (pixel shr 24 and 0xFF)
        val red = (pixel shr 16 and 0xFF)
        val green = (pixel shr 8 and 0xFF)
        val blue = (pixel and 0xFF)

        return listOf(alpha, blue, green, red)
    }

    private fun getMappingFunc(image1: Tensor, image2: Tensor): RealMatrix {
        // (not used) assuming input images are of the form [1, 3, row, col]

        // rearrange image tensors to arrays of pixels
        val image2Rearranged = rearrangeToRGBPixels(image2)

        // convert images to 2D arrays (matrices) of dimension nx11 and nx3
        val image1Mat = arrayTo2DArray(floatToDoubleArray(kernelP(image1).dataAsFloatArray), 11)
        val image2Mat = arrayTo2DArray(floatToDoubleArray(image2Rearranged.dataAsFloatArray), 3)

        // Use QR decomposition to solve for X in the equation AX = B, where A = image1Mat (input) and B = image2Mat (output)
        val qrDecomposition = QRDecomposition(Array2DRowRealMatrix(image1Mat))
        return qrDecomposition.solver.solve(Array2DRowRealMatrix(image2Mat))
    }

    private fun applyMappingFunc(image: Tensor, mapping: RealMatrix): Tensor {
        // (not used) assuming image has dimensions 1x3x640x480
        val imageFeatures = kernelP(image)

        // AX = B, here we're multiplying A (nx11) by X (11x3)
        // Note we need to use arrayTo2DArray as floatToDoubleArray() gives a vector, but the proper dimensions are required for mat multiplication
        val input = Array2DRowRealMatrix(
            arrayTo2DArray(floatToDoubleArray(imageFeatures.dataAsFloatArray), 11)
        )

        val predictions = input.multiply(mapping)
        // reshape image to 1x3x640x480 format
        val rList = mutableListOf<Double>()
        val gList = mutableListOf<Double>()
        val bList = mutableListOf<Double>()

        for (i in 0 until predictions.rowDimension) {
            val currRow = predictions.getRow(i)
            for (j in currRow.indices step 3) {
                rList.add(currRow[j])
                gList.add(currRow[j + 1])
                bList.add(currRow[j + 2])
            }
        }

        // size of result is the total number of entries
        val result = FloatArray(predictions.rowDimension * predictions.columnDimension)

        // for iterating through result
        var idx = 0

        // add the red pixel values first, then the greens and finally the blues
        listOf(rList, gList, bList).forEach{
            it.forEach {item ->
                result[idx] = item.toFloat()
                idx += 1
            }
        }

        // return a Tensor in the same shape as the original image
        return Tensor.fromBlob(result, image.shape())
    }

}