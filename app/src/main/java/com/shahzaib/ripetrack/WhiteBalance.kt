package com.shahzaib.ripetrack

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color.rgb
import android.util.Log
import org.apache.commons.math3.linear.*
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import kotlin.math.pow
import kotlin.math.roundToInt

@Suppress("unused")
class WhiteBalance(context: Context) {
    private var modelAwb: Module? = null
    private var modelS: Module? = null
    private var modelT: Module? = null
    private var outputAwb: Tensor? = null
    private var outputT: Tensor? = null
    private var outputS: Tensor? = null

    private var mean = floatArrayOf(0.0f, 0.0f, 0.0f)
    private var std = floatArrayOf(1.0f, 1.0f, 1.0f)

    init {
        modelAwb = Module.load(Utils.assetFilePath(context, "mobile_awb.pt"))
        modelS = Module.load(Utils.assetFilePath(context, "mobile_s.pt"))
        modelT = Module.load(Utils.assetFilePath(context, "mobile_t.pt"))
    }

    private fun deepWB(image: Tensor): Tensor {
        val inputs: IValue = IValue.from(image)
        outputAwb = modelAwb?.forward(inputs)?.toTensor()!!
        outputT = modelT?.forward(inputs)?.toTensor()!!
        outputS = modelS?.forward(inputs)?.toTensor()!!

        /*Log.i(
            "Mapping Func",
            "Calling the function: ${image.shape().toList()}, ${outputAwb!!.shape().toList()}"
        )

        val regressionFunc = getMappingFunc(image, outputAwb!!)

        Log.i(
            "(Mapping) Regression Func",
            "Dimensions: (${regressionFunc.rowDimension}, ${regressionFunc.columnDimension}), $regressionFunc"
        )

        val appliedMapping = applyMappingFunc(image, regressionFunc)

        Log.i("(Mapping) Applied Coefficients", "$appliedMapping")

        val resultClipped = outOfGamutClipping(appliedMapping)

        Log.i("(Mapping) Clipped Result", "$resultClipped")*/

       // return colorTempInterpolate(outputT!!, outputS!!)


        return colorTempInterpolate(
            outOfGamutClipping(applyMappingFunc(image, getMappingFunc(image, outputT!!))),
            outOfGamutClipping(applyMappingFunc(image, getMappingFunc(image, outputS!!)))
        )
    }

    /* reshapes image Tensors of shape [1, 3, row, col] to [row*col, 3], which is the equivalent of np.reshape(image, [-1, 3]) in Python */
    private fun rearrangeToRGBPixels(image: Tensor): Tensor {
        val tensorData = image.dataAsFloatArray

        // size is 3 (for r,g,b) * rows * cols e.g. 3 * 640 * 480
        val imageData = FloatArray(tensorData.size)

        val numPixels = (image.shape()[2] * image.shape()[3]).toInt()
        Log.i("WhiteBalance", "(Mapping) Rearrange to RGB Pixels: ${image.shape().toList()} Num Pixels: $numPixels")

        // rearrange imageData into the shape row * col * 3
        var idx = 0             // for iterating over imageData with steps of 3 (pixel-by-pixel)
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
     * Kernel function: kernel(r, g, b) -> (r,g,b,rg,rb,gb,r^2,g^2,b^2,rgb,1)
     * Ref: Hong, et al., "A study of digital camera colorimetric characterization
     * based on polynomial modeling." Color Research & Application, 2001.
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
        return Tensor.fromBlob(result.toFloatArray(), longArrayOf(image.shape()[2] * image.shape()[3], 11))
    }

    // convert a 1D array into a 2D array as such: Array<DoubleArray> of size [row][col] (row is inferred)
    private fun arrayTo2DArray(array: DoubleArray, col: Int): Array<DoubleArray> {
        return array.toList().chunked(col) { it.toDoubleArray() }.toTypedArray()
    }

    private fun floatToDoubleArray(input: FloatArray): DoubleArray {
        return DoubleArray(input.size) { input[it].toDouble() }
    }

    /*
    Assuming input images are of the form [1, 3, row, col]
     */
    private fun getMappingFunc(image1: Tensor, image2: Tensor): RealMatrix {
        // rearrange image tensors to arrays of pixels
        //val image1Rearranged = rearrangeToRGBPixels(image1)
        val image2Rearranged = rearrangeToRGBPixels(image2)

        // convert images to 2D arrays (matrices) of dimension nx11 and nx3
        val image1Mat = arrayTo2DArray(
            //kernelP(image1Rearranged).dataAsDoubleArray, 11
            floatToDoubleArray(kernelP(image1).dataAsFloatArray), 11
        )

        val image2Mat = arrayTo2DArray(floatToDoubleArray(image2Rearranged.dataAsFloatArray), 3)

        // Use QR decomposition to solve for X in the equation AX = B, where A = image1Mat (input) and B = image2Mat (output)
        val qrDecomposition = QRDecomposition(Array2DRowRealMatrix(image1Mat))

        //val q = qrDecomposition.q
        //val r = qrDecomposition.r

        return qrDecomposition.solver.solve(Array2DRowRealMatrix(image2Mat))
    }

