package com.shahzaib.mobislp

import android.content.Context
import android.util.Log
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor


class Classification(context: Context, modelPath: String) {
    private var model: Module? = null
    private val min=0.0F
    private val max=1.0F

    init {
        Log.i("Classification Model Load", Utils.assetFilePath(context, modelPath).toString())
        model = Module.load(Utils.assetFilePath(context, modelPath))
    }

    fun <T: Comparable<T>> Iterable<T>.argmax(): Int? {
        return withIndex().maxByOrNull { it.value }?.index
    }

    fun predict(hypercube: FloatArray, channels: Long, width: Long, height: Long): Pair<Int, Int> {
        val shape = longArrayOf(1, channels, height, width)
        val hypercube_tensor = Tensor.fromBlob(hypercube, shape)
        val inputs: IValue = IValue.from(hypercube_tensor)
        val outputs = model?.forward(inputs)?.toTuple()!!
        Log.i("Classification Output", "${outputs.size}")
        val ripeness = outputs[0].toTensor().dataAsFloatArray.toList()
        val remainingLife = outputs[1].toTensor().dataAsFloatArray.toList()

        /*for (f1 in ripeness)
            Log.i("Ripeness value", "$f1")
        for (f2 in remainingLife)
            Log.i("Remaining Life value", "$f2")*/

        val ripenessArgmax = ripeness.argmax()
        val remainingLifeArgmax = remainingLife.argmax()

        return Pair<Int, Int>(ripenessArgmax!!, remainingLifeArgmax!!)
    }

}