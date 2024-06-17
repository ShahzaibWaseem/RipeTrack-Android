package com.shahzaib.ripetrack

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor


class Classification(context: Context, modelPath: String) {
    private var model: Module? = null

    init {
        Log.i("Classification Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    private fun <T: Comparable<T>> Iterable<T>.argmax(): Int? {
        return withIndex().maxByOrNull { it.value }?.index
    }

    fun predict(hypercube: FloatArray, channels: Long, width: Long, height: Long): Pair<Int, Int> {
        val shape = longArrayOf(1, channels, height, width)
        val hypercubeTensor = Tensor.fromBlob(hypercube, shape)
        val inputs: IValue = IValue.from(hypercubeTensor)
        val outputs = model?.forward(inputs)?.toTuple()!!

        Log.i("Classification.Predict", "Size: ${outputs.size}")

        val ripeness = outputs[0].toTensor().dataAsFloatArray.toList()
        val remainingLife = outputs[1].toTensor().dataAsFloatArray.toList()
        Log.i("Classification.Predict", "$ripeness, $remainingLife")

        val ripenessArgmax = ripeness.argmax()
        val remainingLifeArgmax = remainingLife.argmax()

        return Pair(ripenessArgmax!!, remainingLifeArgmax!!)
    }

}