    // assuming image has dimensions 1x3x640x480
    private fun applyMappingFunc(image: Tensor, mapping: RealMatrix): Tensor {
        val imageFeatures = kernelP(image)
        Log.i("WhiteBalance", "KernelP: ${imageFeatures.shape().toList()}")

        // AX = B, here we're multiplying A (nx11) by X (11x3)
        // Note we need to use arrayTo2DArray as floatToDoubleArray() gives a vector, but the proper dimensions are required for mat multiplication
        val input = Array2DRowRealMatrix(arrayTo2DArray(floatToDoubleArray(imageFeatures.dataAsFloatArray), 11))

        Log.i("WhiteBalance", "(Mapping) applyMappingFunc; Input: ${listOf(input.rowDimension, input.columnDimension)}, Mapping: ${listOf(mapping.rowDimension, mapping.columnDimension)}")
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
        //val result = DoubleArray(predictions.rowDimension * predictions.columnDimension)
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

        /*rList.forEach {
            result[idx] = it
            idx += 1
        }
        gList.forEach {
            result[idx] = it
            idx += 1
        }
        bList.forEach {
            result[idx] = it
            idx += 1
        }*/

        // return a Tensor in the same shape as the original image
        return Tensor.fromBlob(result, image.shape())
    }

    private fun outOfGamutClipping(image: Tensor): Tensor {
        // why DoubleArray()? because this is a tensor_float64, not tensor_float32
        val imageData = image.dataAsFloatArray

        imageData.indices.forEach {
            val item = imageData[it]
            imageData[it] = if (item > 1) 1F else if (item < 0) 0F else item
        }

        return Tensor.fromBlob(imageData, image.shape())
    }

    /*// rearrange both images to nx3 format
    val image1Rearranged = rearrangeToRGBPixels(image1)
    val image2Rearranged = rearrangeToRGBPixels(image2)

    // input & output are 2D arrays with nx11 and nx3 dimensions respectively
    val input = kernelP(image1Rearranged)
    val output = Array2DRowRealMatrix(image2Rearranged.dataAsDoubleArray)

    // regression object
    val fitter = OLSMultipleLinearRegression()

    // fit the model
    fitter.newSampleData(image2Rearranged.dataAsDoubleArray, Array2DRowRealMatrix(input.dataAsDoubleArray).data)

    // coefficients
    val coefficients = fitter.estimateRegressionParameters()*/
    /*
    * This function does the following python equivalent operation:
    * I_D = I_T * g_D + I_S * (1 - g_D)
    * */

    private fun multiplyAndAddTensors(tensor1: Tensor, tensor2: Tensor, scalar: Float): Tensor {
        val float1 = tensor1.dataAsFloatArray
        val float2 = tensor2.dataAsFloatArray
        val resulting = FloatArray(float1.size)

        for (i in resulting.indices) {
            resulting[i] = (scalar * float1[i]) + (float2[i] * (1-scalar))
        }
        return Tensor.fromBlob(resulting, longArrayOf(1, 3, tensor1.shape()[2], tensor1.shape()[3]))
    }

    private fun colorTempInterpolate(iT: Tensor, iS: Tensor): Tensor {
        val colorTemperatures = mapOf('T' to 2850, 'F' to 3800, 'D' to 5500, 'C' to 6500, 'S' to 7500)
        val cct1 = colorTemperatures['T']!!.toDouble()
        val cct2 = colorTemperatures['S']!!.toDouble()

        // Interpolation weight
        val cct1inv = 1.0 / cct1
        val cct2inv = 1.0 / cct2
        val tempinvD = 1.0 / colorTemperatures['D']!!.toDouble()

        val gD = (tempinvD - cct2inv) / (cct1inv - cct2inv)

        val iD = multiplyAndAddTensors(iT, iS, gD.toFloat())
        Log.i("ID IT IS", "${iD.shape().toList()} ${iT.shape().toList()} ${iS.shape().toList()}")
        return iD
    }

    private fun floatArrayToBitmap(floatArray: FloatArray, width: Int, height: Int) : Bitmap {
        // Create empty bitmap in ARGB format
        val bmp: Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height * 3)

        // mapping smallest value to 0 and largest value to 255
        val maxValue = floatArray.max()
        val minValue = floatArray.min()
        val delta = maxValue-minValue

        // Define if float min..max will be mapped to 0..255 or 255..0
        val conversion = { v: Float -> ((v-minValue)/delta*255.0f).roundToInt()}

        // copy each value from float array to RGB channels
        for (i in 0 until width * height) {
            val r = conversion(floatArray[i])
            val g = conversion(floatArray[i+width*height])
            val b = conversion(floatArray[i+2*width*height])
            pixels[i] = rgb(r, g, b) // you might need to import for rgb()
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)

        return bmp
    }

    fun whiteBalance(rgbBitmap: Bitmap): Bitmap {
        val rgbTensor = TensorImageUtils.bitmapToFloat32Tensor(rgbBitmap, mean, std)

        Log.i("RGB Tensor", "$rgbTensor")
        Log.i("RGB Tensor after kernelP", "${kernelP(rgbTensor)}")

        Log.i("imageShape", "${rgbTensor.shape().toList()}")

        val outputs = deepWB(rgbTensor)
        Log.i("White Balancing", "Output Shape: ${outputs.shape().toList()}")

        return floatArrayToBitmap(outputs.dataAsFloatArray, outputs.shape()[3].toInt(), outputs.shape()[2].toInt())
    }
}